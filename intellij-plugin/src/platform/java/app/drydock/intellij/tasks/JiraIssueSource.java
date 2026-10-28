package app.drydock.intellij.tasks;

import app.drydock.git.JiraConnection;
import app.drydock.git.JiraIssueListing;
import app.drydock.git.JiraService;

import com.intellij.util.net.JdkProxyProvider;
import com.intellij.util.net.ssl.CertificateManager;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.Authenticator;
import java.net.Authenticator.RequestorType;
import java.net.PasswordAuthentication;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The tool window's Jira data path: it owns one {@link JiraService}, keeps
 * each project's listing in a one-minute cache, and reads the secret lazily
 * — only when the service actually asks for it, on the service's own
 * executor, never on the EDT and never on a cache hit. A disabled project
 * answers {@link JiraIssueListing.Unsupported} without any fetch, network
 * call or password-safe read at all.
 *
 * <p>Unlike {@code TaskTreeInputs.pullRequestsOf}, which deliberately retries
 * a failure on the next pass, this cache also keeps {@link
 * JiraIssueListing.Failed} results for the full TTL: the tree rebuilds on
 * every session or activity event, roughly every second, and a bad token
 * retried that often trips Data Center's CAPTCHA or Cloud's rate limits
 * (spec risk 2). A failure therefore pins for one minute; the user's
 * explicit Refresh bypasses it through {@link #invalidate(Path)}.</p>
 */
final class JiraIssueSource implements AutoCloseable {

    /** One minute, the same protection the GitHub issue cache gives against rebuild storms. */
    private static final long TTL_NANOS = TimeUnit.MINUTES.toNanos(1);

    private static final Logger LOG = System.getLogger(JiraIssueSource.class.getName());

    /** The service call this source caches, so the tests can fake it. */
    @FunctionalInterface
    interface Fetch {
        CompletableFuture<JiraIssueListing> openIssues(Optional<JiraConnection> connection,
                                                        Supplier<Optional<String>> secret);
    }

    private final Fetch fetch;
    private final AutoCloseable owned;
    private final Function<JiraProjectSettings, Optional<String>> secrets;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<Key, Cached> cache = new ConcurrentHashMap<>();

    /** The cache identity: one project root with one settings value, so a JQL change misses. */
    private record Key(Path root, JiraProjectSettings settings) { }

    /** One cached listing and when it stops being served. */
    private record Cached(long expiresAt, CompletableFuture<JiraIssueListing> listing) { }

    /**
     * The production source: its own service with the IDE's proxy and
     * certificates (spec Q4, risk 3), the IDE password safe, the real clock.
     */
    JiraIssueSource() {
        JiraService s = new JiraService(JiraIssueSource::ideClientBuilder);
        this.fetch = s::openIssues;
        this.owned = s;
        this.secrets = JiraCredentials::read;
        this.nanoClock = System::nanoTime;
    }

    /** The test source: every platform touch is a fake. */
    JiraIssueSource(Fetch fetch, AutoCloseable owned,
                    Function<JiraProjectSettings, Optional<String>> secrets, LongSupplier nanoClock) {
        this.fetch = Objects.requireNonNull(fetch, "fetch");
        this.owned = Objects.requireNonNull(owned, "owned");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /**
     * The project's Jira issues, cached per (root, settings) for one minute.
     *
     * <p>A disabled project gets a completed
     * {@link JiraIssueListing.Unsupported} with no fetch and no secret
     * read. An unexpired entry is served as is — <em>including {@code
     * Failed}</em>, deliberately unlike {@code TaskTreeInputs.pullRequestsOf},
     * which retries its failures every pass: here a retried bad token could
     * lock the account (spec risk 2, Q3). After the TTL, or an {@link
     * #invalidate()}, the next call fetches again. The secret supplier
     * handed to the fetch runs on the service executor when (and only when)
     * the service calls it, so a cached answer costs no password-safe read.</p>
     */
    CompletableFuture<JiraIssueListing> issuesOf(Path root, JiraProjectSettings settings) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(settings, "settings");
        Optional<JiraConnection> connection = settings.connection();
        if (connection.isEmpty()) {
            return CompletableFuture.completedFuture(new JiraIssueListing.Unsupported());
        }
        Key key = new Key(root, settings);
        long now = nanoClock.getAsLong();
        Cached cached = cache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return cached.listing();
        }
        CompletableFuture<JiraIssueListing> listing = fetch.openIssues(connection, () -> secrets.apply(settings));
        cache.put(key, new Cached(now + TTL_NANOS, listing));
        return listing;
    }

    /** Forgets every cached listing, so the next gather re-queries each project. */
    void invalidate() {
        cache.clear();
    }

    /** Forgets {@code root}'s cached listings only, so the next gather re-queries that project. */
    void invalidate(Path root) {
        Objects.requireNonNull(root, "root");
        cache.keySet().removeIf(key -> key.root().equals(root));
    }

    /**
     * Releases the owned service: its executor and, once built, its
     * {@code HttpClient} (spec AC27). One failing close must not keep the
     * caller from closing the rest, so the failure is logged (class name
     * only — nothing here carries a secret) and swallowed.
     */
    @Override
    public void close() {
        try {
            owned.close();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Closing the Jira service failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * The IDE-configured client builder: the IDE's proxy selector, the IDE
     * certificate manager's SSL context (corporate Data Center sites sit
     * behind both, spec risk 3), and the IDE authenticator narrowed to
     * proxy challenges only — a bare JDK {@code HttpClient} would ignore
     * all three. Runs lazily on the service's executor thread, because
     * {@code JiraService} builds its client inside its first request; never
     * on the EDT, and no thread assertion is needed for these reads.
     */
    private static HttpClient.Builder ideClientBuilder() {
        return HttpClient.newBuilder()
                .proxy(JdkProxyProvider.getInstance().getProxySelector())
                .sslContext(CertificateManager.getInstance().getSslContext())
                .authenticator(proxyOnly(JdkProxyProvider.getInstance().getAuthenticator()));
    }

    /**
     * The IDE authenticator wrapped so the JDK consults it for proxy
     * challenges only. Jira's own 401 must be answered by the
     * {@code Authorization} header this client sends, not by the IDE's
     * stored logins; forwarding those to Jira would both leak a credential
     * this plugin never asked to use and mask a wrong token as a working
     * one.
     */
    private static Authenticator proxyOnly(Authenticator delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY) {
                    return null;
                }
                return delegate.requestPasswordAuthenticationInstance(
                        getRequestingHost(), getRequestingSite(), getRequestingPort(),
                        getRequestingProtocol(), getRequestingPrompt(), getRequestingScheme(),
                        getRequestingURL(), getRequestorType());
            }
        };
    }
}
