package app.drydock.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepositorySidebarChipTest {

    @Test
    void chipTextPrefixesGlyph() {
        assertEquals("⇅ prod-box", RepositorySidebar.remoteChipText("prod-box"));
    }

    @Test
    void chipTextKeepsFullUserAtHost() {
        assertEquals("⇅ deploy@build.internal",
                RepositorySidebar.remoteChipText("deploy@build.internal"));
    }

    @Test
    void tooltipTextPrefixesRemoteHost() {
        assertEquals("Remote host: deploy@build.internal",
                RepositorySidebar.remoteChipTooltipText("deploy@build.internal"));
    }
}
