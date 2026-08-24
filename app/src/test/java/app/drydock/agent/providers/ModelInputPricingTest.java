package app.drydock.agent.providers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelInputPricingTest {

    @Test
    void estimatesColdCacheWriteAndRefusesUnknownModels() {
        assertEquals(1.25, ModelInputPricing.estimate("claude-sonnet-5", 500_000)
                .orElseThrow().maximumInputCostUsd(), 0.000_001);
        assertEquals(0.3125, ModelInputPricing.estimate("gpt-5.6-sol", 100_000)
                .orElseThrow().maximumInputCostUsd(), 0.000_001);
        assertEquals(2.5, ModelInputPricing.estimate("gpt-5.6-sol", 400_000)
                .orElseThrow().maximumInputCostUsd(), 0.000_001);
        assertTrue(ModelInputPricing.estimate("company/gateway-alias", 500_000).isEmpty());
    }
}
