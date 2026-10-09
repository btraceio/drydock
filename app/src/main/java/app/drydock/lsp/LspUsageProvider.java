package app.drydock.lsp;

import app.drydock.review.Provenance;
import app.drydock.review.UsageProvider;
import app.drydock.review.UsageProvider.Usage;
import app.drydock.review.UsageProvider.UsagesAnswer;
import app.drydock.review.UsageProvider.UsagesAnswer.Status;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * The tier-3 composition at the usage seam (spec docs/superpowers/specs/
 * 2026-10-08-lsp-tier3-usage-resolution.md, §7): an upgrade-only wrapper
 * over an injected lower-tier {@link UsageProvider} (the lexical one) and
 * the per-worktree {@link JdtServerManager}.
 *
 * <p>Constraints, all from §7 unless marked:</p>
 * <ul>
 * <li><b>Upgrade-only.</b> Tier 3 can only add {@link Provenance#RESOLVED}
 * warrant to rows the fallback already produced, or re-centre the
 * declaration on one unique definition. Every miss — no server, not
 * indexed, null/zero/multiple/outside-root results, timeouts, errors —
 * returns the fallback's answer unchanged (the same rows, text, order and
 * provenance, logged at DEBUG, never WARNING). The fallback's own
 * exceptional future propagates as-is: it was already the contract.</li>
 * <li><b>Status.</b> The composed answer is always {@link Status#ANSWERED}:
 * the lexical floor is a valid answer even when the server did not
 * participate, and a server's null or empty references list is a valid
 * answer (zero upgrades, nothing erased). {@link Status#UNAVAILABLE} and
 * {@link Status#INAPPLICABLE} exist for tiers without a floor; this
 * composition never reports them.</li>
 * <li><b>Lines.</b> LSP lines are 0-based, seam lines are 1-based; the
 * conversion lives in this class alone.</li>
 * <li><b>Characters.</b> LSP characters are UTF-16 code units; a Java
 * {@code String} index is one, so the column is the index of the first
 * whole-word occurrence. Identifiers are assumed ASCII, as §7 does.</li>
 * <li><b>Column source (coordinator decision on T4b, 2026-10-08).</b> The
 * seam's carried text is whitespace-stripped (SymbolPeekService strips
 * candidate and occurrence lines), so a column computed from it can land
 * on a different token in the raw line and make the server answer for the
 * wrong symbol. The column is therefore derived from the <em>raw file
 * line</em> at the candidate's line — one bounded read per query, nothing
 * cached — and only falls back to the carried text when the file cannot
 * be read; a definition answer accepted from such an unverified position
 * must pass the whole-word guard below. A references query is never sent
 * from an unverified position: it has no read-back line to guard.</li>
 * <li><b>Guard.</b> A definition answer is accepted only when the
 * declaration line read back contains the symbol as a whole word;
 * otherwise it is a miss and the fallback candidate stands.</li>
 * <li><b>Fan-in untouched.</b> Out-of-diff fan-in stays tier 2 (§7);
 * this class never touches it.</li>
 * </ul>
 *
 * <p>FX-free and thread-safe: every step is a {@link CompletableFuture};
 * the wrapper's own file reads and manager calls hop onto the injected
 * {@code fileReader} executor, so nothing blocks the caller's thread.</p>
 */
public final class LspUsageProvider implements UsageProvider, AutoCloseable {

    private static final Logger LOG = System.getLogger(LspUsageProvider.class.getName());

    /** Bounds every read of a source file (line-scan to the wanted line, no caching). */
    private static final long MAX_READ_BYTES = 8L * 1024 * 1024;

    private final UsageProvider fallback;
    private final Path root;
    private final JdtServerManager manager;
    private final Executor fileReader;
    private final ExecutorService ownedReader;

    /**
     * Production constructor; the file reads run on a per-instance
     * virtual-thread executor {@link #close()} releases.
     */
    public LspUsageProvider(UsageProvider fallback, Path worktreeRoot, JdtServerManager manager) {
        this(fallback, worktreeRoot, manager, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /** Full-seam constructor; the injected executor is not owned and never released here. */
    public LspUsageProvider(UsageProvider fallback, Path worktreeRoot, JdtServerManager manager,
                            Executor fileReader) {
        this(fallback, worktreeRoot, manager, fileReader, false);
    }

    private LspUsageProvider(UsageProvider fallback, Path worktreeRoot, JdtServerManager manager,
                             Executor fileReader, boolean owned) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.root = Objects.requireNonNull(worktreeRoot, "worktreeRoot").toAbsolutePath().normalize();
        this.manager = Objects.requireNonNull(manager, "manager");
        this.fileReader = Objects.requireNonNull(fileReader, "fileReader");
        this.ownedReader = owned ? (ExecutorService) fileReader : null;
    }

    /** Releases the owned executor, if any; an injected one is not this class's to release. */
    @Override
    public void close() {
        if (ownedReader != null) ownedReader.shutdown();
    }

    // ------------------------------------------------------------------ seam

    /**
     * The fallback's candidate, re-centred on the server's unique definition
     * when one exists; any miss returns that candidate unchanged.
     */
    @Override
    public CompletableFuture<Optional<Usage>> declaration(String symbol) {
        return fallback.declaration(symbol).thenCompose(candidate -> {
            if (candidate.isEmpty()) {
                return CompletableFuture.completedFuture(candidate);
            }
            return queryPosition(symbol, candidate.get())
                    .thenCompose(position -> position
                            .map(p -> resolvedDeclaration(symbol, candidate, p))
                            .orElseGet(() -> unchangedDeclaration(symbol, candidate, "no queryable position")));
        });
    }

    /**
     * The fallback's rows, with every row the server confirmed at (file,
     * line) upgraded to {@link Provenance#RESOLVED}; unmatched rows keep
     * their text, order and provenance. Always {@link Status#ANSWERED}.
     */
    @Override
    public CompletableFuture<UsagesAnswer> usagesAnswer(String symbol) {
        CompletableFuture<UsagesAnswer> floor = fallback.usagesAnswer(symbol);
        CompletableFuture<Optional<Usage>> candidate = fallback.declaration(symbol);
        return floor.thenCompose(answer -> candidate.thenCompose(lexical -> {
            if (lexical.isEmpty()) {
                return CompletableFuture.completedFuture(answer); // no position to query at
            }
            return queryPosition(symbol, lexical.get()).thenCompose(position -> {
                if (position.isEmpty()) {
                    return CompletableFuture.completedFuture(answer);
                }
                if (!position.get().verifiedFromDisk()) {
                    // No read-back line guards a references answer: an unverified
                    // position is never sent (coordinator decision, T4b).
                    LOG.log(Level.DEBUG, "references of ''{0}'' not asked: the position could not"
                            + " be verified against the file", symbol);
                    return CompletableFuture.completedFuture(answer);
                }
                return resolvedUsages(symbol, answer, position.get());
            });
        }));
    }

    /** Delegates through {@link #usagesAnswer(String)}; existing callers keep compiling. */
    @Override
    public CompletableFuture<List<Usage>> usages(String symbol) {
        return usagesAnswer(symbol).thenApply(UsagesAnswer::usages);
    }

    // ------------------------------------------------------------ definition

    private CompletableFuture<Optional<Usage>> resolvedDeclaration(String symbol,
                                                                  Optional<Usage> candidate,
                                                                  QueryPosition position) {
        return onReader(() -> manager.definition(position.file(), position.lspLine(), position.character()))
                .thenCompose(query -> {
                    if (query.isEmpty()) {
                        return unchangedDeclaration(symbol, candidate, manager.hint());
                    }
                    return query.get()
                            .thenApply(locations -> uniqueInWorktree(locations))
                            .thenCompose(location -> location
                                    .map(one -> reCentred(symbol, candidate, one))
                                    .orElseGet(() -> unchangedDeclaration(symbol, candidate, missReason(symbol))))
                            .exceptionally(error -> {
                                LOG.log(Level.DEBUG, "definition of ''{0}'' failed: {1}",
                                        symbol, String.valueOf(error));
                                return candidate;
                            });
                });
    }

    private CompletableFuture<Optional<Usage>> unchangedDeclaration(String symbol,
                                                                     Optional<Usage> candidate, String reason) {
        LOG.log(Level.DEBUG, "declaration of ''{0}'' stays with the fallback: {1}", symbol, reason);
        return CompletableFuture.completedFuture(candidate);
    }

    private static String missReason(String symbol) {
        return "no unique in-worktree definition of '" + symbol + "'";
    }

    /** Reads the definition's line back and builds the re-centred, resolved declaration. */
    private CompletableFuture<Optional<Usage>> reCentred(String symbol, Optional<Usage> candidate,
                                                         JdtServerManager.Location definition) {
        Path file = uriToPath(definition.uri());
        if (file == null) {
            return unchangedDeclaration(symbol, candidate, "unparseable definition uri");
        }
        int lspLine = definition.line();
        return readLine(file, lspLine).thenApply(text -> {
            if (text == null) {
                LOG.log(Level.DEBUG, "declaration line {0} of ''{1}'' could not be read", lspLine, symbol);
                return candidate;
            }
            if (identifierColumn(text, symbol).isEmpty()) {
                // The guard: the server answered about a different token than the
                // symbol (a wrong or unverified position); a miss, never a wrong answer.
                LOG.log(Level.DEBUG, "definition of ''{0}'' rejected: line {1} does not name it",
                        symbol, lspLine + 1);
                return candidate;
            }
            return Optional.of(new Usage(
                    root.relativize(file).toString(),
                    lspLine + 1, // LSP 0-based → seam 1-based (§7: the only place the +1 lives)
                    text.strip(),
                    Provenance.RESOLVED,
                    true));
        });
    }

    private Optional<JdtServerManager.Location> uniqueInWorktree(List<JdtServerManager.Location> locations) {
        if (locations.size() != 1) {
            return Optional.empty();
        }
        JdtServerManager.Location location = locations.get(0);
        Path path = uriToPath(location.uri());
        return path != null && path.startsWith(root) ? Optional.of(location) : Optional.empty();
    }

    // ------------------------------------------------------------- references

    private CompletableFuture<UsagesAnswer> resolvedUsages(String symbol, UsagesAnswer floor,
                                                          QueryPosition position) {
        return onReader(() -> manager.references(position.file(), position.lspLine(), position.character()))
                .thenCompose(query -> {
                    if (query.isEmpty()) {
                        LOG.log(Level.DEBUG, "references of ''{0}'' not asked: {1}", symbol, manager.hint());
                        return CompletableFuture.completedFuture(floor);
                    }
                    return query.get()
                            .thenApply(locations -> upgraded(floor, locations))
                            .exceptionally(error -> {
                                LOG.log(Level.DEBUG, "references of ''{0}'' failed: {1}",
                                        symbol, String.valueOf(error));
                                return floor;
                            });
                });
    }

    /** Upgrades exactly the fallback rows the server confirmed; a valid empty answer upgrades nothing. */
    private UsagesAnswer upgraded(UsagesAnswer floor, List<JdtServerManager.Location> locations) {
        Set<RowKey> confirmed = new HashSet<>();
        for (JdtServerManager.Location location : locations) {
            Path path = uriToPath(location.uri());
            if (path == null || !path.startsWith(root)) continue;
            confirmed.add(new RowKey(root.relativize(path).normalize(), location.line() + 1));
        }
        List<Usage> rows = new ArrayList<>(floor.usages().size());
        boolean upgradedAny = false;
        for (Usage row : floor.usages()) {
            if (confirmed.contains(new RowKey(Path.of(row.file()).normalize(), row.line()))) {
                rows.add(new Usage(row.file(), row.line(), row.text(), Provenance.RESOLVED,
                        row.resolvedDeclaration()));
                upgradedAny = true;
            } else {
                rows.add(row);
            }
        }
        if (!upgradedAny) {
            // The server's null/empty list (or one matching nothing) is still a
            // valid answer: the floor's rows stand, marked ANSWERED (§8).
            return new UsagesAnswer(floor.usages(), Status.ANSWERED);
        }
        return new UsagesAnswer(rows, Status.ANSWERED);
    }

    private record RowKey(Path file, int line) { }

    // ------------------------------------------------------------- positions

    /** Where a query may be asked for the candidate: file, LSP 0-based line, UTF-16 column. */
    private record QueryPosition(Path file, int lspLine, int character, boolean verifiedFromDisk) { }

    private CompletableFuture<Optional<QueryPosition>> queryPosition(String symbol, Usage candidate) {
        if (!candidate.file().toLowerCase(Locale.ROOT).endsWith(".java")) {
            return CompletableFuture.completedFuture(Optional.empty()); // a Java-only server: inapplicable
        }
        Path file = root.resolve(candidate.file()).normalize();
        int lspLine = candidate.line() - 1; // seam 1-based → LSP 0-based
        return readLine(file, lspLine).thenApply(rawLine -> {
            if (rawLine != null) {
                OptionalInt column = identifierColumn(rawLine, symbol);
                if (column.isEmpty()) {
                    return Optional.empty(); // stale index: the line no longer names the symbol
                }
                return Optional.of(new QueryPosition(file, lspLine, column.getAsInt(), true));
            }
            // The raw line could not be read: the carried (stripped) text is the
            // last resort, and only a guarded definition may use it.
            OptionalInt column = identifierColumn(candidate.text(), symbol);
            if (column.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new QueryPosition(file, lspLine, column.getAsInt(), false));
        });
    }

    /**
     * The UTF-16 column of the first whole-word occurrence of {@code symbol}
     * in {@code lineText}, or empty. A Java {@code String} index is a UTF-16
     * unit, so the index is the LSP character directly (identifiers are
     * assumed ASCII, as spec §7 does).
     */
    static OptionalInt identifierColumn(String lineText, String symbol) {
        if (lineText == null || symbol == null || symbol.isEmpty() || symbol.length() > lineText.length()) {
            return OptionalInt.empty();
        }
        int from = 0;
        while (true) {
            int at = lineText.indexOf(symbol, from);
            if (at < 0) {
                return OptionalInt.empty();
            }
            int end = at + symbol.length();
            boolean beforeOk = at == 0 || !Character.isJavaIdentifierPart(lineText.charAt(at - 1));
            boolean afterOk = end == lineText.length() || !Character.isJavaIdentifierPart(lineText.charAt(end));
            if (beforeOk && afterOk) {
                return OptionalInt.of(at);
            }
            from = at + 1;
        }
    }

    // ----------------------------------------------------------------- files

    /** One bounded read of the wanted line, off the caller's thread; null when it cannot be read. */
    private CompletableFuture<String> readLine(Path file, int zeroBasedLine) {
        return CompletableFuture.supplyAsync(() -> {
            if (zeroBasedLine < 0) {
                return null;
            }
            try {
                if (!Files.isRegularFile(file) || Files.size(file) > MAX_READ_BYTES) {
                    return null;
                }
                try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    for (int i = 0; i < zeroBasedLine; i++) {
                        if (lines.readLine() == null) {
                            return null;
                        }
                    }
                    return lines.readLine();
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.DEBUG, "could not read line {0} of {1}: {2}",
                        zeroBasedLine, file, e.toString());
                return null;
            }
        }, fileReader);
    }

    private static Path uriToPath(String uri) {
        try {
            return Path.of(URI.create(Objects.requireNonNull(uri, "uri"))).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private <T> CompletableFuture<T> onReader(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, fileReader);
    }
}
