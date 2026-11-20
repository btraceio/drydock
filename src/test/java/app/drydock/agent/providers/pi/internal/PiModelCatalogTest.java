package app.drydock.agent.providers.pi.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure parse tests for {@code pi --list-models} output; the subprocess itself
 * is {@link PiVersionProbe}'s pattern and not exercised here.
 */
class PiModelCatalogTest {

    private static final String CATALOG = """
            provider                      model                                          context  max-out  thinking  images
            ai-gw-baseten                 baseten/zai-org/GLM-5.2                        1.0M     131.1K   yes       no\s
            ai-gw-bedrock                 bedrock-openai/openai.gpt-5.4                  1M       128K     yes       no\s
            ai-gw-bedrock                 bedrock/google.gemma-3-12b-it                  128K     16.4K    no        no\s
            datadog-ai-gateway-anthropic  claude-sonnet-4-6                              200K     16.4K    yes       yes
            """;

    @Test
    void parseExtractsTheModelColumnInOrder() {
        assertEquals(List.of(
                "baseten/zai-org/GLM-5.2",
                "bedrock-openai/openai.gpt-5.4",
                "bedrock/google.gemma-3-12b-it",
                "claude-sonnet-4-6"), PiModelCatalog.parse(CATALOG));
    }

    @Test
    void parseSkipsTheHeaderRowByContent() {
        // The header is skipped by its exact first two column labels
        // ("provider", "model"), not by position or a bare prefix: a future
        // column insert must not shift the parse.
        assertTrue(PiModelCatalog.parse("provider   model   context\n").isEmpty());
    }

    @Test
    void parseDeduplicatesKeepingFirstSeenOrder() {
        String output = """
                provider   model   context
                p1         same/model   1M
                p2         same/model   1M
                p3         other/model   1M
                """;
        assertEquals(List.of("same/model", "other/model"), PiModelCatalog.parse(output));
    }

    @Test
    void parseKeepsGoingPastAMalformedRow() {
        // Applied line by line: one malformed row costs one line, not the
        // whole catalog.
        String output = """
                provider   model   context
                this row has no proper columns
                p1         good/model   1M
                """;
        assertEquals(List.of("good/model"), PiModelCatalog.parse(output));
    }

    @Test
    void parseReturnsEmptyForBlankOrNullOutput() {
        assertTrue(PiModelCatalog.parse("").isEmpty());
        assertTrue(PiModelCatalog.parse("   \n").isEmpty());
        assertTrue(PiModelCatalog.parse(null).isEmpty());
    }

    @Test
    void listRefusesANullCommand() {
        assertTrue(PiModelCatalog.list(null).isEmpty());
    }

    @Test
    void configReadsProviderKeyedModelIds(@TempDir Path dir) throws Exception {
        Path modelsJson = Files.writeString(dir.resolve("models.json"), """
                {
                  "providers": {
                    "ai-gw-bedrock": {
                      "api": "openai-completions",
                      "models": [
                        {"id": "bedrock-openai/openai.gpt-5.4", "contextWindow": 1048576},
                        {"id": "gpt-5-mini"}
                      ]
                    },
                    "datadog-ai-gateway-anthropic": {
                      "api": "anthropic-messages",
                      "models": [
                        {"id": "claude-opus-4-7"},
                        {"name": "no id here"}
                      ]
                    },
                    "empty-provider": {
                      "api": "openai-completions"
                    }
                  }
                }
                """);

        // Ids are verbatim -- the same strings `pi --list-models` prints in
        // its model column; the provider key is a separate column there.
        assertEquals(List.of(
                        "bedrock-openai/openai.gpt-5.4",
                        "gpt-5-mini",
                        "claude-opus-4-7"),
                PiModelCatalog.fromConfig(modelsJson));
    }

    @Test
    void configDegradesToEmpty(@TempDir Path dir) throws Exception {
        assertTrue(PiModelCatalog.fromConfig(null).isEmpty());
        assertTrue(PiModelCatalog.fromConfig(dir.resolve("absent.json")).isEmpty());
        Path malformed = Files.writeString(dir.resolve("malformed.json"), "{not json");
        assertTrue(PiModelCatalog.fromConfig(malformed).isEmpty());
        Path shapeless = Files.writeString(dir.resolve("shapeless.json"), "{\"providers\": 3}");
        assertTrue(PiModelCatalog.fromConfig(shapeless).isEmpty());
        Path empty = Files.writeString(dir.resolve("empty.json"), "{\"providers\": {}}");
        assertTrue(PiModelCatalog.fromConfig(empty).isEmpty());
    }

    @Test
    void parseKeepsAProviderWhoseNameStartsWithProvider() {
        // Only the exact provider/model header is skipped: a provider token that
        // merely starts with "provider" (or is "provider" over a real model id)
        // is a data row.
        String output = """
                provider      model        context
                provider-gw   gw/model-a   1M
                providers     p/model-b    1M
                provider      x/model-c    1M
                """;
        assertEquals(List.of("gw/model-a", "p/model-b", "x/model-c"), PiModelCatalog.parse(output));
    }
}
