package app.drydock.agent.providers.claude.internal;

import app.drydock.agent.api.EvalTokenResolver;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the eval-account auth token by running {@code ddtool} on the
 * host. DataDog-specific: the command audience and datacenter are pinned
 * to the AI gateway's {@code rapid-ai-platform} service at
 * {@code us1.ddbuild.io}.
 *
 * <p>This is the default {@link EvalTokenResolver}, wired in directly for
 * now. Once Drydock grows plugin support it is intended to move behind a
 * DataDog-specific plugin so the eval integration is not bound to a
 * single credential source -- the SPI is the seam that makes that move
 * a drop-in.</p>
 *
 * <p>The token is a JWT; its {@code exp} claim is decoded (without
 * signature verification -- display only, not auth) so the UI can show
 * a countdown. Blocking; must be called off the FX thread.</p>
 */
public final class DtoolEvalTokenResolver implements EvalTokenResolver {

    private static final Logger LOG = System.getLogger(DtoolEvalTokenResolver.class.getName());

    /** ddtool command that yields the eval-account auth token on stdout. */
    private static final List<String> DTOOL_TOKEN_CMD = List.of(
            "ddtool", "auth", "token", "rapid-ai-platform", "--datacenter", "us1.ddbuild.io");

    @Override
    public Optional<ResolvedToken> resolveToken() {
        try {
            ProcessResult res = ProcessRunner.run(DTOOL_TOKEN_CMD, null, java.time.Duration.ofSeconds(15));
            if (res.exitCode() != 0) {
                LOG.log(Level.WARNING, () -> "ddtool auth token failed (exit " + res.exitCode() + "): "
                        + ProcessRunner.excerpt(res.stderr()));
                return Optional.empty();
            }
            String token = res.stdout().strip();
            if (token.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedToken(token, decodeJwtExpiry(token)));
        } catch (Exception e) {
            LOG.log(Level.WARNING, () -> "ddtool auth token unavailable: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Decodes the {@code exp} claim from the JWT payload (middle segment)
     * without verifying the signature: this is for display only, not auth.
     */
    static Optional<Instant> decodeJwtExpiry(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return Optional.empty();
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonValue parsed = JsonParser.parse(new String(payload, StandardCharsets.UTF_8));
            if (parsed instanceof JsonObject o && o.members().get("exp") instanceof JsonValue v) {
                long exp = v instanceof JsonValue.JsonString s ? Long.parseLong(s.value())
                        : v instanceof JsonValue.JsonNumber n ? n.asLong() : 0L;
                return exp > 0 ? Optional.of(Instant.ofEpochSecond(exp)) : Optional.empty();
            }
        } catch (IllegalArgumentException | JsonParseException e) {
            LOG.log(Level.DEBUG, () -> "Could not decode JWT exp: " + e.getMessage());
        }
        return Optional.empty();
    }
}
