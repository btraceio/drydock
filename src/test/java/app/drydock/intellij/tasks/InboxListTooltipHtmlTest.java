package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InboxListTooltipHtmlTest {
    @Test
    void wrapsTooltipAndEscapesTextBeforeRenderingLineBreaks() {
        String html = InboxList.htmlTooltip("Task description\n\n<html>& \"quoted\" 'text'");

        assertTrue(html.startsWith("<html>"));
        assertTrue(html.endsWith("</html>"));
        assertTrue(html.contains("Task description<br><br>"));
        assertTrue(html.contains("&lt;html&gt;&amp; &quot;quoted&quot; &#39;text&#39;"));
        assertEquals(0, html.indexOf("<html>"));
        assertEquals(html.length() - "</html>".length(), html.lastIndexOf("</html>"));
    }
}
