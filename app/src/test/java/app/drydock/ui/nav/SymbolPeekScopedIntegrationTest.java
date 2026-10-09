package app.drydock.ui.nav;

import app.drydock.search.SessionSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scoped tier end to end: a real search over a real little repo, the
 * binder over its parse trees, and a peek whose occurrences carry their
 * tier. The fixture is the case the tier exists for -- a same-named member
 * on an unrelated class that the lexical tier cannot tell from the real
 * thing.
 */
class SymbolPeekScopedIntegrationTest {

    @TempDir
    Path root;
    private SessionSearchService search;
    private SymbolPeekService service;

    @BeforeEach
    void setUp() throws IOException {
        write("src/a/Real.java",
                "package a;",
                "class Real {",
                "  void target() {}",
                "}");
        write("src/a/Other.java",
                "package a;",
                "class Other {",
                "  void target() {}",
                "}");
        write("src/a/Caller.java",
                "package a;",
                "class Caller {",
                "  void run(Other other) {",
                "    other.target();",
                "  }",
                "}");
        search = new SessionSearchService();
        service = new SymbolPeekService(root, search);
    }

    @AfterEach
    void tearDown() {
        search.close();
    }

    private void write(String relative, String... lines) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n");
    }

    @Test
    void thePeekBindsRealReferencesAndRefusesTheSameNamedStranger() throws Exception {
        Optional<SymbolPeek> peek = service.peek("target", Map.of()).get();

        assertTrue(peek.isPresent(), "the search finds the name in three files");
        SymbolPeek card = peek.orElseThrow();
        long bound = card.occurrences().stream().filter(SymbolPeek.Occurrence::bound).count();

        assertEquals(1, bound,
                "exactly one occurrence is a real reference: the call through a typed receiver");
        SymbolPeek.Occurrence theCall = card.occurrences().stream()
                .filter(SymbolPeek.Occurrence::bound).findFirst().orElseThrow();
        assertEquals("src/a/Caller.java", theCall.relativePath().toString());
        assertEquals(4, theCall.line());

        assertTrue(card.resolvedDeclaration(),
                "the declaration is confirmed by binding, whichever file the tie-break centred on");
        assertTrue(card.declarationScopeBound(),
                "the card says the declaration is scope-bound, not score-guessed");
        assertTrue(card.title().contains("scope-bound declaration"), card.title());
    }

    /**
     * A name with no Java around it (prose, or a non-Java repo) keeps the
     * lexical answer: the tier degrades by saying nothing, never by error.
     */
    @Test
    void aNonJavaSymbolKeepsTheLexicalAnswer() throws Exception {
        write("notes/readme.txt", "the target is lexical here");

        Optional<SymbolPeek> peek = service.peek("lexical", Map.of()).get();

        assertTrue(peek.isPresent());
        assertTrue(peek.orElseThrow().occurrences().stream().noneMatch(SymbolPeek.Occurrence::bound),
                "no grammar, no binding claimed");
        assertTrue(peek.orElseThrow().occurrences().stream().anyMatch(occurrence ->
                occurrence.relativePath().toString().equals("notes/readme.txt")));
    }
}
