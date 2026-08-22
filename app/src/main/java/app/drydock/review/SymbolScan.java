package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
 * everywhere the name appears.</p>
 *
 * <p>Blocking: parsing a line -- and, the first time any language is used,
 * loading its native grammar library via {@link GrammarRegistry} -- both do
 * real work (native calls, disk I/O). Never call {@link #of} on the FX
 * thread.</p>
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
     */
    private static final List<String> NAME_NODES = List.of(
            "identifier", "type_identifier", "field_identifier", "simple_identifier");

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

    private SymbolScan() {
    }

    /** {@code file}'s symbols, in source order. */
    public static List<Symbol> of(UnifiedDiff.FileDiff file) {
        Optional<TSLanguage> grammar = GrammarRegistry.forPath(file.path());
        List<Symbol> symbols = new ArrayList<>();
        for (UnifiedDiff.Hunk hunk : file.hunks()) {
            for (UnifiedDiff.Line line : hunk.lines()) {
                boolean changed = line.kind() != UnifiedDiff.Line.Kind.CONTEXT;
                if (grammar.isPresent()) {
                    symbols.addAll(parsed(grammar.get(), file.path(), line.text(), changed));
                } else {
                    symbols.addAll(lexical(file.path(), line.text(), changed));
                }
            }
        }
        return List.copyOf(symbols);
    }

    /**
     * Line-at-a-time parsing. A diff line is not a compilation unit, so the
     * tree is usually an ERROR node with recognisable children -- enough for
     * "is this token introducing a name", the only question asked here, and
     * it avoids reconstructing whole files from a diff.
     *
     * <p>A fresh {@link TSParser} (and the {@link TSTree} it returns) per
     * line is deliberate, not a leak: the binding exposes no public {@code
     * close()}/{@code delete()} on either type -- decompiling {@code
     * TSParser}'s and {@code TSTree}'s constructors shows each registers a
     * {@code java.lang.ref.Cleaner} action that calls the native {@code
     * ts_*_delete} when the object becomes unreachable. There is nothing a
     * manual call could free that the Cleaner does not already own.</p>
     */
    private static List<Symbol> parsed(TSLanguage language, String path, String text,
                                       boolean changed) {
        TSTree tree;
        try {
            TSParser parser = new TSParser();
            parser.setLanguage(language);
            tree = parser.parseString(null, text);
        } catch (RuntimeException e) {
            // A fragment the grammar cannot even tokenise (verified: a lone
            // unpaired UTF-16 surrogate throws "Invalid UTF-8 source input"
            // from the native layer) is not a reason to lose the file --
            // fall back to the same lexical scan an ungrammared file gets.
            //
            // Scoped to just the native-facing calls: catching a wider block
            // here would let a bug in walk() -- our own Java, not the
            // grammar -- disappear into this same "expected fallback" path
            // with no log and no test signal. Absent and broken must not
            // look the same.
            return lexical(path, text, changed);
        }
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        List<Symbol> symbols = new ArrayList<>();
        walk(tree.getRootNode(), utf8, path, changed, symbols);
        return symbols;
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
    private static void walk(TSNode node, byte[] utf8, String path, boolean changed,
                             List<Symbol> out) {
        if (DECLARATION_NODES.contains(node.getType())) {
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                TSNode child = node.getChild(i);
                String field = node.getFieldNameForChild(i);
                boolean isDeclaredName = isNameNode(child)
                        && (field == null || NAME_FIELDS.contains(field));
                if (isDeclaredName) {
                    addSymbol(out, utf8, child, path, true, changed);
                } else {
                    walk(child, utf8, path, changed, out);
                }
            }
            return;
        }
        if (isNameNode(node)) {
            addSymbol(out, utf8, node, path, false, changed);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), utf8, path, changed, out);
        }
    }

    private static boolean isNameNode(TSNode node) {
        return NAME_NODES.contains(node.getType());
    }

    /**
     * {@code node}'s text, sliced from the line's own UTF-8 bytes rather
     * than {@code String.substring} on the original line. {@link
     * TSNode#getStartByte()}/{@link TSNode#getEndByte()} are UTF-8 BYTE
     * offsets (confirmed: a line with two-byte characters before an
     * identifier has a byte length longer than its char length, and the
     * identifier's own node range is the byte span, not the char span) --
     * slicing the {@code String} by char index would misalign, or throw,
     * for any line with a multi-byte character before the token.
     */
    private static void addSymbol(List<Symbol> out, byte[] utf8, TSNode node, String path,
                                  boolean declaration, boolean changed) {
        String name = new String(utf8, node.getStartByte(), node.getEndByte() - node.getStartByte(),
                StandardCharsets.UTF_8);
        if (SymbolWords.isSymbol(name)) {
            out.add(new Symbol(name, path, declaration, changed));
        }
    }

    private static List<Symbol> lexical(String path, String text, boolean changed) {
        List<Symbol> symbols = new ArrayList<>();
        Matcher matcher = SymbolWords.IDENTIFIER.matcher(text);
        while (matcher.find()) {
            String name = matcher.group();
            if (SymbolWords.isSymbol(name)) {
                symbols.add(new Symbol(name, path, false, changed));
            }
        }
        return symbols;
    }
}
