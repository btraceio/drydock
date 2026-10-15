package app.drydock.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Kotlin walk, pinned to the same honesty the Java one is: a bound
 * occurrence is a reference the tree says is real (a derivable receiver
 * resolving to the peeked declaration), and everything else -- chained
 * calls, inferred types, same-named members on unrelated classes -- stays
 * lexical. Every fixture is a whole little Kotlin file on disk, written
 * against shapes read from a probe of the shipped grammar.
 */
class KotlinScopeBinderTest {

    @TempDir
    Path root;
    private ScopeBinder binder;

    @BeforeEach
    void setUp() {
        binder = new ScopeBinder(root);
    }

    private void kt(String relative, String... lines) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n");
    }

    @Test
    void aBareCallBindsToTheEnclosingClassDeclaration() throws IOException {
        kt("src/Caller.kt",
                "package a",
                "class Caller {",
                "  fun target() {}",
                "  fun caller() {",
                "    target()",
                "  }",
                "}");

        ScopeBinder.Binding binding = binder.bind("target",
                Optional.of(new ScopeBinder.Declaration(Path.of("src/Caller.kt"), 3)),
                Path.of("src/Caller.kt"));

        assertEquals(Optional.of(new ScopeBinder.Declaration(Path.of("src/Caller.kt"), 3)),
                Optional.ofNullable(binding.boundLines().get(5)),
                "line 5's bare call binds to the declaration on line 3");
    }

    /** A typed parameter binds cross-file through the import, the Java path's twin. */
    @Test
    void typedParameterBindsThroughTheImport() throws IOException {
        kt("src/a/Helper.kt",
                "package a",
                "class Helper {",
                "  fun target() {}",
                "}");
        kt("src/b/Caller.kt",
                "package b",
                "import a.Helper",
                "class Caller {",
                "  fun run(helper: Helper) {",
                "    helper.target()",
                "  }",
                "}");

        ScopeBinder.Binding binding = binder.bind("target",
                Optional.of(new ScopeBinder.Declaration(Path.of("src/a/Helper.kt"), 3)),
                Path.of("src/b/Caller.kt"));

        assertEquals(Optional.of(new ScopeBinder.Declaration(Path.of("src/a/Helper.kt"), 3)),
                Optional.ofNullable(binding.boundLines().get(5)),
                "the parameter's type resolves to Helper.kt line 3");
    }

    /** Kotlin's nullable spelling: {@code val h: Helper? = null} types the receiver of {@code h?.target()}. */
    @Test
    void aNullableTypedLocalBinds() throws IOException {
        kt("src/a/Helper.kt",
                "package a",
                "class Helper {",
                "  fun target() {}",
                "}");
        kt("src/a/Caller.kt",
                "package a",
                "import a.Helper",
                "class Caller {",
                "  fun run() {",
                "    val helper: Helper? = null",
                "    helper?.target()",
                "  }",
                "}");

        ScopeBinder.Binding binding = binder.bind("target",
                Optional.of(new ScopeBinder.Declaration(Path.of("src/a/Helper.kt"), 3)),
                Path.of("src/a/Caller.kt"));

        assertTrue(binding.boundLines().containsKey(6),
                "the nullable local's declared type is derivable: the ?. call binds");
    }

    /** The de-noise case, Kotlin side: an unrelated class's same-named member is refused. */
    @Test
    void aSameNamedStrangerDoesNotBind() throws IOException {
        kt("src/a/Real.kt",
                "package a",
                "class Real {",
                "  fun target() {}",
                "}");
        kt("src/a/Other.kt",
                "package a",
                "class Other {",
                "  fun target() {}",
                "}");
        kt("src/a/Caller.kt",
                "package a",
                "class Caller {",
                "  fun run(other: Other) {",
                "    other.target()",
                "  }",
                "}");

        ScopeBinder.Binding binding = binder.bind("target",
                Optional.of(new ScopeBinder.Declaration(Path.of("src/a/Real.kt"), 3)),
                Path.of("src/a/Caller.kt"));

        assertTrue(binding.isEmpty(),
                "Other.target is real -- for Other; not for the declaration the reader asked about");
    }

    /** {@code : Base()} inheritance: the delegation specifier's type is the superclass. */
    @Test
    void anInheritedBareCallBindsThroughTheSuperclass() throws IOException {
        kt("src/a/Base.kt",
                "package a",
                "open class Base {",
                "  fun target() {}",
                "}");
        kt("src/a/Caller.kt",
                "package a",
                "class Caller : Base() {",
                "  fun run() {",
                "    target()",
                "  }",
                "}");

        ScopeBinder.Binding binding = binder.bind("target",
                Optional.of(new ScopeBinder.Declaration(Path.of("src/a/Base.kt"), 3)),
                Path.of("src/a/Caller.kt"));

        assertEquals(Optional.of(new ScopeBinder.Declaration(Path.of("src/a/Base.kt"), 3)),
                Optional.ofNullable(binding.boundLines().get(4)),
                "the bare inherited call binds to Base, one delegation hop away");
    }

    /** A chained receiver's type is a guess the file never made; the occurrence stays lexical. */
    @Test
    void aChainedCallDoesNotBind() throws IOException {
        kt("src/a/Helper.kt",
                "package a",
                "class Helper {",
                "  fun target() {}",
                "  fun make(): Helper = this",
                "}");
        kt("src/a/Caller.kt",
                "package a",
                "import a.Helper",
                "class Caller {",
                "  fun run(helper: Helper) {",
                "    helper.make().target()",
                "  }",
                "}");

        assertTrue(binder.bind("target",
                        Optional.of(new ScopeBinder.Declaration(Path.of("src/a/Helper.kt"), 3)),
                        Path.of("src/a/Caller.kt")).isEmpty(),
                "no type inference: the chained receiver binds nothing");
    }

    /** An inferred local ({@code val h = Helper()}) states no type; its receiver stays unbound. */
    @Test
    void anInferredTypeDoesNotBind() throws IOException {
        kt("src/a/Helper.kt",
                "package a",
                "class Helper {",
                "  fun target() {}",
                "}");
        kt("src/a/Caller.kt",
                "package a",
                "import a.Helper",
                "class Caller {",
                "  fun run() {",
                "    val helper = Helper()",
                "    helper.target()",
                "  }",
                "}");

        assertTrue(binder.bind("target",
                        Optional.of(new ScopeBinder.Declaration(Path.of("src/a/Helper.kt"), 3)),
                        Path.of("src/a/Caller.kt")).isEmpty(),
                "the honest default: a type the file never stated is not derivable");
    }

    /** {@code this.member} binds to the enclosing class's own declaration. */
    @Test
    void aThisNavigationBinds() throws IOException {
        kt("src/Holder.kt",
                "package a",
                "class Holder {",
                "  var count: Int = 0",
                "  fun bump() {",
                "    this.count = this.count + 1",
                "  }",
                "}");

        ScopeBinder.Binding binding = binder.bind("count",
                Optional.of(new ScopeBinder.Declaration(Path.of("src/Holder.kt"), 3)),
                Path.of("src/Holder.kt"));

        assertTrue(binding.boundLines().containsKey(5), "this.count binds to the property on line 3");
        assertEquals(3, binding.boundLines().get(5).line());
    }

    /** Cross-language resolution: a Kotlin caller binding a Java declaration, the mixed-repo reality. */
    @Test
    void aKotlinCallerBindsAJavaDeclaration() throws IOException {
        kt("src/a/Util.java",
                "package a;",
                "class Util {",
                "  static int clamp(int v) { return v; }",
                "}");
        kt("src/a/Caller.kt",
                "package a",
                "import a.Util",
                "class Caller {",
                "  fun run(v: Int): Int {",
                "    return Util.clamp(v)",
                "  }",
                "}");

        ScopeBinder.Binding binding = binder.bind("clamp",
                Optional.of(new ScopeBinder.Declaration(Path.of("src/a/Util.java"), 3)),
                Path.of("src/a/Caller.kt"));

        assertEquals(Optional.of(new ScopeBinder.Declaration(Path.of("src/a/Util.java"), 3)),
                Optional.ofNullable(binding.boundLines().get(5)),
                "Util.kt's import names Util.java; the binder resolves across the language line");
    }

    @Test
    void aMissingFileBindsNothing() {
        assertTrue(binder.bind("anything", Optional.empty(), Path.of("src/Gone.kt")).isEmpty());
    }
}
