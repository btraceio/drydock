package app.drydock.agent.providers.claude.internal;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DtoolEvalTokenResolverTest {

    @Test
    void decodeJwtExpiryReadsExpClaim() {
        // payload: {"exp": 1787755205, "iat": 1787733605}
        String payload = base64url("{\"exp\":1787755205,\"iat\":1787733605}");
        String jwt = "header." + payload + ".sig";
        assertEquals(Instant.ofEpochSecond(1787755205),
                DtoolEvalTokenResolver.decodeJwtExpiry(jwt).orElseThrow());
    }

    @Test
    void decodeJwtExpiryEmptyForNonJwt() {
        assertTrue(DtoolEvalTokenResolver.decodeJwtExpiry("not-a-jwt").isEmpty());
    }

    @Test
    void decodeJwtExpiryEmptyForMissingExp() {
        String payload = base64url("{\"sub\":\"no-exp-here\"}");
        String jwt = "h." + payload + ".s";
        assertTrue(DtoolEvalTokenResolver.decodeJwtExpiry(jwt).isEmpty());
    }

    private static String base64url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
    }
}
