package app.drydock.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JBangBootstrapTest {

    @Test
    void stageNativesCopiesBothDylibs(@TempDir Path tmp) throws IOException {
        JBangBootstrap.stageNatives(tmp, "macos-x86_64");
        assertTrue(Files.isRegularFile(tmp.resolve("macos-x86_64/libghostty.dylib")));
        assertTrue(Files.isRegularFile(tmp.resolve("macos-x86_64/libdrydockterminalhost.dylib")));
    }

    @Test
    void stageNativesIsIdempotent(@TempDir Path tmp) throws IOException {
        JBangBootstrap.stageNatives(tmp, "macos-x86_64");
        JBangBootstrap.stageNatives(tmp, "macos-x86_64"); // must not throw
        assertTrue(Files.isRegularFile(tmp.resolve("macos-x86_64/libghostty.dylib")));
    }

    @Test
    void applyNativeDirPropertiesSetsBothAndRespectsOverride(@TempDir Path tmp) {
        String ghostty = "app.drydock.ghostty.nativeDir";
        String host = "app.drydock.terminalhost.nativeDir";
        String preset = "/preset/override";
        System.setProperty(ghostty, preset);
        System.clearProperty(host);
        try {
            JBangBootstrap.applyNativeDirProperties(tmp);
            assertEquals(preset, System.getProperty(ghostty), "must not override a pre-set property");
            assertEquals(tmp.toString(), System.getProperty(host));
        } finally {
            System.clearProperty(ghostty);
            System.clearProperty(host);
        }
    }

    @Test
    void defaultCacheRootIsUnderLibraryCaches() {
        Path root = JBangBootstrap.defaultCacheRoot("1.2.3");
        String tail = Path.of("Library", "Caches", "drydock", "native", "1.2.3").toString();
        assertTrue(root.toString().endsWith(tail), root.toString());
    }

    @Test
    void resolveVersionFallsBackWhenManifestAbsent() {
        // Running from classes (no jar manifest) => getImplementationVersion() is null.
        String v = JBangBootstrap.resolveVersion();
        assertNotNull(v);
        assertFalse(v.isBlank());
        assertTrue(v.startsWith("dev-"), v);
    }
}
