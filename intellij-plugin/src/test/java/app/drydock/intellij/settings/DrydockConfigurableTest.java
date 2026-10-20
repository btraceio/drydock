package app.drydock.intellij.settings;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DrydockConfigurableTest {

    @Test
    void effectiveBaseDefaultsToDevWt() {
        assertEquals(Path.of("/Users/me/dev/wt"), DrydockConfigurable.effectiveBase(Optional.empty(), Path.of("/Users/me")));
    }

    @Test
    void effectiveBaseUsesConfigured() {
        assertEquals(Path.of("/work/trees"),
                DrydockConfigurable.effectiveBase(Optional.of(Path.of("/work/trees")), Path.of("/Users/me")));
    }

    @Test
    void aWorktreeFolderMustBeAFullPathOrEmpty() {
        assertEquals(Optional.empty(), DrydockConfigurable.refusal(""));
        assertEquals(Optional.empty(), DrydockConfigurable.refusal("/work/trees"));
        assertEquals(Optional.of("Enter a full folder path, or leave it empty for the default."),
                DrydockConfigurable.refusal("wt"));
    }
}
