package app.drydock.review;

import org.treesitter.TSNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Kotlin walk. Node shapes verified against the shipped grammar by
 * the probe that fed this class: declarations carry NO field names
 * ({@code class_declaration}/{@code object_declaration}/{@code
 * companion_object} name their {@code type_identifier} child; a {@code
 * function_declaration} names its {@code simple_identifier}), the
 * superclass rides a {@code delegation_specifier} ({@code
 * constructor_invocation} → {@code user_type}, or a bare {@code
 * user_type}), a member access is a {@code navigation_expression}
 * (receiver + {@code navigation_suffix}'s {@code simple_identifier})
 * whether called or not, a bare call is a {@code call_expression} over a
 * lone {@code simple_identifier}, and a parameter/property type is a
 * {@code user_type} or a {@code nullable_type} wrapping one.
 */
final class KotlinTreeWalker extends TreeWalker {

    KotlinTreeWalker(byte[] source) {
        super(source);
    }

    @Override
    void collect(TSNode root) {
        walk(root, List.of(), Map.of());
    }

    private void walk(TSNode node, List<String> enclosingClasses, Map<String, String> scopeVars) {
        switch (node.getType()) {
            case "source_file" -> collectImports(node);
            case "class_declaration", "object_declaration", "companion_object" -> {
                TSNode nameNode = childOfType(node, "type_identifier");
                String name = nameNode == null ? "" : text(nameNode);
                ClassDraft draft = new ClassDraft(superclassOf(node));
                collectMembers(childOfType(node, "class_body"), draft);
                if (!name.isEmpty()) {
                    classes.put(name, draft);
                    classNodes.add(node);
                }
                List<String> next = append(enclosingClasses, name);
                for (int i = 0; i < node.getChildCount(); i++) {
                    walk(node.getChild(i), next, withFieldTypes(scopeVars, draft));
                }
                return;
            }
            case "function_declaration" -> {
                // The function's scope: every parameter and property
                // (Kotlin's locals) declared anywhere in it, layered over
                // the enclosing classes' properties.
                Map<String, String> inner = new LinkedHashMap<>(scopeVars);
                collectScopeVariables(node, inner);
                for (int i = 0; i < node.getChildCount(); i++) {
                    walk(node.getChild(i), enclosingClasses, inner);
                }
                return;
            }
            case "navigation_expression" -> {
                recordMember(node, enclosingClasses, scopeVars);
            }
            case "call_expression" -> {
                recordBareCall(node, enclosingClasses, scopeVars);
            }
            default -> {
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), enclosingClasses, scopeVars);
        }
    }

    /**
     * The {@code : Base(...)} clause's type, or null. Both spellings came
     * out of the probe: a {@code constructor_invocation} wraps the
     * {@code user_type} (Base with parentheses), and a bare {@code
     * user_type} names a supertype with none.
     */
    private String superclassOf(TSNode classNode) {
        TSNode delegation = childOfType(classNode, "delegation_specifier");
        if (delegation == null) {
            return null;
        }
        TSNode invocation = childOfType(delegation, "constructor_invocation");
        TSNode container = invocation == null ? delegation : invocation;
        TSNode userType = childOfType(container, "user_type");
        return userType == null ? null : simpleTypeName(userType);
    }

    /** Parameters and properties (locals) of one function-shaped node, into {@code out}. */
    private void collectScopeVariables(TSNode node, Map<String, String> out) {
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            switch (child.getType()) {
                case "parameter" -> putTyped(child, out);
                case "property_declaration" -> putTyped(child, out);
                default -> collectScopeVariables(child, out);
            }
        }
    }

    /**
     * A parameter or property: the name is the {@code
     * variable_declaration}'s (or parameter's) {@code simple_identifier},
     * the type a {@code user_type} or {@code nullable_type} beside it. A
     * property with no stated type ({@code val inferred = ...}) contributes
     * nothing -- the receiver it names stays unbound, the honest default.
     */
    private void putTyped(TSNode node, Map<String, String> out) {
        TSNode nameNode = childOfType(node, "simple_identifier");
        if (nameNode == null) {
            TSNode declaration = childOfType(node, "variable_declaration");
            nameNode = declaration == null ? null : childOfType(declaration, "simple_identifier");
        }
        if (nameNode == null) {
            return;
        }
        String type = declaredType(node);
        if (type != null) {
            out.putIfAbsent(text(nameNode), type);
        }
    }

    /** The user_type text of a parameter or property, nullable or not; null when none is stated. */
    private String declaredType(TSNode node) {
        TSNode userType = childOfType(node, "user_type");
        if (userType == null) {
            TSNode nullable = childOfType(node, "nullable_type");
            userType = nullable == null ? null : childOfType(nullable, "user_type");
        }
        if (userType == null) {
            // A property keeps its type beside the name, one level into
            // the variable_declaration.
            TSNode declaration = childOfType(node, "variable_declaration");
            if (declaration != null) {
                userType = childOfType(declaration, "user_type");
                if (userType == null) {
                    TSNode nullable = childOfType(declaration, "nullable_type");
                    userType = nullable == null ? null : childOfType(nullable, "user_type");
                }
            }
        }
        return userType == null ? null : simpleTypeName(userType);
    }

    private void collectMembers(TSNode body, ClassDraft draft) {
        if (body == null) {
            return;
        }
        for (int i = 0; i < body.getChildCount(); i++) {
            TSNode child = body.getChild(i);
            switch (child.getType()) {
                case "function_declaration" -> {
                    TSNode name = childOfType(child, "simple_identifier");
                    if (name != null) {
                        draft.members.putIfAbsent(text(name), child.getStartPoint().getRow() + 1);
                    }
                }
                case "property_declaration" -> {
                    TSNode declaration = childOfType(child, "variable_declaration");
                    TSNode name = declaration == null ? null
                            : childOfType(declaration, "simple_identifier");
                    if (name != null) {
                        draft.members.putIfAbsent(text(name), child.getStartPoint().getRow() + 1);
                        String type = declaredType(child);
                        if (type != null) {
                            draft.fieldTypes.putIfAbsent(text(name), type);
                        }
                    }
                }
                case "class_declaration", "object_declaration", "companion_object" -> {
                    TSNode name = childOfType(child, "type_identifier");
                    if (name != null) {
                        draft.members.putIfAbsent(text(name), child.getStartPoint().getRow() + 1);
                    }
                }
                default -> {
                }
            }
        }
    }

    /**
     * {@code obj.member} / {@code obj.member(...)} / {@code this.member}
     * / {@code Outer.Inner.member}: a navigation_expression whose
     * navigation_suffix names the member and whose receiver is a name, a
     * {@code this} or another navigation. A receiver that is a call is a
     * chained expression -- its type is a guess the file never made, and
     * the occurrence stays unbound.
     */
    private void recordMember(TSNode node, List<String> enclosingClasses,
                              Map<String, String> scopeVars) {
        TSNode suffix = childOfType(node, "navigation_suffix");
        TSNode member = suffix == null ? null : childOfType(suffix, "simple_identifier");
        if (member == null) {
            return;
        }
        TSNode receiver = node.getChild(0);
        boolean bare = receiver == null || receiver.getType().equals("this_expression");
        Optional<String> receiverType = bare ? Optional.empty()
                : receiverTypeOf(receiver, enclosingClasses, scopeVars);
        references.add(new Reference(text(member), receiverType,
                List.copyOf(enclosingClasses), bare,
                node.getStartPoint().getRow() + 1));
    }

    /**
     * The receiver's derivable type: a name (a class of this file, a
     * scoped variable, or a type candidate the binder confirms or
     * refuses), or -- for {@code Holder.Meta.SIZE} -- the last segment of
     * the nested navigation. A call receiver refuses.
     */
    private Optional<String> receiverTypeOf(TSNode receiver, List<String> enclosingClasses,
                                           Map<String, String> scopeVars) {
        if (receiver.getType().equals("simple_identifier")) {
            return receiverTypeOf(text(receiver), enclosingClasses, scopeVars);
        }
        if (receiver.getType().equals("navigation_expression")) {
            TSNode suffix = childOfType(receiver, "navigation_suffix");
            TSNode last = suffix == null ? null : childOfType(suffix, "simple_identifier");
            return last == null ? Optional.empty() : Optional.of(text(last));
        }
        return Optional.empty();
    }

    private void recordBareCall(TSNode node, List<String> enclosingClasses,
                                Map<String, String> scopeVars) {
        TSNode callee = childOfType(node, "simple_identifier");
        if (callee == null) {
            return;
        }
        references.add(new Reference(text(callee), Optional.empty(),
                List.copyOf(enclosingClasses), true,
                node.getStartPoint().getRow() + 1));
    }

    private void collectImports(TSNode sourceFile) {
        for (int i = 0; i < sourceFile.getChildCount(); i++) {
            TSNode child = sourceFile.getChild(i);
            if (child.getType().equals("package_header")) {
                TSNode identifier = childOfType(child, "identifier");
                if (identifier != null) {
                    packageName = text(identifier);
                }
                continue;
            }
            if (!child.getType().equals("import_list")) {
                continue;
            }
            for (int j = 0; j < child.getChildCount(); j++) {
                TSNode header = child.getChild(j);
                if (!header.getType().equals("import_header")) {
                    continue;
                }
                TSNode wildcard = childOfType(header, "wildcard_import");
                if (wildcard != null) {
                    TSNode identifier = childOfType(header, "identifier");
                    if (identifier != null) {
                        wildcards.add(text(identifier));
                    }
                    continue;
                }
                TSNode identifier = childOfType(header, "identifier");
                if (identifier != null) {
                    String imported = text(identifier).strip();
                    if (imported.lastIndexOf('.') > 0) {
                        imports.add(imported);
                    }
                }
            }
        }
    }
}
