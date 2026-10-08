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
 * Binds a symbol's occurrences in one Java file to real references by
 * reading the parse tree -- the tier between name matching and a language
 * server (spec §6, the {@code UsageProvider} seam).
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
public final class JavaScopeBinder {

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

    public JavaScopeBinder(Path root) {
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
        for (Reference ref : file.references) {
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
    private Optional<Declaration> unqualifiedMember(FileModel file, Reference ref, String symbol) {
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

    /**
     * One reference-shaped occurrence: the used name, the receiver's
     * derivable type (empty when the receiver is an expression whose type
     * the file does not state), the classes enclosing the occurrence in
     * its own file, and whether the receiver was bare or {@code this}.
     */
    private record Reference(String name, Optional<String> receiverType, List<String> enclosingClasses,
                             boolean unqualified, int line) {
    }

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
        final List<Reference> references = new ArrayList<>();

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
            return Optional.of(buildModel(tree.getRootNode(), root.relativize(path), source));
        } catch (IOException | UncheckedIOException e) {
            return Optional.empty();
        }
    }

    private static FileModel buildModel(TSNode root, Path relative, byte[] source) {
        Walker walker = new Walker(source);
        walker.collect(root, List.of(), Map.of());
        String topLevel = walker.classNodes.stream()
                .filter(node -> node.getParent() != null && node.getParent().getType().equals("program"))
                .map(node -> walker.text(walker.field(node, "name")))
                .findFirst().orElse("");
        FileModel model = new FileModel(relative, source, walker.packageName, topLevel);
        for (Map.Entry<String, Walker.ClassDraft> entry : walker.classes.entrySet()) {
            Walker.ClassDraft draft = entry.getValue();
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

    /**
     * One pass over the tree: classes with their members and field types,
     * method scopes with their parameters and locals, and every reference
     * shape ({@code method_invocation} name, {@code field_access} field)
     * with its receiver's derivable type resolved against the scopes and
     * the file's own class names.
     */
    private static final class Walker {
        private final byte[] source;
        final Map<String, ClassDraft> classes = new LinkedHashMap<>();
        final List<TSNode> classNodes = new ArrayList<>();
        final List<String> imports = new ArrayList<>();
        final List<String> wildcards = new ArrayList<>();
        final List<Reference> references = new ArrayList<>();
        String packageName = "";

        Walker(byte[] source) {
            this.source = source;
        }

        String text(TSNode node) {
            if (node == null || node.isNull()) {
                return "";
            }
            int from = node.getStartByte();
            int to = node.getEndByte();
            return from < 0 || to > source.length || from > to ? ""
                    : new String(source, from, to - from, StandardCharsets.UTF_8);
        }

        /**
         * The named field's child, or null: the binding hands out NULL
         * NODES (not Java nulls) for absent fields, and any use of one
         * throws -- every access goes through here.
         */
        TSNode field(TSNode node, String fieldName) {
            if (node == null || node.isNull()) {
                return null;
            }
            TSNode child = node.getChildByFieldName(fieldName);
            return child == null || child.isNull() ? null : child;
        }

        static final class ClassDraft {
            final String superclass;
            final Map<String, Integer> members = new LinkedHashMap<>();
            final Map<String, String> fieldTypes = new LinkedHashMap<>();

            ClassDraft(String superclass) {
                this.superclass = superclass == null ? "" : superclass;
            }
        }

        void collect(TSNode node, List<String> enclosingClasses, Map<String, String> scopeVars) {
            String type = node.getType();
            switch (type) {
                case "program" -> {
                    collectImports(node);
                }
                case "class_declaration", "interface_declaration", "enum_declaration",
                        "record_declaration" -> {
                    String name = text(field(node, "name"));
                    ClassDraft draft = new ClassDraft(superclassOf(node));
                    collectMembers(field(node, "body"), draft);
                    classes.put(name, draft);
                    classNodes.add(node);
                    List<String> next = append(enclosingClasses, name);
                    for (int i = 0; i < node.getChildCount(); i++) {
                        collect(node.getChild(i), next, withFieldTypes(scopeVars, draft));
                    }
                    return;
                }
                case "method_declaration", "constructor_declaration" -> {
                    // The method's scope: every parameter and local
                    // declared anywhere in it, layered over the enclosing
                    // classes' fields. Whole-method visibility, not
                    // declaration-order -- a use above the declaration line
                    // does not compile, so this cannot over-bind.
                    Map<String, String> inner = new LinkedHashMap<>(scopeVars);
                    collectScopeVariables(node, inner);
                    for (int i = 0; i < node.getChildCount(); i++) {
                        collect(node.getChild(i), enclosingClasses, inner);
                    }
                    return;
                }
                case "method_invocation", "field_access" -> {
                    recordReference(node, enclosingClasses, scopeVars);
                }
                default -> {
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                collect(node.getChild(i), enclosingClasses, scopeVars);
            }
        }

        /**
         * The {@code extends} clause's type, or null: the superclass node
         * spans the {@code extends} keyword too, so the type is its child
         * ({@code type} field, falling back to the first type identifier).
         */
        private String superclassOf(TSNode classNode) {
            TSNode superclass = field(classNode, "superclass");
            if (superclass == null || superclass.isNull()) {
                return null;
            }
            TSNode type = field(superclass, "type");
            if (type != null && !type.isNull()) {
                return simpleTypeName(type);
            }
            for (int i = 0; i < superclass.getChildCount(); i++) {
                TSNode child = superclass.getChild(i);
                if (child.getType().equals("type_identifier")) {
                    return text(child);
                }
            }
            return null;
        }

        /** Parameters and locals of one method-shaped node, into {@code out}. */
        private void collectScopeVariables(TSNode node, Map<String, String> out) {
            for (int i = 0; i < node.getChildCount(); i++) {
                TSNode child = node.getChild(i);
                switch (child.getType()) {
                    case "formal_parameter" -> putTyped(child, out);
                    case "local_variable_declaration" -> putTyped(child, out);
                    default -> collectScopeVariables(child, out);
                }
            }
        }

        private void putTyped(TSNode node, Map<String, String> out) {
            TSNode typeNode = field(node, "type");
            // Two shapes carry a name: a formal_parameter names its
            // identifier directly, a local_variable_declaration wraps it
            // in a variable_declarator. Either way the name is the thing
            // being declared, with a type beside it.
            TSNode name = field(node, "name");
            if (name == null) {
                TSNode declarator = field(node, "declarator");
                name = declarator == null ? null : field(declarator, "name");
            }
            if (typeNode != null && name != null) {
                out.putIfAbsent(text(name), simpleTypeName(typeNode));
            }
        }

        private void collectMembers(TSNode body, ClassDraft draft) {
            if (body == null || body.isNull()) {
                return;
            }
            for (int i = 0; i < body.getChildCount(); i++) {
                TSNode child = body.getChild(i);
                switch (child.getType()) {
                    case "method_declaration", "constructor_declaration" -> {
                        TSNode name = field(child, "name");
                        if (name != null) {
                            draft.members.putIfAbsent(text(name), child.getStartPoint().getRow() + 1);
                        }
                    }
                    case "field_declaration" -> {
                        TSNode typeNode = field(child, "type");
                        TSNode declarator = field(child, "declarator");
                        if (declarator == null) {
                            break;
                        }
                        TSNode name = field(declarator, "name");
                        if (name != null) {
                            draft.members.putIfAbsent(text(name), child.getStartPoint().getRow() + 1);
                            if (typeNode != null) {
                                draft.fieldTypes.putIfAbsent(text(name), simpleTypeName(typeNode));
                            }
                        }
                    }
                    case "enum_declaration", "record_declaration" -> {
                        TSNode name = field(child, "name");
                        if (name != null) {
                            draft.members.putIfAbsent(text(name), child.getStartPoint().getRow() + 1);
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        private void recordReference(TSNode node, List<String> enclosingClasses,
                                     Map<String, String> scopeVars) {
            String nameField = node.getType().equals("method_invocation") ? "name" : "field";
            TSNode name = node.getChildByFieldName(nameField);
            if (name == null || !name.getType().equals("identifier")) {
                return;
            }
            TSNode object = field(node, "object");
            boolean bare = object == null || text(object).equals("this");
            references.add(new Reference(text(name), bare ? Optional.empty()
                    : receiverType(object, enclosingClasses, scopeVars),
                    List.copyOf(enclosingClasses), bare,
                    node.getStartPoint().getRow() + 1));
        }

        /**
         * The receiver's statically derivable type: a name that is a class
         * of this file, else a parameter/local/field whose declared type
         * the scopes name, else (for {@code Outer.Inner.member}) the last
         * segment of a qualified name, treated as a type candidate the
         * binder will confirm or refuse. Empty when the receiver is an
         * expression whose type the file never states.
         */
        private Optional<String> receiverType(TSNode object, List<String> enclosingClasses,
                                              Map<String, String> scopeVars) {
            if (object == null) {
                return Optional.empty();
            }
            if (object.getType().equals("identifier")) {
                String name = text(object);
                if (isClassOfThisFile(name)) {
                    return Optional.of(name);
                }
                String typed = lookupVar(name, enclosingClasses, scopeVars);
                if (typed != null) {
                    return Optional.of(typed);
                }
                // Not a variable in scope: the last possibility is a class
                // name (Util.clamp(), a static call through an import).
                // Passed through as a candidate, not an answer: the member
                // lookup confirms it by finding a class of exactly this
                // name that declares the member -- or refuses, and the
                // occurrence stays lexical.
                return Optional.of(name);
            }
            if (object.getType().equals("field_access")) {
                TSNode field = field(object, "field");
                return field == null ? Optional.empty() : Optional.of(text(field));
            }
            return Optional.empty();
        }

        private String lookupVar(String name, List<String> enclosingClasses,
                                 Map<String, String> scopeVars) {
            String inScope = scopeVars.get(name);
            if (inScope != null) {
                return inScope;
            }
            for (int i = enclosingClasses.size() - 1; i >= 0; i--) {
                ClassDraft draft = classes.get(enclosingClasses.get(i));
                if (draft != null) {
                    String fieldType = draft.fieldTypes.get(name);
                    if (fieldType != null) {
                        return fieldType;
                    }
                }
            }
            return null;
        }

        private boolean isClassOfThisFile(String name) {
            return classes.containsKey(name);
        }

        private void collectImports(TSNode program) {
            for (int i = 0; i < program.getChildCount(); i++) {
                TSNode child = program.getChild(i);
                if (child.getType().equals("package_declaration")) {
                    // No field name on the package's identifier (verified:
                    // neither the single- nor the multi-segment form carries
                    // one), so the name is the named child that is not the
                    // keyword -- a scoped_identifier for dotted packages, a
                    // plain identifier for single-segment ones. Both spell
                    // the name contiguously in the source bytes.
                    for (int j = 0; j < child.getChildCount(); j++) {
                        TSNode candidate = child.getChild(j);
                        if (candidate.isNamed()
                                && (candidate.getType().equals("scoped_identifier")
                                || candidate.getType().equals("identifier"))) {
                            packageName = text(candidate);
                            break;
                        }
                    }
                    continue;
                }
                if (!child.getType().equals("import_declaration")) {
                    continue;
                }
                String imported = text(child).strip();
                for (String prefix : new String[]{"import ", "import static "}) {
                    if (imported.startsWith(prefix)) {
                        imported = imported.substring(prefix.length());
                        break;
                    }
                }
                imported = imported.replace(";", "").strip();
                if (imported.endsWith(".*")) {
                    wildcards.add(imported.substring(0, imported.length() - 2));
                } else if (imported.lastIndexOf('.') > 0) {
                    // A static import names a member; the type its receiver
                    // could reference is the segment above it. Detected from
                    // the tree (the raw text lost the keyword), same rule the
                    // grammar's own static_import node gives.
                    if (child.getChild(0).getType().equals("static_import")) {
                        imported = imported.substring(0, imported.lastIndexOf('.'));
                    }
                    imports.add(imported);
                }
            }
        }

        /** {@code List<Foo>} → {@code List}, {@code a.b.C} → {@code C}: the simple name a receiver could match. */
        private String simpleTypeName(TSNode typeNode) {
            String text = text(typeNode).strip();
            int generic = text.indexOf('<');
            if (generic > 0) {
                text = text.substring(0, generic);
            }
            int dot = text.lastIndexOf('.');
            if (dot >= 0 && dot < text.length() - 1) {
                text = text.substring(dot + 1);
            }
            return text.strip();
        }

    }

    private static List<String> append(List<String> list, String name) {
        List<String> next = new ArrayList<>(list);
        next.add(name);
        return next;
    }

    private static Map<String, String> withFieldTypes(Map<String, String> scopeVars, Walker.ClassDraft draft) {
        Map<String, String> next = new LinkedHashMap<>(scopeVars);
        next.putAll(draft.fieldTypes);
        return next;
    }

    // ---- the type index ------------------------------------------------------

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
                        if (name.endsWith(".java") && count[0]++ < MAX_TYPE_FILES) {
                            index.computeIfAbsent(name.substring(0, name.length() - 5)
                                    .toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(file);
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
