package app.drydock.agent.api;

/**
 * How a provider is handed drydock's own MCP tools.
 *
 * <p>This replaced a boolean {@code supportsMcpConfig}, which could only ask
 * "does this integration understand Claude's {@code --mcp-config} file". Once
 * a second provider reached the same server by a different mechanism, that
 * question stopped being answerable yes/no: Codex wants the endpoint URL and
 * token on its command line and has no use for a file at all.</p>
 *
 * <ul>
 *   <li>{@code NONE} -- this integration cannot reach drydock's tools. Its
 *       sessions get no token minted, so no credential exists to leak. Nothing
 *       is here today; Pi used to be, before drydock shipped it a bridge
 *       extension.</li>
 *   <li>{@code CONFIG_FILE} -- drydock writes an owner-only JSON config and
 *       the launch command points at it (Claude's {@code --mcp-config}).</li>
 *   <li>{@code COMMAND_LINE} -- the launch command carries the endpoint and
 *       token itself (Codex's {@code -c mcp_servers.…}).</li>
 * </ul>
 *
 * <p>Like {@code supportsRemote}, this is a static fact about the integration
 * rather than something probed from the installed binary, so implementations
 * MUST make it cheap and non-blocking. Whether the installed binary actually
 * advertises the flag stays provider-internal.</p>
 */
public enum McpDelivery {
    NONE,
    CONFIG_FILE,
    COMMAND_LINE
}
