package app.drydock.agent.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The session-scoped git config the agent-identity pipeline injects, as
 * {@code GIT_CONFIG_COUNT}/{@code GIT_CONFIG_KEY_n}/{@code GIT_CONFIG_VALUE_n}
 * environment entries inside the session's env file (verified against real
 * git: these entries apply at command scope, after the system and global
 * config files, and an empty {@code credential.helper} value resets the
 * helper list).
 *
 * <p>Two families, both session-scoped and both transient: nothing here is
 * ever written to the user's global git config.</p>
 *
 * <ul>
 *   <li><strong>Credential helper</strong> — when the credential command
 *   mints {@code GIT_CREDENTIAL_HELPER_SPEC}, the session gets a
 *   {@code credential.helper=} entry (empty value resets the list) followed
 *   by the spec itself. The reset is load-bearing, not cosmetic: a global
 *   {@code credential.https://github.com.helper=!gh auth git-credential}
 *   would otherwise run first and supply the human's stored credential,
 *   pushing the bot-authored commit under the human's identity.</li>
 *   <li><strong>HTTPS remote rewrite</strong> — installation-style tokens are
 *   HTTPS-only, so an SSH-configured {@code github.com} origin is rewritten
 *   to its HTTPS form with {@code url.<https>.insteadOf}, without touching
 *   the user's SSH setup. A credential helper paired with an origin that
 *   cannot be rewritten to HTTPS is a hard failure, because the push would
 *   silently go out over SSH under the human's SSH identity.</li>
 * </ul>
 */
public record AgentIdentityGitConfig(List<Entry> entries) {

    public AgentIdentityGitConfig {
        entries = List.copyOf(entries);
    }

    /** One session-scoped git config entry. */
    public record Entry(String key, String value) {

        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    /** What an origin remote's URL means for the HTTPS rewrite. */
    enum OriginKind {

        /** No origin was resolvable; nothing to rewrite and nothing to fail over. */
        ABSENT,

        /** Already an HTTPS (or other non-SSH) URL; the token authenticates it as-is. */
        NOT_SSH,

        /** A github.com SSH URL with a rewritable path. */
        REWRITABLE_SSH,

        /** An SSH URL — or an unparseable github.com one — that must stay SSH. */
        UNREWRITABLE_SSH
    }

    /**
     * The entries for one prepared session: the credential-helper reset and
     * spec when the command minted a spec, and the HTTPS rewrite when the
     * repository's origin needs one.
     *
     * @throws AgentIdentityException when a credential helper is configured
     *     but {@code originUrl} is an SSH URL this pipeline cannot rewrite
     */
    public static List<Entry> forSession(AgentIdentitySpec spec, Optional<String> originUrl) {
        List<Entry> entries = new ArrayList<>();
        Optional<String> helper = spec.value(AgentIdentitySpec.GIT_CREDENTIAL_HELPER_SPEC);
        if (helper.isPresent()) {
            entries.add(new Entry("credential.helper", ""));
            entries.add(new Entry("credential.helper", helper.orElseThrow()));
            String url = originUrl.orElse("");
            OriginKind kind = originKind(url);
            switch (kind) {
                case ABSENT, NOT_SSH -> { }
                case REWRITABLE_SSH -> entries.add(rewriteEntry(url));
                case UNREWRITABLE_SSH -> throw new AgentIdentityException(
                        "The credential command mints a git credential helper, but the origin remote '"
                                + url + "' is an SSH URL that cannot be rewritten to HTTPS; installation-style "
                                + "tokens authenticate HTTPS only, so make the origin an HTTPS URL or a github.com "
                                + "SSH URL of the form git@github.com:OWNER/REPO.git");
            }
        }
        return List.copyOf(entries);
    }

    static OriginKind originKind(String originUrl) {
        if (originUrl.isBlank()) {
            return OriginKind.ABSENT;
        }
        if (originUrl.startsWith("git@github.com:")) {
            return pathShape(originUrl.substring("git@github.com:".length()))
                    ? OriginKind.REWRITABLE_SSH : OriginKind.UNREWRITABLE_SSH;
        }
        if (originUrl.startsWith("ssh://")) {
            String hostPath = originUrl.substring("ssh://".length());
            int slash = hostPath.indexOf('/');
            String host = slash < 0 ? hostPath : hostPath.substring(0, slash);
            String normalizedHost = host.endsWith(":22") ? host.substring(0, host.length() - 3) : host;
            if (!normalizedHost.equals("github.com") && !normalizedHost.equals("git@github.com")) {
                return OriginKind.UNREWRITABLE_SSH;
            }
            String path = slash < 0 ? "" : hostPath.substring(slash + 1);
            return pathShape(path) ? OriginKind.REWRITABLE_SSH : OriginKind.UNREWRITABLE_SSH;
        }
        // scp-style syntax, any host: [user@]host:path with no scheme. Only
        // github.com is rewritable; every other host's SSH URL stays SSH and
        // therefore cannot take an HTTPS-only token.
        int colon = originUrl.indexOf(':');
        if (colon > 0 && !originUrl.contains("://") && !originUrl.substring(0, colon).contains("/")) {
            String user = originUrl.substring(0, colon);
            String host = user.substring(user.indexOf('@') + 1);
            String path = originUrl.substring(colon + 1);
            if (host.equals("github.com")) {
                return pathShape(path) ? OriginKind.REWRITABLE_SSH : OriginKind.UNREWRITABLE_SSH;
            }
            return OriginKind.UNREWRITABLE_SSH;
        }
        return OriginKind.NOT_SSH;
    }

    /** Whether {@code path} is {@code OWNER/REPO}, with or without {@code .git}. */
    private static boolean pathShape(String path) {
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - ".git".length());
        }
        int slash = path.indexOf('/');
        return slash > 0 && slash < path.length() - 1 && path.indexOf('/', slash + 1) < 0;
    }

    /**
     * The rewrite entry taking {@code originUrl} (a github.com SSH URL of
     * {@link OriginKind#REWRITABLE_SSH}) to its HTTPS form.
     */
    static Entry rewriteEntry(String originUrl) {
        String path;
        if (originUrl.startsWith("git@github.com:")) {
            path = originUrl.substring("git@github.com:".length());
        } else {
            String hostPath = originUrl.substring("ssh://".length());
            int slash = hostPath.indexOf('/');
            path = hostPath.substring(slash + 1);
        }
        if (!path.endsWith(".git")) {
            path = path + ".git";
        }
        return new Entry("url.https://github.com/" + path + ".insteadOf", originUrl);
    }
}
