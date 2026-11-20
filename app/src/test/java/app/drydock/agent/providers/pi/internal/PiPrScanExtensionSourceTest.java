package app.drydock.agent.providers.pi.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PiPrScanExtensionSourceTest {

    @Test
    void sessionIdIsAPathSafeBasenameAndOutputIsContained() {
        String source = PiPrScanExtensionSource.SOURCE;

        assertTrue(source.contains("!/^[A-Za-z0-9_-]{1,128}$/.test(id)"));
        assertTrue(source.contains("const root = path.resolve(dir);"));
        assertTrue(source.contains("const file = path.resolve(root, id);"));
        assertTrue(source.contains("const relative = path.relative(root, file);"));
        assertTrue(source.contains("path.isAbsolute(relative)"));
        assertTrue(source.contains("fs.renameSync(tmp, file);"));
    }
}
