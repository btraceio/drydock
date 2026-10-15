package app.drydock.review;

import org.treesitter.TSNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Java walk. Node shapes verified against the shipped grammar by the
 * probe that fed this class ({@code method_invocation} carries {@code
 * object}/{@code name}/{@code arguments}, {@code field_access} carries
 * {@code object}/{@code field}, {@code class_declaration} carries {@code
 * name}/{@code superclass}/{@code interfaces}/{@code body}; parameters
 * carry {@code name} where locals carry {@code declarator}; a
 * single-segment package is a bare {@code identifier} with no field
 * name).
 */
final class JavaTreeWalker extends TreeWalker {

    JavaTreeWalker(byte[] source) {
        super(source);
    }

    @Override
    void collect(TSNode root) {
        walk(root, List.of(), Map.of());
    }

    private void walk(TSNode node, List<String> enclosingClasses, Map<String, String> scopeVars) {
        switch (node.getType()) {
            case "program" -> collectImports(node);
            case "class_declaration", "interface_declaration", "enum_declaration",
                    "record_declaration" -> {
                String name = text(field(node, "name"));
                ClassDraft draft = new ClassDraft(superclassOf(node));
                collectMembers(field(node, "body"), draft);
                classes.put(name, draft);
                classNodes.add(node);
                List<String> next = append(enclosingClasses, name);
                for (int i = 0; i < node.getChildCount(); i++) {
                    walk(node.getChild(i), next, withFieldTypes(scopeVars, draft));
                }
                return;
            }
            case "method_declaration", "constructor_declaration" -> {
                // The method's scope: every parameter and local declared
                // anywhere in it, layered over the enclosing classes'
                // fields. Whole-method visibility, not declaration-order --
                // a use above the declaration line does not compile, so
                // this cannot over-bind.
                Map<String, String> inner = new LinkedHashMap<>(scopeVars);
                collectScopeVariables(node, inner);
                for (int i = 0; i < node.getChildCount(); i++) {
                    walk(node.getChild(i), enclosingClasses, inner);
                }
                return;
            }
            case "method_invocation", "field_access" -> recordReference(node, enclosingClasses,
                    scopeVars);
            default -> {
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), enclosingClasses, scopeVars);
        }
    }

    /**
     * The {@code extends} clause's type, or null: the superclass node
     * spans the {@code extends} keyword too, so the type is its child
     * ({@code type} field, falling back to the first type identifier).
     */
    private String superclassOf(TSNode classNode) {
        TSNode superclass = field(classNode, "superclass");
        if (superclass == null) {
            return null;
        }
        TSNode type = field(superclass, "type");
        if (type != null) {
            return simpleTypeName(type);
        }
        TSNode identifier = childOfType(superclass, "type_identifier");
        return identifier == null ? null : text(identifier);
    }

    /** Parameters and locals of one method-shaped node, into {@code out}. */
    private void collectScopeVariables(TSNode node, Map<String, String> out) {
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            switch (child.getType()) {
                case "formal_parameter", "local_variable_declaration" -> putTyped(child, out);
                default -> collectScopeVariables(child, out);
            }
        }
    }

    private void putTyped(TSNode node, Map<String, String> out) {
        TSNode typeNode = field(node, "type");
        // Two shapes carry a name: a formal_parameter names its identifier
        // directly, a local_variable_declaration wraps it in a
        // variable_declarator.
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
        if (body == null) {
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
        TSNode name = field(node, nameField);
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
     * The receiver's statically derivable type: an identifier (a class of
     * this file, a scoped variable, or a class-name candidate the binder
     * confirms or refuses), or -- for {@code Outer.Inner.member} -- the
     * last segment of the qualified name, as a type candidate. Empty when
     * the receiver is an expression whose type the file never states.
     */
    private Optional<String> receiverType(TSNode object, List<String> enclosingClasses,
                                          Map<String, String> scopeVars) {
        if (object.getType().equals("identifier")) {
            return receiverTypeOf(text(object), enclosingClasses, scopeVars);
        }
        if (object.getType().equals("field_access")) {
            TSNode member = field(object, "field");
            return member == null ? Optional.empty() : Optional.of(text(member));
        }
        return Optional.empty();
    }

    private void collectImports(TSNode program) {
        for (int i = 0; i < program.getChildCount(); i++) {
            TSNode child = program.getChild(i);
            if (child.getType().equals("package_declaration")) {
                // No field name on the package's identifier (verified:
                // neither the single- nor the multi-segment form carries
                // one), so the name is the named child that is not the
                // keyword -- a scoped_identifier for dotted packages, a
                // plain identifier for single-segment ones.
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
                // could reference is the segment above it, detected from
                // the tree (the raw text lost the keyword).
                if (child.getChild(0).getType().equals("static_import")) {
                    imported = imported.substring(0, imported.lastIndexOf('.'));
                }
                imports.add(imported);
            }
        }
    }
}
