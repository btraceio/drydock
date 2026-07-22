package app.drydock.ui.explorer;

import app.drydock.ui.explorer.FileContent.Terminator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure file-loading assertions -- no FX toolkit needed. */
class FileContentTest {

    private static final long MAX = 1024;

    @Test
    void plainUtf8FileIsEditable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Hello.java");
        Files.writeString(file, "class Hello {}\n");

        FileContent content = FileContent.load(file, MAX);

        assertEquals("class Hello {}\n", content.text());
        assertFalse(content.truncated());
        assertTrue(content.decoded());
        assertEquals(Terminator.LF, content.terminator());
        assertTrue(content.editable());
    }

    @Test
    void crlfFileIsNormalisedForTheEditorAndRestoredOnWrite(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("windows.txt");
        Files.write(file, "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8));

        FileContent content = FileContent.load(file, MAX);

        assertEquals("a\nb\n", content.text(), "editor sees LF");
        assertEquals(Terminator.CRLF, content.terminator());
        assertTrue(content.editable());
        assertEquals("a\r\nX\r\n", content.toDiskText("a\nX\n"), "write restores CRLF");
    }

    @Test
    void lfFileWritesBackUnchanged(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("unix.txt");
        Files.writeString(file, "a\nb\n");

        FileContent content = FileContent.load(file, MAX);

        assertEquals("a\nX\n", content.toDiskText("a\nX\n"));
    }

    @Test
    void mixedTerminatorsAreNotEditable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("mixed.txt");
        Files.write(file, "a\r\nb\nc\n".getBytes(StandardCharsets.UTF_8));

        FileContent content = FileContent.load(file, MAX);

        assertEquals(Terminator.MIXED, content.terminator());
        assertFalse(content.editable(), "rewriting would touch every line");
    }

    @Test
    void invalidUtf8IsNotEditable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("latin1.txt");
        Files.write(file, new byte[] {(byte) 0xC3, (byte) 0x28});

        FileContent content = FileContent.load(file, MAX);

        assertFalse(content.decoded());
        assertFalse(content.editable(), "lossy decode would destroy the file");
    }

    @Test
    void nulByteMeansBinaryAndIsNotEditable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("blob.bin");
        Files.write(file, new byte[] {'M', 'Z', 0, 'x'});

        FileContent content = FileContent.load(file, MAX);

        assertFalse(content.decoded());
        assertFalse(content.editable());
    }

    @Test
    void truncatedFileIsNotEditable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("huge.txt");
        Files.writeString(file, "x".repeat((int) MAX + 50));

        FileContent content = FileContent.load(file, MAX);

        assertTrue(content.truncated());
        assertFalse(content.editable(), "saving would delete the untruncated remainder");
        assertTrue(content.text().contains("truncated"), "the user must see why it is read-only");
    }

    @Test
    void emptyFileIsEditable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("empty.txt");
        Files.writeString(file, "");

        FileContent content = FileContent.load(file, MAX);

        assertEquals(Terminator.NONE, content.terminator());
        assertTrue(content.editable());
    }

    @Test
    void missingFileThrows(@TempDir Path dir) {
        assertThrows(IOException.class, () -> FileContent.load(dir.resolve("nope.txt"), MAX));
    }
}
