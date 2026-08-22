package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * One file's symbols: what it declares, what it uses, and whether each sits
 * on a changed line (spec §4.2).
 *
 * <p>Two front ends behind one shape. With a grammar, declarations come from
 * the parse tree. Without one, every occurrence is a <em>use</em> and the
 * file declares nothing -- a lexical scan cannot tell a declaration from a
 * call without guessing, and a wrong declaration would mint wrong edges
 * everywhere the name appears. A file that is not plausibly code at all
 * (see {@link #NOT_CODE}) takes neither front end and contributes
 * nothing.</p>
 *
 * <p><strong>A hunk, not a line, is the parsing unit.</strong> The first
 * design parsed one diff line at a time, on the reasoning that a diff line
 * is not a compilation unit. It is worse than that: for anything whose body
 * spans lines -- which is every real C++ class -- the opening line alone is
 * an incomplete construct, and tree-sitter reports no name for it. Measured
 * on the case this feature exists to serve, a five-line
 * {@code class JmpCtxScope} declared {@code arm} and {@code disarm} and lost
 * {@code JmpCtxScope} entirely. A hunk is a contiguous region of one file,
 * so joining its lines is far likelier to parse as real syntax, and it is
 * one parse per hunk rather than per line.</p>
 *
 * <p><strong>A name inside a comment is not a use.</strong> This falls out
 * of parsing a hunk rather than a line, but it is a policy and not an
 * accident, so it is stated here. A whole {@code //} line always parsed as
 * a comment; what did NOT was an INTERIOR line of a block comment, which
 * is most of the documentation in this codebase. Read alone,
 * {@code  * ranks &#123;@code BaseMove&#125; above &#123;@link
 * HunkDigest&#125;} is not a comment -- it is an asterisk and some
 * identifiers -- so line-at-a-time lexed the doc words as names and minted
 * real reference edges from them. Measured on this branch's own diff that
 * was 108 of 337 edges, more than the non-code denylist removes. With the
 * hunk in hand the grammar sees one comment node and yields nothing from
 * it.</p>
 *
 * <p>The new behaviour is the right one: prose that NAMES a thing is not
 * code that DEPENDS on it, and nobody ever decided that a
 * {@code &#123;@link&#125;} should couple two files in the review rail.
 * Guarded by {@code aNameThatAppearsOnlyInACommentIsNotAUse}, because a
 * {@link #walk} change or a grammar bump that starts descending into
 * comments again would otherwise restore those 108 edges with no signal at
 * all.</p>
 *
 * <p>Blocking: parsing -- and, the first time any language is used, loading
 * its native grammar library via {@link GrammarRegistry} -- both do real
 * work (native calls, disk I/O). Never call {@link #of} on the FX thread.</p>
 */
public final class SymbolScan {

    /** One symbol occurrence. */
    public record Symbol(String name, String path, boolean declaration, boolean onChangedLine) {
    }

    /**
     * tree-sitter node types that introduce a name, across the shipped
     * grammars. Verified against the real grammars (not assumed): parsed
     * representative snippets for every shipped language and read the
     * S-expression tree-sitter itself printed, plus the field name each
     * child carries. That surfaced two gaps the original sketch of this
     * list did not cover -- {@code type_spec} (Go's {@code struct}/{@code
     * interface}/type-alias name lives one level under {@code
     * type_declaration}, which is otherwise a dead end) and the four
     * enum-member containers ({@code enum_constant} for Java, {@code
     * enumerator} for C/C++, {@code enum_variant} for Rust, {@code
     * enum_entry} for Kotlin) -- both added here rather than left silently
     * unhandled.
     */
    private static final List<String> DECLARATION_NODES = List.of(
            "class_declaration", "interface_declaration", "record_declaration",
            "enum_declaration", "method_declaration", "constructor_declaration",
            "function_definition", "function_declarator", "function_declaration",
            "struct_specifier", "class_specifier", "enum_specifier", "type_definition",
            "field_declaration", "function_item", "struct_item", "enum_item", "impl_item",
            "class_definition", "type_alias_declaration", "object_declaration",
            "type_spec", "enum_constant", "enumerator", "enum_variant", "enum_entry");

    /**
     * Node types tree-sitter uses for a bare name, across the shipped
     * grammars. {@code simple_identifier} is Kotlin's spelling -- without it
     * every Kotlin name (declared or used) is invisible to this scan, since
     * Kotlin never emits plain {@code identifier} nodes.
     *
     * <p>{@code namespace_identifier} is the qualifier of a C/C++ {@code
     * A::b}, and it is here so that a qualified name references what
     * qualifies it: {@code void JmpCtxScope::arm() &#123;&#125;} in a
     * {@code .cpp} otherwise names nothing its own header declares, and the
     * pair never links by symbol. The equivalent shapes in the other shipped
     * languages need no entry -- confirmed by dumping their trees, Java's
     * {@code Foo.bar()}, Rust's {@code Foo::bar} and Go's {@code pkg.Sym}
     * all spell the qualifier with a plain {@code identifier}, which is
     * already listed.</p>
     */
    private static final List<String> NAME_NODES = List.of(
            "identifier", "type_identifier", "field_identifier", "simple_identifier",
            "namespace_identifier");

    /**
     * Child field names that mark an identifier as the thing being
     * declared, as opposed to a type reference, a result type or a
     * parameter. Java/Kotlin-family and Go/Rust/Python/JS/TS grammars name
     * it {@code name}; C/C++ name it {@code declarator} (a C declarator can
     * itself be a nested {@link #DECLARATION_NODES} entry, e.g. {@code
     * function_definition}'s {@code declarator} is a {@code
     * function_declarator}, whose own {@code declarator} field is the
     * identifier -- the recursion in {@link #walk} unwinds that correctly).
     */
    private static final Set<String> NAME_FIELDS = Set.of("name", "declarator");

    /**
     * Extensions whose files are not code, and so contribute nothing --
     * not even uses.
     *
     * <p>"A file with no grammar contributes uses only" was written for an
     * unsupported <em>language</em>, not for prose. Measured on this
     * branch's own 54-file diff, 51 of 337 reference edges (15%) originated
     * in files that are not code at all: a design document quoting Java in
     * fenced blocks minted an edge to each of seventeen changed classes, and
     * {@code app.css} reached {@code Sections.java} through a style class
     * name. Those edges are not wrong about the text; they are wrong about
     * the code, and they merge sections that have no structural relation.</p>
     *
     * <p>An explicit denylist rather than a cleverer test. Anything that
     * tried to infer "is this code" would be a guess whose failures are
     * invisible, and the honest cost of a denylist is stated rather than
     * hidden: it lets through every extension nobody listed, and every file
     * with no extension at all -- {@code Makefile} and {@code gradlew} are
     * code and should pass, {@code LICENSE} and {@code CODEOWNERS} are not
     * and still contribute uses. Those are prose without identifiers, so
     * they mint few edges; a document full of code blocks is the case that
     * actually mattered, and it has an extension.</p>
     */
    private static final Set<String> NOT_CODE = Set.of(
            "md", "markdown", "txt", "rst", "adoc", "org",
            "css", "scss", "sass", "less",
            "json", "yaml", "yml", "toml", "ini", "cfg", "conf", "lock",
            "html", "htm", "xml", "xsd", "dtd", "svg",
            "png", "jpg", "jpeg", "gif", "ico", "webp", "pdf",
            "properties", "csv", "tsv", "patch", "diff", "log");

    private SymbolScan() {
    }

    /** {@code file}'s symbols, in reading order within each hunk. */
    public static List<Symbol> of(UnifiedDiff.FileDiff file) {
        if (!plausiblyCode(file.path())) {
            return List.of();
        }
        Optional<TSLanguage> grammar = GrammarRegistry.forPath(file.path());
        List<Symbol> symbols = new ArrayList<>();
        for (UnifiedDiff.Hunk hunk : file.hunks()) {
            if (grammar.isPresent()) {
                // The new state first (context + additions), then the old
                // one, so the output is stable and a context line is
                // reported exactly once.
                scanView(grammar.get(), file.path(), hunk, UnifiedDiff.Line.Kind.ADD, true,
                        symbols);
                scanView(grammar.get(), file.path(), hunk, UnifiedDiff.Line.Kind.DEL, false,
                        symbols);
            } else {
                for (UnifiedDiff.Line line : hunk.lines()) {
                    lexical(symbols, file.path(), line.text(), isChanged(line));
                }
            }
        }
        return List.copyOf(symbols);
    }

    /**
     * Whether {@code path} is worth scanning at all. Extension-only, matched
     * the way {@link GrammarRegistry} matches: the last dot after the last
     * slash, lowercased.
     */
    private static boolean plausiblyCode(String path) {
        if (path == null || path.endsWith("/")) {
            return false;
        }
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot < 0 || dot < slash || dot == path.length() - 1) {
            return true;
        }
        return !NOT_CODE.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * Scans one state of {@code hunk} into {@code out}.
     *
     * <p>A hunk interleaves ADD, DEL and CONTEXT lines, and joining all
     * three produces a fragment that is not valid source in either state --
     * a deleted {@code if} and the added one replacing it, both present,
     * with two bodies and one closing brace. So each state is parsed on its
     * own: the new state is CONTEXT + ADD, the old state is CONTEXT + DEL,
     * and each is a coherent view of one file.</p>
     *
     * <p>Context lines appear in both views, so exactly one view reports
     * them: {@code reportContext} is true for the new state and false for
     * the old, which reports only its DEL lines. Every source line is
     * therefore scanned once, as it was when this was line-at-a-time, and a
     * DEL line is still read in the surrounding syntax it was deleted from
     * rather than in isolation. A hunk with no deletions -- the common case
     * -- parses once.</p>
     *
     * <p>A fresh {@link TSParser} (and the {@link TSTree} it returns) per
     * view is deliberate, not a leak: the binding exposes no public {@code
     * close()}/{@code delete()} on either type -- decompiling {@code
     * TSParser}'s and {@code TSTree}'s constructors shows each registers a
     * {@code java.lang.ref.Cleaner} action that calls the native {@code
     * ts_*_delete} when the object becomes unreachable. There is nothing a
     * manual call could free that the Cleaner does not already own.</p>
     */
    private static void scanView(TSLanguage language, String path, UnifiedDiff.Hunk hunk,
                                 UnifiedDiff.Line.Kind changedKind, boolean reportContext,
                                 List<Symbol> out) {
        List<UnifiedDiff.Line> lines = new ArrayList<>();
        boolean anyReported = false;
        for (UnifiedDiff.Line line : hunk.lines()) {
            if (line.kind() != UnifiedDiff.Line.Kind.CONTEXT && line.kind() != changedKind) {
                continue;
            }
            lines.add(line);
            anyReported |= reportContext || line.kind() == changedKind;
        }
        if (!anyReported) {
            return;
        }
        Fragment fragment = Fragment.of(lines, reportContext);
        TSTree tree;
        try {
            TSParser parser = new TSParser();
            parser.setLanguage(language);
            tree = parser.parseString(null, fragment.text());
        } catch (RuntimeException e) {
            // A fragment the grammar cannot even tokenise (verified: a lone
            // unpaired UTF-16 surrogate throws "Invalid UTF-8 source input"
            // from the native layer) is not a reason to lose the file --
            // fall back to the same lexical scan an ungrammared file gets,
            // for the lines this view is responsible for.
            //
            // Scoped to just the native-facing calls: catching a wider block
            // here would let a bug in walk() -- our own Java, not the
            // grammar -- disappear into this same "expected fallback" path
            // with no log and no test signal. Absent and broken must not
            // look the same.
            for (int index = 0; index < lines.size(); index++) {
                if (fragment.reports(index)) {
                    lexical(out, path, lines.get(index).text(), fragment.changed(index));
                }
            }
            return;
        }
        walk(tree.getRootNode(), fragment, path, out);
    }

    /**
     * One parsed view of a hunk: the joined source, its UTF-8 bytes, and
     * where each line begins in them.
     *
     * <p>The byte offsets are what makes per-hunk parsing keep the
     * per-symbol answer the line-at-a-time version gave for free. {@link
     * TSNode#getStartByte()} is a UTF-8 BYTE offset (confirmed: a line with
     * two-byte characters before an identifier has a byte length longer than
     * its char length, and the identifier's own node range is the byte span,
     * not the char span), so {@code lineStart} is measured in bytes too --
     * counting characters would drift by one per non-ASCII byte and
     * attribute a symbol to the wrong line, or slice a name in half.</p>
     *
     * <p>{@code text} and {@code utf8} are the same content twice on
     * purpose: the parser takes a {@code String} and answers in bytes, and
     * re-encoding per symbol would be the same work done once per name
     * instead of once per hunk. Purely internal -- the array components mean
     * the generated {@code equals} is identity-based, and nothing compares
     * two of these.</p>
     */
    private record Fragment(String text, byte[] utf8, int[] lineStart,
                            boolean[] reportedLines, boolean[] changedLines) {

        static Fragment of(List<UnifiedDiff.Line> lines, boolean reportContext) {
            StringBuilder joined = new StringBuilder();
            int[] lineStart = new int[lines.size()];
            boolean[] reported = new boolean[lines.size()];
            boolean[] changed = new boolean[lines.size()];
            int offset = 0;
            for (int index = 0; index < lines.size(); index++) {
                UnifiedDiff.Line line = lines.get(index);
                lineStart[index] = offset;
                reported[index] = reportContext || isChanged(line);
                changed[index] = isChanged(line);
                joined.append(line.text()).append('\n');
                offset += line.text().getBytes(StandardCharsets.UTF_8).length + 1;
            }
            String text = joined.toString();
            return new Fragment(text, text.getBytes(StandardCharsets.UTF_8), lineStart,
                    reported, changed);
        }

        /**
         * The line {@code byteOffset} falls in. {@code lineStart} is
         * strictly increasing (every line contributes at least its
         * newline), so the binary search's insertion point is one past the
         * containing line.
         */
        int lineAt(int byteOffset) {
            int found = Arrays.binarySearch(lineStart, byteOffset);
            int index = found >= 0 ? found : -found - 2;
            return Math.min(Math.max(index, 0), lineStart.length - 1);
        }

        /** Whether this view is the one that reports line {@code index}. */
        boolean reports(int index) {
            return reportedLines[index];
        }

        boolean changed(int index) {
            return changedLines[index];
        }
    }

    private static boolean isChanged(UnifiedDiff.Line line) {
        return line.kind() != UnifiedDiff.Line.Kind.CONTEXT;
    }

    /**
     * Walks the tree looking for name tokens. A node in {@link
     * #DECLARATION_NODES} marks only the identifier sitting in its own
     * {@code name}/{@code declarator} field as a declaration -- not every
     * identifier in its subtree. Marking the whole subtree (the naive
     * reading of "this is a declaring node") would brand a call inside a
     * method body as a declaration of the method's own name, which is
     * exactly the false edge this design exists to avoid. Kotlin's grammar
     * carries no field names at all (confirmed by dumping every child's
     * field name), so when a declaration node's child has none, the first
     * bare name-shaped child stands in for the missing field.
     */
    private static void walk(TSNode node, Fragment fragment, String path, List<Symbol> out) {
        if (DECLARATION_NODES.contains(node.getType())) {
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                TSNode child = node.getChild(i);
                String field = node.getFieldNameForChild(i);
                boolean isDeclaredName = isNameNode(child)
                        && (field == null || NAME_FIELDS.contains(field));
                if (isDeclaredName) {
                    addSymbol(out, fragment, child, path, true);
                } else {
                    walk(child, fragment, path, out);
                }
            }
            return;
        }
        if (isNameNode(node)) {
            addSymbol(out, fragment, node, path, false);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), fragment, path, out);
        }
    }

    private static boolean isNameNode(TSNode node) {
        return NAME_NODES.contains(node.getType());
    }

    /**
     * {@code node}'s text, sliced from the fragment's own UTF-8 bytes rather
     * than {@code String.substring} on the joined text, because the node
     * range is a byte range (see {@link Fragment}). The line the node starts
     * on decides both whether this view reports it at all and whether it
     * counts as changed.
     */
    private static void addSymbol(List<Symbol> out, Fragment fragment, TSNode node, String path,
                                  boolean declaration) {
        int start = node.getStartByte();
        int index = fragment.lineAt(start);
        if (!fragment.reports(index)) {
            return;
        }
        String name = new String(fragment.utf8(), start, node.getEndByte() - start,
                StandardCharsets.UTF_8);
        if (SymbolWords.isSymbol(name)) {
            out.add(new Symbol(name, path, declaration, fragment.changed(index)));
        }
    }

    private static void lexical(List<Symbol> out, String path, String text, boolean changed) {
        Matcher matcher = SymbolWords.IDENTIFIER.matcher(text);
        while (matcher.find()) {
            String name = matcher.group();
            if (SymbolWords.isSymbol(name)) {
                out.add(new Symbol(name, path, false, changed));
            }
        }
    }
}
