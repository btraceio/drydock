package app.drydock.review;

import org.treesitter.TSNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One language's walk over a parse tree, filling the drafts {@link
 * ScopeBinder} resolves against: classes with members and field types,
 * method scopes with their typed variables, references with derivable
 * receivers, and the package/imports the type resolution needs.
 *
 * <p>Subclasses implement {@link #collect} for their grammar's real node
 * shapes -- shapes read from a probe that parsed representative source
 * and printed the tree tree-sitter itself produced, never assumed. The
 * binding exposes node text only as byte offsets into the source ({@code
 * getText()} does not exist), so every string is cut out of the file's
 * own UTF-8 bytes here; and it hands out NULL NODES for absent fields,
 * never Java nulls, so every field access goes through {@link #field}.</p>
 */
abstract class TreeWalker {

    private final byte[] source;

    final Map<String, ClassDraft> classes = new LinkedHashMap<>();
    final List<TSNode> classNodes = new ArrayList<>();
    final List<String> imports = new ArrayList<>();
    final List<String> wildcards = new ArrayList<>();
    final List<Reference> references = new ArrayList<>();
    String packageName = "";

    TreeWalker(byte[] source) {
        this.source = source;
    }

    /** One reference-shaped occurrence: the used name, its receiver's derivable type (empty = unknown). */
    record Reference(String name, Optional<String> receiverType, List<String> enclosingClasses,
                     boolean unqualified, int line) {
    }

    /** One class-like declaration: members (name → line), field types, and its superclass name. */
    static final class ClassDraft {
        final String superclass;
        final Map<String, Integer> members = new LinkedHashMap<>();
        final Map<String, String> fieldTypes = new LinkedHashMap<>();

        ClassDraft(String superclass) {
            this.superclass = superclass == null ? "" : superclass;
        }
    }

    /** Walks from {@code root}, filling this walker's drafts. */
    abstract void collect(TSNode root);

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

    /** The first child of {@code node} carrying {@code type}, or null -- the shapes that carry no field name. */
    TSNode childOfType(TSNode node, String type) {
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (child.getType().equals(type)) {
                return child;
            }
        }
        return null;
    }

    /**
     * The receiver's statically derivable type: a name that is a class of
     * this file, else a variable in scope whose declared type the drafts
     * know, else a bare name passed through as a TYPE CANDIDATE the binder
     * will confirm or refuse. Subclasses pre-classify the receiver shape
     * (identifier, qualified, expression) and call this for the names.
     */
    Optional<String> receiverTypeOf(String name, List<String> enclosingClasses,
                                    Map<String, String> scopeVars) {
        if (isClassOfThisFile(name)) {
            return Optional.of(name);
        }
        String typed = lookupVar(name, enclosingClasses, scopeVars);
        if (typed != null) {
            return Optional.of(typed);
        }
        return Optional.of(name);
    }

    String lookupVar(String name, List<String> enclosingClasses, Map<String, String> scopeVars) {
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

    boolean isClassOfThisFile(String name) {
        return classes.containsKey(name);
    }

    /**
     * {@code List<Foo>} → {@code List}, {@code a.b.C} → {@code C}, {@code
     * Util?} → {@code Util}: the simple name a receiver could match,
     * across the shipped grammars' type spellings.
     */
    String simpleTypeName(TSNode typeNode) {
        String text = text(typeNode).strip();
        int generic = text.indexOf('<');
        if (generic > 0) {
            text = text.substring(0, generic);
        }
        int dot = text.lastIndexOf('.');
        if (dot >= 0 && dot < text.length() - 1) {
            text = text.substring(dot + 1);
        }
        return text.replace("?", "").strip();
    }

    static List<String> append(List<String> list, String name) {
        List<String> next = new ArrayList<>(list);
        next.add(name);
        return next;
    }

    static Map<String, String> withFieldTypes(Map<String, String> scopeVars, ClassDraft draft) {
        Map<String, String> next = new LinkedHashMap<>(scopeVars);
        next.putAll(draft.fieldTypes);
        return next;
    }
}
