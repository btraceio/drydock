package app.drydock.ui.nav;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tier between name matching and a language server: a bound occurrence
 * is a reference the parse tree says is real. Every test pins a REAL binding
 * or a REAL refusal -- the refusals are the point (they are the usage-link
 * noise the lexical tier cannot help but find), and the fixtures are whole
 * little Java programs on disk so the binder reads exactly what production
 * reads.
 */
class JavaScopeBinderTest {

    @TempDir
    Path root;
    private JavaScopeBinder binder;

    @BeforeEach
    void setUp() {
        binder = new JavaScopeBinder(root);
    }

    private void java(String relative, String... lines) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n");
    }

    // ---- what binds ----------------------------------------------------------

    /** No receiver: the enclosing class owns the member, right here in the file. */
    @Test
    void aBareCallBindsToTheEnclosingClassDeclaration() throws IOException {
        java("src/Caller.java",
                "package a;",
                "class Caller {",
                "  void target() {}",
                "  void caller() {",
                "    target();",
                "  }",
                "}");

        JavaScopeBinder.Binding binding = binder.bind("target",
                Optional.of(new JavaScopeBinder.Declaration(Path.of("src/Caller.java"), 3)),
                Path.of("src/Caller.java"));

        assertEquals(new JavaScopeBinder.Declaration(Path.of("src/Caller.java"), 3),
                binding.boundLines().get(5), "line 5's bare call binds to the declaration on line 3");
    }

    /** {@code this.field}: same class, field_access shape. */
    @Test
    void aThisFieldAccessBinds() throws IOException {
        java("src/Holder.java",
                "package a;",
                "class Holder {",
                "  int count;",
                "  void bump() {",
                "    this.count = this.count + 1;",
                "  }",
                "}");

        JavaScopeBinder.Binding binding = binder.bind("count",
                Optional.of(new JavaScopeBinder.Declaration(Path.of("src/Holder.java"), 3)),
                Path.of("src/Holder.java"));

        assertTrue(binding.boundLines().containsKey(5), "this.count binds to the field on line 3");
        assertEquals(3, binding.boundLines().get(5).line());
    }

    /** A local's declared type, resolved through an import into another file. */
    @Test
    void aTypedLocalBindsAcrossFilesThroughTheImport() throws IOException {
        java("src/a/Helper.java",
                "package a;",
                "class Helper {",
                "  void target() {}",
                "}");
        java("src/b/Caller.java",
                "package b;",
                "import a.Helper;",
                "class Caller {",
                "  void run() {",
                "    Helper helper = new Helper();",
                "    helper.target();",
                "  }",
                "}");

        JavaScopeBinder.Binding binding = binder.bind("target",
                Optional.of(new JavaScopeBinder.Declaration(Path.of("src/a/Helper.java"), 3)),
                Path.of("src/b/Caller.java"));

        assertEquals(new JavaScopeBinder.Declaration(Path.of("src/a/Helper.java"), 3),
                binding.boundLines().get(6), "the local's type resolves to Helper.java line 3");
    }

    /** {@code Class.member()} static shape: the receiver names the class itself. */
    @Test
    void aQualifiedStaticCallBindsThroughTheImport() throws IOException {
        java("src/a/Util.java",
                "package a;",
                "class Util {",
                "  static int clamp(int v) { return v; }",
                "}");
        java("src/b/Caller.java",
                "package b;",
                "import a.Util;",
                "class Caller {",
                "  int run(int v) {",
                "    return Util.clamp(v);",
                "  }",
                "}");

        JavaScopeBinder.Binding binding = binder.bind("clamp",
                Optional.of(new JavaScopeBinder.Declaration(Path.of("src/a/Util.java"), 3)),
                Path.of("src/b/Caller.java"));

        assertEquals(new JavaScopeBinder.Declaration(Path.of("src/a/Util.java"), 3),
                binding.boundLines().get(5));
    }

    /** Same package, no import: the package names the file. */
    @Test
    void aSamePackageTypeBindsWithoutAnImport() throws IOException {
        java("src/a/Peer.java",
                "package a;",
                "class Peer {",
                "  void target() {}",
                "}");
        java("src/a/Caller.java",
                "package a;",
                "class Caller {",
                "  void run(Peer peer) {",
                "    peer.target();",
                "  }",
                "}");

        JavaScopeBinder.Binding binding = binder.bind("target",
                Optional.of(new JavaScopeBinder.Declaration(Path.of("src/a/Peer.java"), 3)),
                Path.of("src/a/Caller.java"));

        assertEquals(new JavaScopeBinder.Declaration(Path.of("src/a/Peer.java"), 3),
                binding.boundLines().get(4));
    }

    /**
     * The superclass chain: the receiver's own class does not declare the
     * member, its {@code extends} does. Inheritance is how half of real
     * Java calls dispatch; refusing it would under-claim massively.
     */
    @Test
    void anInheritedMemberBindsThroughTheSuperclass() throws IOException {
        java("src/a/Base.java",
                "package a;",
                "class Base {",
                "  void target() {}",
                "}");
        java("src/a/Sub.java",
                "package a;",
                "class Sub extends Base {",
                "}",
                "class Caller {",
                "  void run(Sub sub) {",
                "    sub.target();",
                "  }",
                "}");

        JavaScopeBinder.Binding binding = binder.bind("target",
                Optional.of(new JavaScopeBinder.Declaration(Path.of("src/a/Base.java"), 3)),
                Path.of("src/a/Sub.java"));

        assertEquals(new JavaScopeBinder.Declaration(Path.of("src/a/Base.java"), 3),
                binding.boundLines().get(6), "target binds to Base, one extends hop away");
    }

    /** A field's declared type resolves receivers exactly like a local's. */
    @Test
    void aFieldReceiverBindsByTheFieldType() throws IOException {
        java("src/a/Helper.java",
                "package a;",
                "class Helper {",
                "  void target() {}",
                "}");
        java("src/a/Holder.java",
                "package a;",
                "import a.Helper;",
                "class Holder {",
                "  private final Helper helper = new Helper();",
                "  void run() {",
                "    helper.target();",
                "  }",
                "}");

        JavaScopeBinder.Binding binding = binder.bind("target",
                Optional.of(new JavaScopeBinder.Declaration(Path.of("src/a/Helper.java"), 3)),
                Path.of("src/a/Holder.java"));

        assertEquals(new JavaScopeBinder.Declaration(Path.of("src/a/Helper.java"), 3),
                binding.boundLines().get(6));
    }

    // ---- what refuses (the de-noise) ------------------------------------------

    /**
     * The case the whole tier exists for: another class declares a
     * same-named member, and an unrelated receiver calls ITS version. The
     * name matches; the reference does not.
     */
    @Test
    void aSameNameMethodOnAnUnrelatedReceiverDoesNotBind() throws IOException {
        java("src/a/Real.java",
                "package a;",
                "class Real {",
                "  void target() {}",
                "}");
        java("src/a/Other.java",
                "package a;",
                "class Other {",
                "  void target() {}",
                "}");
        java("src/a/Caller.java",
                "package a;",
                "class Caller {",
                "  void run(Other other) {",
                "    other.target();",
                "  }",
                "}");

        JavaScopeBinder.Binding binding = binder.bind("target",
                Optional.of(new JavaScopeBinder.Declaration(Path.of("src/a/Real.java"), 3)),
                Path.of("src/a/Caller.java"));

        assertTrue(binding.isEmpty(),
                "Other.target is a real method, but it is not the symbol the reader asked about"
                        + " -- the call site binds nothing");
        assertEquals(Optional.of(new JavaScopeBinder.Declaration(Path.of("src/a/Other.java"), 3)),
                binding.unanimousDeclaration(),
                "the votes say which declaration the file DOES reference -- a peek that"
                        + " centred on the wrong candidate can read this and re-centre");
    }

    /** A receiver whose type the file never states (a chained call) is honest noise. */
    @Test
    void aChainedCallReceiverDoesNotBind() throws IOException {
        java("src/a/Helper.java",
                "package a;",
                "class Helper {",
                "  void target() {}",
                "}");
        java("src/a/Caller.java",
                "package a;",
                "class Caller {",
                "  Helper make() { return null; }",
                "  void run() {",
                "    make().target();",
                "  }",
                "}");

        assertTrue(binder.bind("target",
                        Optional.of(new JavaScopeBinder.Declaration(Path.of("src/a/Helper.java"), 3)),
                        Path.of("src/a/Caller.java")).isEmpty(),
                "no type inference here: the receiver's type is a guess, and guesses do not bind");
    }

    /** A library type ({@code java.lang} here) cannot be resolved from the repo. */
    @Test
    void anUnresolvableReceiverTypeDoesNotBind() throws IOException {
        java("src/a/Caller.java",
                "package a;",
                "class Caller {",
                "  void run(String s) {",
                "    s.length();",
                "  }",
                "}");

        assertTrue(binder.bind("length", Optional.empty(), Path.of("src/a/Caller.java")).isEmpty());
    }

    /** Two files could both be the type: a guess with a binding's authority is refused. */
    @Test
    void anAmbiguousTypeResolutionRefuses() throws IOException {
        java("src/p1/Helper.java", "package p1;", "class Helper { void target() {} }");
        java("src/p2/Helper.java", "package p2;", "class Helper { void target() {} }");
        java("src/a/Caller.java",
                "package a;",
                "import p1.Helper;",
                "class Caller {",
                "  void run(Helper helper) {",
                "    helper.target();",
                "  }",
                "}");

        // The import names p1.Helper exactly, so this BINDS: ambiguity is
        // only unresolvable when nothing names the package.
        assertEquals(1, binder.bind("target",
                        Optional.of(new JavaScopeBinder.Declaration(Path.of("src/p1/Helper.java"), 2)),
                        Path.of("src/a/Caller.java")).boundLines().size(),
                "the import disambiguates");

        java("src/b/Caller.java",
                "package a;",     // deliberately the same package as nothing
                "class Caller {",
                "  void run(Helper helper) {",
                "    helper.target();",
                "  }",
                "}");
        // No import, no same-package Helper: p1 and p2 are both candidates
        // and neither the file nor the binder can pick.
        assertTrue(binder.bind("target",
                        Optional.of(new JavaScopeBinder.Declaration(Path.of("src/p1/Helper.java"), 2)),
                        Path.of("src/b/Caller.java")).isEmpty(),
                "two candidates, no discriminator: refuse rather than guess");
    }

    /** A missing file or a non-Java path binds nothing, and that is not an error. */
    @Test
    void aMissingFileBindsNothing() {
        assertTrue(binder.bind("anything", Optional.empty(), Path.of("src/Gone.java")).isEmpty());
    }

    /**
     * Generics in the type ({@code Container<Helper>}) resolve by the
     * container's own name only when the container is resolvable; the
     * receiver's type is the container, not the type argument.
     */
    @Test
    void aGenericReceiverTypeResolvesByItsOwnName() throws IOException {
        java("src/a/Container.java",
                "package a;",
                "class Container<T> {",
                "  void target() {}",
                "}");
        java("src/a/Caller.java",
                "package a;",
                "class Caller {",
                "  void run(Container<Caller> box) {",
                "    box.target();",
                "  }",
                "}");

        JavaScopeBinder.Binding binding = binder.bind("target",
                Optional.of(new JavaScopeBinder.Declaration(Path.of("src/a/Container.java"), 3)),
                Path.of("src/a/Caller.java"));

        assertEquals(new JavaScopeBinder.Declaration(Path.of("src/a/Container.java"), 3),
                binding.boundLines().get(4));
    }

    /** Multiple occurrences in one file: each is judged on its own receiver. */
    @Test
    void eachOccurrenceIsJudgedAlone() throws IOException {
        java("src/a/Real.java", "package a;", "class Real {", "  void target() {} }");
        java("src/a/Other.java", "package a;", "class Other {", "  void target() {} }");
        java("src/a/Caller.java",
                "package a;",
                "class Caller extends Real {",
                "  void run(Other other) {",
                "    target();",          // line 5: inherited through Real? No --
                "    other.target();",    // bare call binds only to classes of THIS file
                "  }",                   // (extends resolution is receiver-driven)
                "}");

        Map<Integer, JavaScopeBinder.Declaration> bound =
                binder.bind("target",
                        Optional.of(new JavaScopeBinder.Declaration(Path.of("src/a/Real.java"), 3)),
                        Path.of("src/a/Caller.java")).boundLines();

        // The bare call on line 5: Caller does not declare target, and v1's
        // bare-call rule is same-file classes only -- inherited bare calls
        // stay lexical. The honest under-claim, pinned.
        assertFalse(bound.containsKey(4),
                "a bare inherited call is v1's known under-claim: it stays lexical");
        assertFalse(bound.containsKey(5), "Other.target is not Real.target");
    }
}
