package app.drydock.agent.api;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a session needs to call back into drydock's MCP server, minted
 * for one launch.
 *
 * <p>{@code endpointUrl} and {@code token} are always present; {@code
 * configFile} only when the provider asked for {@link
 * McpDelivery#CONFIG_FILE}, because writing an owner-only file for a provider
 * that will not read one is a credential on disk with no reader.</p>
 *
 * <p>{@code token} is a session credential. {@link #toString()} is overridden
 * to withhold it: this record is reachable from launch contexts that end up in
 * log lines and command previews, and the one in {@code McpServer} is already
 * kept out of {@code toString} for the same reason.</p>
 */
public record McpAccess(String endpointUrl, String token, Optional<Path> configFile) {

    public McpAccess {
        Objects.requireNonNull(endpointUrl, "endpointUrl");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(configFile, "configFile");
    }

    /** Deliberately withholds the token and the endpoint (which carries the port). */
    @Override
    public String toString() {
        return "McpAccess[configFile=" + configFile.map(Path::toString).orElse("none") + "]";
    }
}
