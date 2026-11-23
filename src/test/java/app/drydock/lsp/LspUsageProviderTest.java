package app.drydock.lsp;

import app.drydock.review.Provenance;
import app.drydock.review.UsageProvider;
import app.drydock.review.UsageProvider.Usage;
import app.drydock.review.UsageProvider.UsagesAnswer;
import app.drydock.review.UsageProvider.UsagesAnswer.Status;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNull;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §9 composition coverage for {@link LspUsageProvider}: a real
 * {@link JdtServerManager} driven to INDEXED by fake process/client
 * fixtures and a canned fallback — no test launches jdt.ls, and no test
 * sleeps: the manual scheduler runs every duration inline. The wrapper's
 * contract under test is upgrade-only: tier 3 can only add RESOLVED
 * warrant or re-centre on one unique definition, and every miss returns
 * the fallback's answer byte for byte.
 */
@Timeout(60)
class LspUsageProviderTest {

    // The occurrence line (1-based 12) and the declaration it resolves to
    // (Decl.java line 10, LSP line 9). Use.java line 12 reads
    // "        int v = clamp(w);" — "clamp" at UTF-16 column 16.
    private static final String RAW_USE_LINE = "        int v = clamp(w);";
    private static final String CARRIED_USE_LINE = "int v = clamp(w);"; // the seam's stripped text
    private static final int USE_LINE = 12;
    private static final int USE_LSP_LINE = USE_LINE - 1;
    private static final int USE_COLUMN = 16;
    private static final int DECL_LINE = 10; // Decl.java, 1-based
    private static final int DECL_LSP_LINE = DECL_LINE - 1;

    @TempDir
    Path tempDir;

    private final ManualScheduler time = new ManualScheduler();
    private final List<FakeProcess> processes = new CopyOnWriteArrayList<>();
    private Path root;
    private FakeClient client;
    private JdtServerManager manager;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tempDir.resolve("wt"));
        Path jdtHome = tempDir.resolve("jdt");
        Files.createDirectories(jdtHome.resolve("config_mac"));
        Path plugins = Files.createDirectories(jdtHome.resolve("plugins"));
        Files.writeString(plugins.resolve("org.eclipse.equinox.launcher_1.6.900.v20240613-0350.jar"), "launcher");
        write("src/Use.java", List.of(
                "package demo;",
                "",
                "public class Use {",
                "    void run(int w) {",
                "        int first = 0;",
                "        int second = 0;",
                "        int third = 0;",
                "        int fourth = 0;",
                "        int fifth = 0;",
                "        int sixth = 0;",
                "        int seventh = 0;",
                RAW_USE_LINE,
                "        int u = clamp(v);",
                "    }",
                "}"));
        write("src/Use2.java", List.of(
                "package demo;",
                "",
                "class Use2 {",
                "    void run(int w) {",
                "        int clampNow = clamp(w);",
                "    }",
                "}"));
        write("src/Decl.java", List.of(
                "package demo;",
                "",
                "public class Decl {",
                "    int a;",
                "    int b;",
                "    int c;",
                "    int d;",
                "    int e;",
                "",
                "    int clamp(int w) {",
                "        return w;",
                "    }",
                "}"));
        write("src/Other.java", List.of(
                "package demo;",
                "",
                "class Other {",
                "    void run() {",
                "        clamp(0);",
                "    }",
                "}"));
        manager = new JdtServerManager(root, new JdtServerManager.LaunchConfig(jdtHome, null),
                javaHome -> "21.0.1",
                (command, workingDirectory) -> {
                    FakeProcess process = new FakeProcess();
                    processes.add(process);
                    return process;
                },
                (process, settings, listener) -> {
                    client = new FakeClient();
                    client.server = listener;
                    return client;
                },
                time, Files.createDirectories(tempDir.resolve("lsp")));
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    // ------------------------------------------------------------ definition

    @Test
    void aUniqueDefinitionReCentresTheDeclarationWithExactLineConversion() throws Exception {
        driveToIndexed();
        LspUsageProvider provider = provider(fallback(Optional.of(candidate())));

        CompletableFuture<Optional<Usage>> answer = provider.declaration("clamp");

        // The exact request: the raw file line's column, the LSP 0-based line.
        Asked asked = client.asked("textDocument/definition").get(0);
        assertEquals(useUri(), string(paramsOf(asked), "textDocument", "uri"));
        JsonObject position = (JsonObject) paramsOf(asked).get("position");
        assertEquals(USE_LSP_LINE, ((JsonNumber) position.get("line")).asInt());
        assertEquals(USE_COLUMN, ((JsonNumber) position.get("character")).asInt());
        client.respondTo("textDocument/definition",
                new JsonArray(List.of(location(declUri(), DECL_LSP_LINE, 4))));

        assertEquals(Optional.of(new Usage("src/Decl.java", DECL_LINE, "int clamp(int w) {",
                Provenance.RESOLVED, true)), answer.get());
    }

    @Test
    void everyDefinitionMissLeavesTheFallbackCandidateUnchanged() throws Exception {
        assertEquals(Optional.of(candidate()), declarationAfter(JsonNull.INSTANCE));
        assertEquals(Optional.of(candidate()), declarationAfter(new JsonArray(List.of())));
        assertEquals(Optional.of(candidate()), declarationAfter(new JsonArray(List.of( // two answers
                location(declUri(), DECL_LSP_LINE, 4), location(useUri(), USE_LSP_LINE, USE_COLUMN)))));
        assertEquals(Optional.of(candidate()), declarationAfter(new JsonArray(List.of( // outside the root
                location(tempDir.resolve("elsewhere/Decl.java").toUri().toString(), DECL_LSP_LINE, 4)))));
    }

    @Test
    void aFailingOrTimedOutDefinitionQueryLeavesTheFallbackCandidate() throws Exception {
        driveToIndexed();
        LspUsageProvider provider = provider(fallback(Optional.of(candidate())));

        CompletableFuture<Optional<Usage>> answer = provider.declaration("clamp");
        client.failTo("textDocument/definition", new IOException("query timeout"));

        assertEquals(Optional.of(candidate()), answer.get());
    }

    @Test
    void anUnindexedManagerLeavesTheFallbackCandidateAndNeverWaits() throws Exception {
        LspUsageProvider provider = provider(fallback(Optional.of(candidate())));

        assertEquals(Optional.of(candidate()), provider.declaration("clamp").get());
        assertEquals(1, processes.size(), "the query started the server; the answer never waited for it");
    }

    @Test
    void aNonJavaSymbolIsInapplicableAndNeverQueriesTheServer() throws Exception {
        driveToIndexed();
        Usage kotlin = new Usage("src/Use.kt", 3, "fun clamp()", Provenance.MEASURED, false);
        List<Usage> rows = List.of(usage("src/Use.kt", 3, "fun clamp()", Provenance.MEASURED));
        LspUsageProvider provider = provider(fallback(Optional.of(kotlin), rows));

        assertEquals(Optional.of(kotlin), provider.declaration("clamp").get());
        UsagesAnswer answer = provider.usagesAnswer("clamp").get();

        assertEquals(Status.ANSWERED, answer.status());
        assertEquals(rows, answer.usages());
        assertTrue(client.asked("textDocument/definition").isEmpty(), "a Java-only server is never asked");
        assertTrue(client.asked("textDocument/references").isEmpty(), "a Java-only server is never asked");
    }

    @Test
    void theColumnComesFromTheRawFileLineNotTheStrippedSeamText() throws Exception {
        driveToIndexed();
        // Use2.java line 5: "        int clampNow = clamp(w);" — the first "clamp"
        // substring is inside "clampNow"; the whole word starts at column 23
        // (the carried, stripped text would say 15).
        Usage candidate = new Usage("src/Use2.java", 5, "int clampNow = clamp(w);",
                Provenance.MEASURED, false);
        LspUsageProvider provider = provider(fallback(Optional.of(candidate)));

        CompletableFuture<Optional<Usage>> answer = provider.declaration("clamp");

        Asked asked = client.asked("textDocument/definition").get(0);
        JsonObject position = (JsonObject) paramsOf(asked).get("position");
        assertEquals(4, ((JsonNumber) position.get("line")).asInt());
        assertEquals(23, ((JsonNumber) position.get("character")).asInt());
        client.respondTo("textDocument/definition",
                new JsonArray(List.of(location(declUri(), DECL_LSP_LINE, 4))));
        assertEquals(Optional.of(new Usage("src/Decl.java", DECL_LINE, "int clamp(int w) {",
                Provenance.RESOLVED, true)), answer.get());
    }

    @Test
    void identifierColumnFindsTheFirstWholeWordOccurrence() {
        assertEquals(OptionalInt.of(16), LspUsageProvider.identifierColumn(RAW_USE_LINE, "clamp"));
        assertEquals(OptionalInt.of(8), LspUsageProvider.identifierColumn(CARRIED_USE_LINE, "clamp"));
        assertEquals(OptionalInt.empty(), LspUsageProvider.identifierColumn("int clampNow = 0;", "clamp"));
        assertEquals(OptionalInt.of(8), LspUsageProvider.identifierColumn("clampIt(clamp);", "clamp"));
        assertEquals(OptionalInt.empty(), LspUsageProvider.identifierColumn("", "clamp"));
    }

    @Test
    void theCarriedTextColumnIsTheLastResortWhenTheRawLineCannotBeRead() throws Exception {
        driveToIndexed();
        // src/Missing.java does not exist: the raw line is unreadable, the
        // definition may still be asked at the carried text's column 8 —
        // and the answer must then pass the whole-word guard.
        Usage candidate = new Usage("src/Missing.java", 4, CARRIED_USE_LINE, Provenance.MEASURED, false);
        LspUsageProvider provider = provider(fallback(Optional.of(candidate)));

        CompletableFuture<Optional<Usage>> answer = provider.declaration("clamp");

        Asked asked = client.asked("textDocument/definition").get(0);
        JsonObject position = (JsonObject) paramsOf(asked).get("position");
        assertEquals(3, ((JsonNumber) position.get("line")).asInt());
        assertEquals(8, ((JsonNumber) position.get("character")).asInt());
        client.respondTo("textDocument/definition",
                new JsonArray(List.of(location(declUri(), DECL_LSP_LINE, 4))));
        assertEquals(Optional.of(new Usage("src/Decl.java", DECL_LINE, "int clamp(int w) {",
                Provenance.RESOLVED, true)), answer.get());
    }

    @Test
    void aDefinitionWhoseLineDoesNotNameTheSymbolIsRejected() throws Exception {
        // The guard: a definition that resolved a different token names a line
        // without the symbol ("    int a;") — a miss, never a wrong answer.
        Optional<Usage> answer = declarationAfter(
                new JsonArray(List.of(location(declUri(), 3, 4))));

        assertEquals(Optional.of(candidate()), answer);
    }

    @Test
    void aStaleCandidateLineQueriesNothing() throws Exception {
        driveToIndexed();
        // The peek's line 5 no longer names the symbol on disk: no position,
        // no query, the fallback stands.
        Usage stale = new Usage("src/Use.java", 5, CARRIED_USE_LINE, Provenance.MEASURED, false);
        List<Usage> rows = List.of(usage("src/Use.java", 5, CARRIED_USE_LINE, Provenance.MEASURED));
        LspUsageProvider provider = provider(fallback(Optional.of(stale), rows));

        assertEquals(Optional.of(stale), provider.declaration("clamp").get());
        assertEquals(rows, provider.usages("clamp").get());
        assertTrue(client.asked("textDocument/definition").isEmpty());
        assertTrue(client.asked("textDocument/references").isEmpty());
    }

    // ------------------------------------------------------------ references

    @Test
    void referencesAreAskedAtTheDeclarationPositionWithIncludeDeclaration() throws Exception {
        driveToIndexed();
        LspUsageProvider provider = provider(fallback(Optional.of(candidate()),
                usage("src/Use.java", USE_LINE, CARRIED_USE_LINE, Provenance.MEASURED)));

        CompletableFuture<UsagesAnswer> answer = provider.usagesAnswer("clamp");

        Asked asked = client.asked("textDocument/references").get(0);
        assertEquals(useUri(), string(paramsOf(asked), "textDocument", "uri"));
        JsonObject position = (JsonObject) paramsOf(asked).get("position");
        assertEquals(USE_LSP_LINE, ((JsonNumber) position.get("line")).asInt());
        assertEquals(USE_COLUMN, ((JsonNumber) position.get("character")).asInt());
        JsonObject context = (JsonObject) paramsOf(asked).get("context");
        assertEquals(new JsonBoolean(true), context.get("includeDeclaration"));
        client.respondTo("textDocument/references",
                new JsonArray(List.of(location(useUri(), USE_LSP_LINE, USE_COLUMN))));

        UsagesAnswer result = answer.get();
        assertEquals(Status.ANSWERED, result.status());
        assertEquals(List.of(new Usage("src/Use.java", USE_LINE, CARRIED_USE_LINE,
                Provenance.RESOLVED, false)), result.usages());
    }

    @Test
    void confirmedRowsAreUpgradedAndUnmatchedRowsKeepProvenanceAndOrder() throws Exception {
        driveToIndexed();
        List<Usage> rows = List.of(
                usage("src/Use.java", USE_LINE, CARRIED_USE_LINE, Provenance.MEASURED),
                usage("src/Use.java", 13, "int u = clamp(v);", Provenance.SCOPED),
                usage("src/Other.java", 5, "clamp(0);", Provenance.MEASURED));
        LspUsageProvider provider = provider(fallback(Optional.of(candidate()), rows));

        CompletableFuture<UsagesAnswer> answer = provider.usagesAnswer("clamp");
        // The declaration location matches no fallback row and must add none;
        // Use.java line 12 is confirmed; lines 13 and Other.java are not.
        client.respondTo("textDocument/references", new JsonArray(List.of(
                location(declUri(), DECL_LSP_LINE, 4),
                location(useUri(), USE_LSP_LINE, USE_COLUMN))));

        UsagesAnswer result = answer.get();
        assertEquals(Status.ANSWERED, result.status());
        assertEquals(List.of(
                new Usage("src/Use.java", USE_LINE, CARRIED_USE_LINE, Provenance.RESOLVED, false),
                usage("src/Use.java", 13, "int u = clamp(v);", Provenance.SCOPED),
                usage("src/Other.java", 5, "clamp(0);", Provenance.MEASURED)), result.usages());
    }

    @Test
    void aScopedRowIsUpgradedOnlyWhenTheServerConfirmsIt() throws Exception {
        driveToIndexed();
        List<Usage> rows = List.of(
                usage("src/Use.java", USE_LINE, CARRIED_USE_LINE, Provenance.MEASURED),
                usage("src/Use.java", 13, "int u = clamp(v);", Provenance.SCOPED));
        LspUsageProvider provider = provider(fallback(Optional.of(candidate()), rows));

        CompletableFuture<UsagesAnswer> answer = provider.usagesAnswer("clamp");
        client.respondTo("textDocument/references", new JsonArray(List.of(
                location(useUri(), USE_LSP_LINE, USE_COLUMN),
                location(useUri(), 12, 8)))); // Use.java line 13 (1-based)

        UsagesAnswer result = answer.get();
        assertEquals(List.of(
                new Usage("src/Use.java", USE_LINE, CARRIED_USE_LINE, Provenance.RESOLVED, false),
                new Usage("src/Use.java", 13, "int u = clamp(v);", Provenance.RESOLVED, false)),
                result.usages());
    }

    @Test
    void nullAndEmptyReferenceListsAreAnswersThatEraseNothing() throws Exception {
        List<Usage> rows = List.of(
                usage("src/Use.java", USE_LINE, CARRIED_USE_LINE, Provenance.MEASURED),
                usage("src/Other.java", 5, "clamp(0);", Provenance.MEASURED));
        assertEquals(rows, rowsAfter(JsonNull.INSTANCE));
        assertEquals(rows, rowsAfter(new JsonArray(List.of())));
    }

    @Test
    void aFailingOrTimedOutReferencesQueryLeavesTheFallbackRowsAnswered() throws Exception {
        driveToIndexed();
        List<Usage> rows = List.of(
                usage("src/Use.java", USE_LINE, CARRIED_USE_LINE, Provenance.MEASURED));
        LspUsageProvider provider = provider(fallback(Optional.of(candidate()), rows));

        CompletableFuture<UsagesAnswer> answer = provider.usagesAnswer("clamp");
        client.failTo("textDocument/references", new IOException("query timeout"));

        UsagesAnswer result = answer.get();
        assertEquals(Status.ANSWERED, result.status());
        assertEquals(rows, result.usages());
    }

    @Test
    void anUnavailableManagerReturnsTheFallbackAnswerAnswered() throws Exception {
        List<Usage> rows = List.of(
                usage("src/Use.java", USE_LINE, CARRIED_USE_LINE, Provenance.MEASURED),
                usage("src/Other.java", 5, "clamp(0);", Provenance.SCOPED));
        LspUsageProvider provider = provider(fallback(Optional.of(candidate()), rows));

        UsagesAnswer answer = provider.usagesAnswer("clamp").get();

        assertEquals(Status.ANSWERED, answer.status());
        assertEquals(rows, answer.usages());
        assertEquals(1, processes.size(), "the query started the server; the answer never waited for it");
    }

    @Test
    void referencesAreNotAskedFromAnUnverifiablePosition() throws Exception {
        driveToIndexed();
        // The raw line cannot be read (no such file): a references query has
        // no read-back line to guard it, so the position is never sent.
        Usage candidate = new Usage("src/Missing.java", 4, CARRIED_USE_LINE, Provenance.MEASURED, false);
        List<Usage> rows = List.of(usage("src/Missing.java", 4, CARRIED_USE_LINE, Provenance.MEASURED));
        LspUsageProvider provider = provider(fallback(Optional.of(candidate), rows));

        UsagesAnswer answer = provider.usagesAnswer("clamp").get();

        assertEquals(Status.ANSWERED, answer.status());
        assertEquals(rows, answer.usages());
        assertTrue(client.asked("textDocument/references").isEmpty());
    }

    @Test
    void usagesDelegatesThroughTheComposedAnswer() throws Exception {
        driveToIndexed();
        List<Usage> rows = List.of(
                usage("src/Use.java", USE_LINE, CARRIED_USE_LINE, Provenance.MEASURED),
                usage("src/Other.java", 5, "clamp(0);", Provenance.MEASURED));
        LspUsageProvider provider = provider(fallback(Optional.of(candidate()), rows));

        CompletableFuture<List<Usage>> usages = provider.usages("clamp");
        client.respondTo("textDocument/references",
                new JsonArray(List.of(location(useUri(), USE_LSP_LINE, USE_COLUMN))));

        assertEquals(List.of(
                new Usage("src/Use.java", USE_LINE, CARRIED_USE_LINE, Provenance.RESOLVED, false),
                usage("src/Other.java", 5, "clamp(0);", Provenance.MEASURED)), usages.get());
    }

    // ------------------------------------------------- symlink-safe containment

    /** A symlink to the worktree; the test is skipped where links cannot be made. */
    private Path linkToRoot() {
        Path link = tempDir.resolve("linkwt");
        try {
            return Files.createSymbolicLink(link, root);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.abort("symbolic links unavailable: " + e);
            throw new AssertionError(e); // unreachable
        }
    }

    @Test
    void aSymlinkedRootAcceptsRealPathDefinitions() throws Exception {
        driveToIndexed();
        Path link = linkToRoot();
        LspUsageProvider provider = new LspUsageProvider(fallback(Optional.of(candidate())), link, manager,
                Runnable::run);

        CompletableFuture<Optional<Usage>> answer = provider.declaration("clamp");
        // The server answers under the real (target) form, not the link form.
        client.respondTo("textDocument/definition", new JsonArray(List.of(
                location(declFile().toRealPath().toUri().toString(), DECL_LSP_LINE, 4))));

        assertEquals(Optional.of(new Usage("src/Decl.java", DECL_LINE, "int clamp(int w) {",
                Provenance.RESOLVED, true)), answer.get(), "worktree-relative, never a ../ path");
    }

    @Test
    void aSymlinkedRootUpgradesRealPathReferences() throws Exception {
        driveToIndexed();
        Path link = linkToRoot();
        List<Usage> rows = List.of(
                usage("src/Use.java", USE_LINE, CARRIED_USE_LINE, Provenance.MEASURED),
                usage("src/Other.java", 5, "clamp(0);", Provenance.MEASURED));
        LspUsageProvider provider = new LspUsageProvider(fallback(Optional.of(candidate()), rows), link,
                manager, Runnable::run);

        CompletableFuture<UsagesAnswer> answer = provider.usagesAnswer("clamp");
        client.respondTo("textDocument/references", new JsonArray(List.of(
                location(useFile().toRealPath().toUri().toString(), USE_LSP_LINE, USE_COLUMN))));

        assertEquals(List.of(
                new Usage("src/Use.java", USE_LINE, CARRIED_USE_LINE, Provenance.RESOLVED, false),
                usage("src/Other.java", 5, "clamp(0);", Provenance.MEASURED)), answer.get().usages());
    }

    @Test
    void aLinkFormUriUnderARealRootIsAccepted() throws Exception {
        driveToIndexed();
        Path link = linkToRoot();
        LspUsageProvider provider = provider(fallback(Optional.of(candidate())));

        CompletableFuture<Optional<Usage>> answer = provider.declaration("clamp");
        client.respondTo("textDocument/definition", new JsonArray(List.of(
                location(link.resolve("src/Decl.java").toUri().toString(), DECL_LSP_LINE, 4))));

        assertEquals(Optional.of(new Usage("src/Decl.java", DECL_LINE, "int clamp(int w) {",
                Provenance.RESOLVED, true)), answer.get());
    }

    @Test
    void aPathOutsideTheRootInBothFormsIsAMiss() throws Exception {
        // A real, readable sibling that shares the root's name as a prefix.
        Path sibling = Files.createDirectories(tempDir.resolve("wt-sibling/src"));
        Files.write(sibling.resolve("Decl.java"), Files.readAllLines(declFile()));
        assertEquals(Optional.of(candidate()), declarationAfter(new JsonArray(List.of(
                location(sibling.resolve("Decl.java").toUri().toString(), DECL_LSP_LINE, 4)))));
        assertEquals(Optional.of(candidate()), declarationAfter(new JsonArray(List.of(
                location(sibling.resolve("Decl.java").toRealPath().toUri().toString(), DECL_LSP_LINE, 4)))));
    }

    @Test
    void canonicalisationRunsOnTheFileReaderExecutor() throws Exception {
        driveToIndexed();
        AtomicInteger tasks = new AtomicInteger();
        Executor counting = task -> {
            tasks.incrementAndGet();
            task.run();
        };
        LspUsageProvider provider = new LspUsageProvider(fallback(Optional.of(candidate()),
                usage("src/Use.java", USE_LINE, CARRIED_USE_LINE, Provenance.MEASURED)), root, manager, counting);

        CompletableFuture<UsagesAnswer> answer = provider.usagesAnswer("clamp");
        int beforeAnswer = tasks.get();
        client.respondTo("textDocument/references",
                new JsonArray(List.of(location(useUri(), USE_LSP_LINE, USE_COLUMN))));
        answer.get();

        assertTrue(tasks.get() > beforeAnswer,
                "the location containment (file I/O) hops to fileReader, never the client's reader");
    }

    // ------------------------------------------------------ declaration memo

    /** A fallback counting its declaration scans (and the symbols scanned) and usagesAnswer calls. */
    private static final class CountingFallback implements UsageProvider {
        final List<String> declarationScans = new CopyOnWriteArrayList<>();
        final AtomicInteger usagesAnswers = new AtomicInteger();
        private final Supplier<CompletableFuture<Optional<Usage>>> declaration;
        private final List<Usage> rows;

        CountingFallback(Supplier<CompletableFuture<Optional<Usage>>> declaration, List<Usage> rows) {
            this.declaration = declaration;
            this.rows = rows;
        }

        @Override public CompletableFuture<Optional<Usage>> declaration(String symbol) {
            declarationScans.add(symbol);
            return declaration.get();
        }

        @Override public CompletableFuture<List<Usage>> usages(String symbol) {
            return CompletableFuture.completedFuture(rows);
        }

        @Override public CompletableFuture<UsagesAnswer> usagesAnswer(String symbol) {
            usagesAnswers.incrementAndGet();
            return CompletableFuture.completedFuture(new UsagesAnswer(rows, Status.ANSWERED));
        }
    }

    private static CountingFallback countingFallback() {
        return new CountingFallback(() -> CompletableFuture.completedFuture(Optional.of(candidate())),
                List.of(candidate()));
    }

    @Test
    void oneFallbackDeclarationScanPerPeek() throws Exception {
        CountingFallback fallback = countingFallback();
        LspUsageProvider provider = provider(fallback);

        assertEquals(Optional.of(candidate()), provider.declaration("clamp").get());
        assertEquals(List.of(candidate()), provider.usagesAnswer("clamp").get().usages());

        assertEquals(List.of("clamp"), fallback.declarationScans, "one declaration scan per peek");
        assertEquals(1, fallback.usagesAnswers.get());
    }

    @Test
    void aDifferentSymbolRescansAndNeverGetsAnotherSymbolsDeclaration() throws Exception {
        CountingFallback fallback = countingFallback();
        LspUsageProvider provider = provider(fallback);

        provider.declaration("a").get();
        provider.usagesAnswer("b").get();
        assertEquals(List.of("a", "b"), fallback.declarationScans, "b never takes a's declaration");
        provider.usagesAnswer("a").get();
        assertEquals(List.of("a", "b", "a"), fallback.declarationScans, "a replaced slot rescans");
        // Take-once: a consumed slot is not served twice.
        provider.declaration("c").get();
        provider.usagesAnswer("c").get();
        provider.usagesAnswer("c").get();
        assertEquals(List.of("a", "b", "a", "c", "c"), fallback.declarationScans);
    }

    @Test
    void aFailedFallbackDeclarationPropagatesAsToday() {
        Supplier<CompletableFuture<Optional<Usage>>> failing =
                () -> CompletableFuture.failedFuture(new IllegalStateException("scan failed"));
        CountingFallback shared = new CountingFallback(failing, List.of(candidate()));
        LspUsageProvider provider = provider(shared);
        CompletableFuture<Optional<Usage>> declaration = provider.declaration("clamp");
        CompletableFuture<UsagesAnswer> sharedUsages = provider.usagesAnswer("clamp");

        // The same composition with no shared scan: the outcome must not differ.
        CompletableFuture<UsagesAnswer> freshUsages =
                provider(new CountingFallback(failing, List.of(candidate()))).usagesAnswer("clamp");

        assertTrue(declaration.isCompletedExceptionally());
        assertEquals(freshUsages.isCompletedExceptionally(), sharedUsages.isCompletedExceptionally());
        assertEquals(List.of("clamp"), shared.declarationScans, "the failed scan is shared, not repeated");
    }

    // ------------------------------------------------ per-query manager lookup

    @Test
    void aRetiredManagerIsNotUsedByTheNextQuery() throws Exception {
        driveToIndexed();
        FakeClient[] secondClient = new FakeClient[1];
        JdtServerManager second = new JdtServerManager(root,
                new JdtServerManager.LaunchConfig(tempDir.resolve("jdt"), null),
                javaHome -> "21.0.1",
                (command, workingDirectory) -> new FakeProcess(),
                (process, settings, listener) -> {
                    secondClient[0] = new FakeClient();
                    secondClient[0].server = listener;
                    return secondClient[0];
                },
                time, Files.createDirectories(tempDir.resolve("lsp2")));
        try {
            assertTrue(second.definition(useFile(), 0, 0).isEmpty());
            secondClient[0].respondTo("initialize", JsonNull.INSTANCE);
            secondClient[0].notifyServer("language/status", obj("type", str("ServiceReady")));
            secondClient[0].notifyServer("language/progressReport", obj(
                    "taskType", str("Importing Gradle project"), "id", str("s1"), "status", str("Started")));
            secondClient[0].notifyServer("language/progressReport", obj(
                    "taskType", str("Importing Gradle project"), "id", str("s1"), "status", str("Completed")));
            assertEquals(JdtServerManager.Readiness.INDEXED, second.state());
            FakeClient firstClient = client;

            List<Usage> rows = List.of(usage("src/Use.java", USE_LINE, CARRIED_USE_LINE, Provenance.MEASURED));
            AtomicInteger lookups = new AtomicInteger();
            List<Optional<JdtServerManager>> answers = List.of(Optional.of(manager), Optional.of(second),
                    Optional.empty());
            LspUsageProvider provider = new LspUsageProvider(fallback(Optional.of(candidate()), rows), root,
                    () -> answers.get(Math.min(lookups.getAndIncrement(), answers.size() - 1)), Runnable::run);

            CompletableFuture<UsagesAnswer> one = provider.usagesAnswer("clamp");
            firstClient.respondTo("textDocument/references", new JsonArray(List.of()));
            one.get();
            CompletableFuture<UsagesAnswer> two = provider.usagesAnswer("clamp");
            secondClient[0].respondTo("textDocument/references", new JsonArray(List.of()));
            two.get();
            assertEquals(1, firstClient.asked("textDocument/references").size(), "the retired one is not asked");
            assertEquals(1, secondClient[0].asked("textDocument/references").size());

            // An empty lookup (tier off): the exact fallback answer, and no request anywhere.
            UsagesAnswer off = provider.usagesAnswer("clamp").get();
            assertEquals(new UsagesAnswer(rows, Status.ANSWERED), off);
            assertEquals(Optional.of(candidate()), provider.declaration("clamp").get());
            assertEquals(1, firstClient.asked("textDocument/references").size());
            assertEquals(1, secondClient[0].asked("textDocument/references").size());
            assertTrue(secondClient[0].asked("textDocument/definition").isEmpty());
            assertEquals(3 + 1, lookups.get(), "one lookup per query");
        } finally {
            second.close();
        }
    }

    // ------------------------------------------------------------- fixtures

    private static final class ManualScheduler implements JdtServerManager.Scheduler {
        private record Task(long deadline, Runnable runnable) { }

        private long nowMillis;
        private final List<Task> tasks = new ArrayList<>();

        @Override public Instant now() { return Instant.ofEpochMilli(nowMillis); }

        @Override public void execute(Runnable task) { task.run(); }

        @Override public Timer afterMillis(long delayMillis, Runnable task) {
            Task pending = new Task(nowMillis + delayMillis, task);
            tasks.add(pending);
            return () -> tasks.remove(pending);
        }

        void advanceMillis(long millis) {
            nowMillis += millis;
            while (true) {
                Task due = tasks.stream().filter(t -> t.deadline() <= nowMillis)
                        .min(Comparator.comparingLong(Task::deadline)).orElse(null);
                if (due == null) return;
                tasks.remove(due);
                due.runnable().run();
            }
        }
    }

    private static final class FakeProcess implements JdtServerManager.ServerProcess {
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();

        @Override public InputStream stdout() { return InputStream.nullInputStream(); }
        @Override public OutputStream stdin() { return OutputStream.nullOutputStream(); }
        @Override public CompletableFuture<Integer> exitFuture() { return exit; }

        @Override public void close() { exit.complete(0); }
    }

    private record Asked(String method, JsonValue params, CompletableFuture<JsonValue> response) { }

    private static final class FakeClient implements JdtServerManager.ClientConnection {
        final List<Asked> requests = new CopyOnWriteArrayList<>();
        JdtServerManager.ServerNotifications server;

        @Override public CompletableFuture<JsonValue> request(String method, JsonValue params) {
            CompletableFuture<JsonValue> response = new CompletableFuture<>();
            requests.add(new Asked(method, params, response));
            if ("shutdown".equals(method)) {
                response.complete(JsonNull.INSTANCE);
            }
            return response;
        }

        @Override public void notification(String method, JsonValue params) { }

        @Override public void close() { }

        void notifyServer(String method, JsonValue params) {
            server.onNotification(method, params);
        }

        void respondTo(String method, JsonValue result) {
            requests.stream()
                    .filter(a -> a.method().equals(method) && !a.response().isDone())
                    .findFirst()
                    .ifPresentOrElse(a -> a.response().complete(result),
                            () -> { throw new IllegalStateException("no pending " + method); });
        }

        void failTo(String method, Throwable error) {
            requests.stream()
                    .filter(a -> a.method().equals(method) && !a.response().isDone())
                    .findFirst()
                    .ifPresentOrElse(a -> a.response().completeExceptionally(error),
                            () -> { throw new IllegalStateException("no pending " + method); });
        }

        List<Asked> asked(String method) {
            return requests.stream().filter(a -> a.method().equals(method)).toList();
        }
    }

    // ----------------------------------------------------------- driving aids

    private void write(String relative, List<String> lines) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, lines);
    }

    private void driveToIndexed() {
        if (manager.state() == JdtServerManager.Readiness.INDEXED) {
            return; // already driven by an earlier helper call in this test
        }
        assertTrue(manager.definition(root.resolve("src/Use.java"), 0, 0).isEmpty(),
                "a query outside INDEXED falls through immediately");
        client.respondTo("initialize", JsonNull.INSTANCE);
        client.notifyServer("language/status", obj("type", str("ServiceReady")));
        client.notifyServer("language/progressReport", obj(
                "taskType", str("Importing Gradle project"), "id", str("t1"), "status", str("Started")));
        client.notifyServer("language/progressReport", obj(
                "taskType", str("Importing Gradle project"), "id", str("t1"), "status", str("Completed")));
        assertEquals(JdtServerManager.Readiness.INDEXED, manager.state());
    }

    private LspUsageProvider provider(UsageProvider fallback) {
        return new LspUsageProvider(fallback, root, manager, Runnable::run);
    }

    private static UsageProvider fallback(Optional<Usage> declarationRow, Usage... rows) {
        return fallback(declarationRow, List.of(rows));
    }

    private static UsageProvider fallback(Optional<Usage> declarationRow, List<Usage> rows) {
        return new UsageProvider() {
            @Override public CompletableFuture<Optional<Usage>> declaration(String symbol) {
                return CompletableFuture.completedFuture(declarationRow);
            }

            @Override public CompletableFuture<List<Usage>> usages(String symbol) {
                return CompletableFuture.completedFuture(rows);
            }
        };
    }

    private static Usage candidate() {
        return usage("src/Use.java", USE_LINE, CARRIED_USE_LINE, Provenance.MEASURED);
    }

    private static Usage usage(String file, int line, String text, Provenance provenance) {
        return new Usage(file, line, text, provenance, false);
    }

    private Path useFile() { return root.resolve("src/Use.java"); }
    private Path declFile() { return root.resolve("src/Decl.java"); }
    private String useUri() { return useFile().toUri().toString(); }
    private String declUri() { return declFile().toUri().toString(); }

    private Optional<Usage> declarationAfter(JsonValue definitionAnswer) throws Exception {
        driveToIndexed();
        LspUsageProvider provider = provider(fallback(Optional.of(candidate())));
        CompletableFuture<Optional<Usage>> answer = provider.declaration("clamp");
        client.respondTo("textDocument/definition", definitionAnswer);
        return answer.get();
    }

    private List<Usage> rowsAfter(JsonValue referencesAnswer) throws Exception {
        driveToIndexed();
        List<Usage> rows = List.of(
                usage("src/Use.java", USE_LINE, CARRIED_USE_LINE, Provenance.MEASURED),
                usage("src/Other.java", 5, "clamp(0);", Provenance.MEASURED));
        LspUsageProvider provider = provider(fallback(Optional.of(candidate()), rows));
        CompletableFuture<UsagesAnswer> answer = provider.usagesAnswer("clamp");
        client.respondTo("textDocument/references", referencesAnswer);
        UsagesAnswer result = answer.get();
        assertEquals(Status.ANSWERED, result.status());
        return result.usages();
    }

    // ------------------------------------------------------------------- json

    private static JsonObject location(String uri, int line, int character) {
        return obj("uri", str(uri),
                "range", obj("start", obj("line", num(line), "character", num(character))));
    }

    private static JsonObject paramsOf(Asked asked) {
        return (JsonObject) asked.params();
    }

    private static String string(JsonObject params, String container, String key) {
        JsonObject outer = (JsonObject) params.get(container);
        return ((JsonString) outer.get(key)).value();
    }

    private static JsonObject obj(Object... members) {
        LinkedHashMap<String, JsonValue> map = new LinkedHashMap<>();
        for (int i = 0; i < members.length; i += 2) {
            map.put((String) members[i], (JsonValue) members[i + 1]);
        }
        return new JsonObject(map);
    }

    private static JsonString str(String value) { return new JsonString(value); }
    private static JsonNumber num(long value) { return JsonNumber.of(value); }
}
