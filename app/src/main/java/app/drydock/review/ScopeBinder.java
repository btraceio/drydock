package app.drydock.review;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Binds a symbol's occurrences in one file to real references by reading
 * the parse tree -- the tier between name matching and a language server
 * (spec §6, the {@code UsageProvider} seam). Java and Kotlin today; the
 * walk is per-language ({@link JavaTreeWalker}, {@link KotlinTreeWalker}
 * -- each built from a probe that read the grammar's real shapes) and the
 * resolution over the walked drafts is shared.
 *
 * <p><strong>What "bound" means here, and what it does not.</strong> An
 * occurrence is bound when its receiver is <em>statically derivable</em>:
 * no receiver or {@code this} with the member declared on a class
 * enclosing the occurrence in the same file; a receiver naming a class
 * (same file, an import, or the same package) whose declaration -- or
 * superclass chain, up to {@link #MAX_SUPERCLASS_DEPTH} -- carries the
 * member; or a local/parameter/field whose declared type is one of those.
 * A bound occurrence is a reference the tree says is real, not a name the
 * text happens to share. What is deliberately NOT claimed: interface and
 * overload resolution, type inference, implicit outer-class {@code this}
 * from a nested class, {@code java.lang} types, and every receiver whose
 * type the file does not state. Those occurrences stay lexical-tier; 
 * under-claiming is the failure mode this design chose over guessing.</p>
 *
 * <p>Node shapes were verified against the shipped grammar (a probe parsed
 * representative source and read the tree tree-sitter itself printed):
 * {@code method_invocation} carries {@code object}/{@code name}/{@code
 * arguments}, {@code field_access} carries {@code object}/{@code field},
 * {@code class_declaration} carries {@code name}/{@code superclass}/
 * {@code interfaces}/{@code body}, and the java binding exposes node text
 * only as byte offsets into the source -- there is no {@code getText()},
 * so every string below is cut out of the file's own UTF-8 bytes.</p>
 *
 * <p>Blocking: parses and a repository filename walk. Never on the FX
 * thread; {@link SymbolPeekService} calls this on its resolve executor. A
 * grammar that fails to load or a file that fails to parse binds nothing
 * -- the caller keeps the lexical answer, never an error.</p>
 */
public final class ScopeBinder {

    /** Guard against pathological trees; the binder declines rather than crawls. */
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;

    /** Past this many Java files the type index stops being an aid and starts being a liability. */
    private static final int MAX_TYPE_FILES = 100_000;

    /** {@code extends} chains followed before the binder gives up and leaves the occurrence lexical. */
    private static final int MAX_SUPERCLASS_DEPTH = 3;

    /** The same junk the text search skips; a type index over build output is noise squared. */
    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of(".git", "node_modules", "build", ".gradle", "out", "target");

    /** One found declaration: the member's file and 1-based line. */
    public record Declaration(Path relativePath, int line) {
    }

    /**
     * The lines of one file whose occurrences bound, and where each bound
     * to -- plus every declaration the file's references resolved to, with
     * votes, so a caller whose candidate bound nothing can see whether the
     * references agree on a different one.
     */
    public record Binding(Map<Integer, Declaration> boundLines, Map<Declaration, Integer> votes) {

        static final Binding NONE = new Binding(Map.of(), Map.of());

        public boolean isEmpty() {
            return boundLines.isEmpty();
        }

        /** The declaration this file's references agree on, when they agree on exactly one. */
        public Optional<Declaration> unanimousDeclaration() {
            return votes.size() == 1 ? Optional.of(votes.keySet().iterator().next()) : Optional.empty();
        }
    }

    private final Path root;
    /** Simple class name (lowercased) → repo files that could declare it. Built once, lazily. */
    private Map<String, List<Path>> typeFiles;
    /** Parsed file models: a peek query and each of its receiver types parses once. */
    private final Map<Path, Optional<FileModel>> models = new HashMap<>();

    public ScopeBinder(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * Binds {@code symbol}'s occurrences in the repo file {@code relative}
     * that resolve to the declaration the reader is looking at -- a
     * reference to a <em>different</em> same-named member is exactly the
     * false link this tier exists to refuse, so it binds nothing. Empty
     * when nothing binds: a valid answer, not a failure; every unbound
     * occurrence keeps its lexical-tier warrant.
     */
    public Binding bind(String symbol, Optional<Declaration> queried, Path relative) {
        return model(root.resolve(relative))
                .map(file -> bind(file, symbol, queried))
                .orElse(Binding.NONE);
    }

    private Binding bind(FileModel file, String symbol, Optional<Declaration> queried) {
        Map<Integer, Declaration> bound = new LinkedHashMap<>();
        Map<Declaration, Integer> votes = new LinkedHashMap<>();
        for (TreeWalker.Reference ref : file.references) {
            if (!ref.name().equals(symbol)) {
                continue;
            }
            Optional<Declaration> resolved = memberOf(file, ref.receiverType(), symbol,
                    new LinkedHashSet<>())
                    .or(() -> unqualifiedMember(file, ref, symbol));
            resolved.ifPresent(declaration -> votes.merge(declaration, 1, Integer::sum));
            if (resolved.isPresent() && queried.isPresent()
                    && resolved.get().equals(queried.get())) {
                bound.put(ref.line(), resolved.get());
            }
        }
        return new Binding(bound, votes);
    }

    /**
     * Where {@code symbol} is declared for a receiver of type {@code
     * typeName}: that type's own declaration, then its {@code extends}
     * chain, each step resolved from the file that declares the current
     * type. Empty when the type is unresolvable (a library, {@code
     * java.lang}, a guess the file never made) or declares nothing of the
     * name -- both honest, both leaving the occurrence lexical-tier.
     */
    private Optional<Declaration> memberOf(FileModel from, Optional<String> typeName,
                                          String symbol, Set<String> seen) {
        if (typeName.isEmpty() || seen.size() >= MAX_SUPERCLASS_DEPTH) {
            return Optional.empty();
        }
        String type = typeName.orElseThrow();
        if (!seen.add(type)) {
            return Optional.empty();
        }
        Optional<FileModel> target = from.classes.containsKey(type)
                ? Optional.of(from)
                : resolveType(type, from);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        ClassModel cls = target.orElseThrow().classes.get(type);
        if (cls == null) {
            // The candidate file shares the package but declares no such
            // class: a wrong file the index cannot distinguish.
            return Optional.empty();
        }
        Integer line = cls.members.get(symbol);
        if (line != null) {
            return Optional.of(new Declaration(target.orElseThrow().relative, line));
        }
        return memberOf(target.orElseThrow(),
                cls.superclass.isEmpty() ? Optional.empty() : Optional.of(cls.superclass),
                symbol, seen);
    }

    /**
     * A bare or {@code this} receiver: any class enclosing the occurrence
     * in this file may own the member -- or reach it through its own
     * {@code extends} chain, because a bare inherited call (the most
     * common kind in a codebase with base classes) is a real reference
     * too. Each enclosing class goes innermost first; the chain walk is
     * {@link #memberOf}'s, capped and cycle-guarded the same way.
     */
    private Optional<Declaration> unqualifiedMember(FileModel file, TreeWalker.Reference ref, String symbol) {
        if (!ref.unqualified()) {
            return Optional.empty();
        }
        for (int i = ref.enclosingClasses().size() - 1; i >= 0; i--) {
            Optional<Declaration> found = memberOf(file,
                    Optional.of(ref.enclosingClasses().get(i)), symbol, new LinkedHashSet<>());
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** {@code type}'s declared member {@code symbol} -- that file only, no inheritance. */
    private Optional<Declaration> enclosingMember(FileModel file, String type, String symbol) {
        ClassModel model = file.classes.get(type);
        if (model == null) {
            return Optional.empty();
        }
        Integer line = model.members.get(symbol);
        return line == null ? Optional.empty()
                : Optional.of(new Declaration(file.relative, line));
    }

    /**
     * Resolves simple type name {@code type} as seen from {@code from}: a
     * same-file class, then an exact (or static) import, then a wildcard
     * import, then the same package. Ambiguity resolves to nothing --
     * picking one candidate would be a guess with a binding's authority.
     */
    private Optional<FileModel> resolveType(String type, FileModel from) {
        if (from.classes.containsKey(type)) {
            return Optional.of(from);
        }
        List<Path> candidates = typeFiles().getOrDefault(type.toLowerCase(Locale.ROOT), List.of());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Optional<FileModel> exact = byPackage(candidates, from.imports.stream()
                .filter(imported -> imported.endsWith("." + type))
                .map(imported -> imported.substring(0, imported.length() - type.length() - 1))
                .findFirst());
        if (exact.isPresent()) {
            return exact;
        }
        for (String wildcard : from.wildcards) {
            Optional<FileModel> found = byPackage(candidates, Optional.of(wildcard));
            if (found.isPresent()) {
                return found;
            }
        }
        return byPackage(candidates, Optional.of(from.packageName));
    }

    private Optional<FileModel> byPackage(List<Path> candidates, Optional<String> packageName) {
        if (packageName.isEmpty()) {
            return Optional.empty();
        }
        String wanted = packageName.orElseThrow();
        List<FileModel> matches = new ArrayList<>();
        for (Path path : candidates) {
            model(path).filter(m -> m.packageName.equals(wanted)).ifPresent(matches::add);
        }
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    // ---- the parsed file -----------------------------------------------------

    /** One class declaration: members (name → declaration line), field types, and its superclass name. */
    private static final class ClassModel {
        final String superclass;
        final Map<String, Integer> members = new LinkedHashMap<>();
        final Map<String, String> fieldTypes = new LinkedHashMap<>();

        ClassModel(String superclass) {
            this.superclass = superclass;
        }
    }

    /** Everything the binder needs from one parsed file. */
    private static final class FileModel {
        final Path relative;
        final byte[] source;
        final String packageName;
        final List<String> imports = new ArrayList<>();
        final List<String> wildcards = new ArrayList<>();
        /** Every class declared in the file, top-level or nested, by simple name. */
        final Map<String, ClassModel> classes = new LinkedHashMap<>();
        final String topLevelClass;
        final List<TreeWalker.Reference> references = new ArrayList<>();

        FileModel(Path relative, byte[] source, String packageName, String topLevelClass) {
            this.relative = relative;
            this.source = source;
            this.packageName = packageName;
            this.topLevelClass = topLevelClass;
        }

        String text(TSNode node) {
            int from = node.getStartByte();
            int to = node.getEndByte();
            return from < 0 || to > source.length || from > to ? ""
                    : new String(source, from, to - from, StandardCharsets.UTF_8);
        }
    }

    private Optional<FileModel> model(Path path) {
        Optional<FileModel> cached = models.get(path);
        if (cached != null) {
            return cached;
        }
        Optional<FileModel> parsed = parseModel(path);
        models.put(path, parsed);
        return parsed;
    }

    private Optional<FileModel> parseModel(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            if (Files.size(path) > MAX_FILE_BYTES) {
                return Optional.empty();
            }
            byte[] source = Files.readAllBytes(path);
            Optional<TSLanguage> grammar = GrammarRegistry.forPath(path.toString());
            if (grammar.isEmpty()) {
                return Optional.empty();
            }
            TSParser parser = new TSParser();
            parser.setLanguage(grammar.get());
            TSTree tree;
            try {
                tree = parser.parseString(null, new String(source, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                // A file the grammar cannot even tokenise is not an error
                // worth surfacing: it binds nothing, the lexical answer
                // stands.
                return Optional.empty();
            }
            return Optional.of(buildModel(tree.getRootNode(), root.relativize(path), source, path));
        } catch (IOException | UncheckedIOException e) {
            return Optional.empty();
        }
    }

    private static FileModel buildModel(TSNode root, Path relative, byte[] source, Path path) {
        TreeWalker walker = walkerFor(path, source);
        if (walker == null) {
            return new FileModel(relative, source, "", "");
        }
        walker.collect(root);
        // The top-level class: the file's name-bearing declaration whose
        // parent is the file node. Both grammars spell the file node
        // differently ("program" / "source_file"); neither matters beyond
        // this test.
        String topLevel = walker.classNodes.stream()
                .filter(node -> node.getParent() != null
                        && (node.getParent().getType().equals("program")
                        || node.getParent().getType().equals("source_file")))
                .map(node -> topTypeName(walker, node))
                .findFirst().orElse("");
        FileModel model = new FileModel(relative, source, walker.packageName, topLevel);
        for (Map.Entry<String, TreeWalker.ClassDraft> entry : walker.classes.entrySet()) {
            TreeWalker.ClassDraft draft = entry.getValue();
            ClassModel classModel = new ClassModel(draft.superclass);
            classModel.members.putAll(draft.members);
            classModel.fieldTypes.putAll(draft.fieldTypes);
            model.classes.put(entry.getKey(), classModel);
        }
        model.imports.addAll(walker.imports);
        model.wildcards.addAll(walker.wildcards);
        model.references.addAll(walker.references);
        return model;
    }

    private static String topTypeName(TreeWalker walker, TSNode node) {
        if (node.getType().equals("class_declaration") || node.getType().equals("interface_declaration")
                || node.getType().equals("enum_declaration") || node.getType().equals("record_declaration")) {
            TSNode name = walker.field(node, "name");
            return name == null ? "" : walker.text(name);
        }
        TSNode name = walker.childOfType(node, "type_identifier");
        return name == null ? "" : walker.text(name);
    }

    /** The per-language walk; null for a path whose language has no walker -- it binds nothing, honestly. */
    private static TreeWalker walkerFor(Path path, byte[] source) {
        String name = path.getFileName().toString();
        if (name.endsWith(".java")) {
            return new JavaTreeWalker(source);
        }
        if (name.endsWith(".kt") || name.endsWith(".kts")) {
            return new KotlinTreeWalker(source);
        }
        return null;
    }

    // ---- the type index ------------------------------------------------------

    /** The class-name stem a file could declare: {@code Util} for Util.java/Util.kt, else null. */
    private static String stem(String fileName) {
        if (fileName.endsWith(".java")) {
            return fileName.substring(0, fileName.length() - 5);
        }
        if (fileName.endsWith(".kt")) {
            return fileName.substring(0, fileName.length() - 3);
        }
        if (fileName.endsWith(".kts")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return null;
    }

    private synchronized Map<String, List<Path>> typeFiles() {
        if (typeFiles == null) {
            Map<String, List<Path>> index = new HashMap<>();
            int[] count = {0};
            try {
                Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
                    @Override
                    public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                            java.nio.file.attribute.BasicFileAttributes attrs) {
                        return SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())
                                ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                                : java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult visitFile(Path file,
                            java.nio.file.attribute.BasicFileAttributes attrs) {
                        String name = file.getFileName().toString();
                        String stem = stem(name);
                        if (stem != null && count[0]++ < MAX_TYPE_FILES) {
                            index.computeIfAbsent(stem.toLowerCase(Locale.ROOT),
                                    key -> new ArrayList<>()).add(file);
                        }
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                typeFiles = Map.of();
                return typeFiles;
            }
            typeFiles = count[0] > MAX_TYPE_FILES ? Map.of() : index;
        }
        return typeFiles;
    }
}
