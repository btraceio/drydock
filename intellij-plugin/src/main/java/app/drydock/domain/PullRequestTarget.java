package app.drydock.domain;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A session's explicit target, independent of the PR published from its working branch. */
public record PullRequestTarget(String repository, int number) {

    private static final Pattern REPOSITORY = Pattern.compile(
            "[A-Za-z0-9.-]+/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("/([^/]+)/([^/]+)/pull/([1-9][0-9]*)/?");

    public PullRequestTarget {
        Objects.requireNonNull(repository, "repository");
        if (!REPOSITORY.matcher(repository).matches() || number <= 0) {
            throw new IllegalArgumentException("A PR target needs HOST/OWNER/REPO and a positive number");
        }
    }

    public String url() {
        return "https://" + repository + "/pull/" + number;
    }

    public static PullRequestTarget fromUrl(String url) {
        URI uri = URI.create(url);
        Matcher path = PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null || !path.matches()) {
            throw new IllegalArgumentException("Use an HTTPS pull request URL");
        }
        return new PullRequestTarget(uri.getHost() + "/" + path.group(1) + "/" + path.group(2),
                Integer.parseInt(path.group(3)));
    }
}
