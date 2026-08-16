package app.drydock.agent.api;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a session needs to call back into drydock's MCP server, minted
 * for one launch.
 *
 * <p>{@code endpointUrl} and {@code token} are always present.
 * {@code credentialFile} is an owner-only file holding the credential, and
 * what is in it depends on how the provider is served: for {@link
 * McpDelivery#CONFIG_FILE} it is the whole JSON config the agent reads
 * (Claude's {@code --mcp-config}), and for {@link McpDelivery#COMMAND_LINE}
 * it is the bare token, which exists so the launch command can read it at
 * exec time instead of carrying it as an argument. Absent only when there is
 * no MCP wiring at all.</p>
 *
 * <p>{@code token} is a session credential. {@link #toString()} is overridden
 * to withhold it: this record is reachable from launch contexts that end up in
 * log lines and command previews, and the one in {@code McpServer} is already
 * kept out of {@code toString} for the same reason.</p>
 */
public record McpAccess(String endpointUrl, String token, Optional<Path> credentialFile) {

    public McpAccess {
        Objects.requireNonNull(endpointUrl, "endpointUrl");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(credentialFile, "credentialFile");
    }

    /** Deliberately withholds the token and the endpoint (which carries the port). */
    @Override
    public String toString() {
        return "McpAccess[credentialFile=" + credentialFile.map(Path::toString).orElse("none") + "]";
    }
}
