package app.drydock.ui.review;

import app.drydock.search.SessionSearchService;
import app.drydock.ui.nav.ExplorerTrailStore;

import java.nio.file.Path;
import java.util.Objects;

/**
 * What the Review tour needs to navigate like the Explorer (spec §5): the
 * checkout its peeks and search read, the session's search service, and the
 * store its trail persists in under {@link ExplorerTrailStore#reviewKey} of
 * {@code sessionKey}.
 */
public record ReviewNavigation(Path root, SessionSearchService search, ExplorerTrailStore trails, String sessionKey) {

    public ReviewNavigation {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(search, "search");
        Objects.requireNonNull(trails, "trails");
        Objects.requireNonNull(sessionKey, "sessionKey");
    }
}
