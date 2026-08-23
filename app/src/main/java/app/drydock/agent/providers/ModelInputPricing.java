package app.drydock.agent.providers;

import app.drydock.agent.api.ResumeCostEstimate;

import java.util.Locale;
import java.util.Optional;

/**
 * Public list prices used only for a conservative warning, in USD per million
 * input tokens. Rates include a 5-minute cold-cache write where the model
 * exposes one. Unknown models deliberately produce no estimate.
 *
 * <p>Pricing snapshot: 2026-08-23, from the vendors' official API pricing
 * pages. Keeping this small and explicit is safer than silently applying one
 * vendor's price to a gateway alias.</p>
 */
public final class ModelInputPricing {

    private ModelInputPricing() { }

    public static Optional<ResumeCostEstimate> estimate(String model, long contextTokens) {
        if (model == null || model.isBlank() || contextTokens <= 0) {
            return Optional.empty();
        }
        return maximumUsdPerMillion(model, contextTokens).map(rate ->
                new ResumeCostEstimate(contextTokens, contextTokens * rate / 1_000_000d, model));
    }

    static Optional<Double> maximumUsdPerMillion(String model, long contextTokens) {
        String m = model.toLowerCase(Locale.ROOT);

        if (m.startsWith("claude-opus-5") || m.matches("claude-opus-4-[5-9].*")) return Optional.of(6.25);
        if (m.startsWith("claude-opus-4")) return Optional.of(18.75);
        if (m.startsWith("claude-sonnet-5")) return Optional.of(2.50);
        if (m.startsWith("claude-sonnet-4")) return Optional.of(3.75);
        if (m.startsWith("claude-haiku-4-5")) return Optional.of(1.25);
        if (m.startsWith("claude-haiku-3-5")) return Optional.of(1.00);

        double longContext = contextTokens > 272_000 ? 2.0 : 1.0;
        if (m.startsWith("gpt-5.6-sol")) return Optional.of(3.125 * longContext);
        if (m.startsWith("gpt-5.6-terra")) return Optional.of(1.5625 * longContext);
        if (m.startsWith("gpt-5.6-luna")) return Optional.of(0.625 * longContext);
        if (m.startsWith("gpt-5.5")) return Optional.of(2.50 * longContext);
        if (m.startsWith("gpt-5.4")) return Optional.of(1.25 * longContext);
        if (m.startsWith("gpt-5.3-codex")) return Optional.of(1.75);
        if (m.startsWith("gpt-5.2-codex") || m.startsWith("gpt-5.1-codex")
                || m.startsWith("gpt-5-codex")) return Optional.of(1.25);
        return Optional.empty();
    }
}
