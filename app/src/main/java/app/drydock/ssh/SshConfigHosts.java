package app.drydock.ssh;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Best-effort extraction of concrete host aliases from the user's
 * {@code ~/.ssh/config}, for pre-populating the "Add remote repository"
 * host combo box. Deliberately shallow (spec: SSH remote repositories):
 * {@code Include} is not followed and {@code Match} blocks contribute
 * nothing, so the list can be incomplete — the combo box stays editable
 * and free-text hosts are always accepted.
 */
public final class SshConfigHosts {

    private static final Logger LOG = System.getLogger(SshConfigHosts.class.getName());

    private SshConfigHosts() {
    }

    /** Reads {@code ~/.ssh/config}; an unreadable or absent file is simply an empty suggestion list. */
    public static List<String> loadUserHosts() {
        Path config = Path.of(System.getProperty("user.home"), ".ssh", "config");
        try {
            return parse(Files.readString(config));
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "No readable ~/.ssh/config; host suggestions empty", e);
            return List.of();
        }
    }

    /** Pure parser over the config text; see class doc for the (deliberate) limits. */
    public static List<String> parse(String configText) {
        Set<String> hosts = new LinkedHashSet<>();
        for (String rawLine : configText.split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // Both "Host alias" and "Host=alias" keyword forms are valid.
            String[] keywordAndRest = line.split("[ \t=]+", 2);
            if (keywordAndRest.length < 2
                    || !keywordAndRest[0].toLowerCase(Locale.ROOT).equals("host")) {
                continue;
            }
            for (String pattern : keywordAndRest[1].split("[ \t]+(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                String alias = pattern.strip();
                if (alias.startsWith("\"") && alias.endsWith("\"") && alias.length() >= 2) {
                    alias = alias.substring(1, alias.length() - 1);
                }
                if (alias.isEmpty() || alias.startsWith("!")
                        || alias.contains("*") || alias.contains("?")) {
                    continue;
                }
                hosts.add(alias);
            }
        }
        return new ArrayList<>(hosts);
    }
}
