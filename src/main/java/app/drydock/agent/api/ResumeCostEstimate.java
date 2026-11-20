package app.drydock.agent.api;

import java.util.Objects;

/** Input-only cost ceiling for the first model request after resuming a transcript. */
public record ResumeCostEstimate(long contextTokens, double maximumInputCostUsd, String model) {
    public ResumeCostEstimate {
        if (contextTokens < 0 || !Double.isFinite(maximumInputCostUsd) || maximumInputCostUsd < 0) {
            throw new IllegalArgumentException("invalid resume cost estimate");
        }
        Objects.requireNonNull(model, "model");
    }
}
