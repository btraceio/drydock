package app.drydock.ui.explorer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One loaded file's text plus the structural facts that decide whether it
 * may be edited (spec decision 3). Eligibility is derived from the BYTES at
 * load time -- never from the file extension, and never by sniffing the
 * buffer for marker text, since a source file can legitimately contain the
 * words this class puts in a truncation notice.
 *
 * <p>Three things make a buffer unsafe to write back:</p>
 * <ul>
 *   <li><b>Truncated</b> -- saving would delete everything past the limit.</li>
 *   <li><b>Undecodable</b> -- a lenient UTF-8 decode turns every invalid
 *       byte into U+FFFD, so writing the buffer back destroys a binary or
 *       non-UTF-8 file. Decoding is therefore strict, and any NUL byte
 *       marks the content binary outright.</li>
 *   <li><b>Mixed line terminators</b> -- {@link org.fxmisc.richtext.CodeArea}
 *       works in LF, so a rewrite would normalise every line and turn a
 *       one-line touch-up into a whole-file diff.</li>
 * </ul>
 *
 * <p>{@link #text()} is always LF-normalised for the editor;
 * {@link #toDiskText(String)} restores the file's own terminator on the way
 * back out.</p>
 */
record FileContent(String text, boolean truncated, boolean decoded, Terminator terminator) {

    /** The file's dominant line terminator, captured at load and reapplied on write. */
    enum Terminator { LF, CRLF, MIXED, NONE }

    /** Whether this buffer may be written back to disk without losing or rewriting content. */
    boolean editable() {
        return decoded && !truncated && terminator != Terminator.MIXED;
    }

    /** Converts the editor's LF text back to the file's own terminator. */
    String toDiskText(String editorText) {
        return terminator == Terminator.CRLF ? editorText.replace("\n", "\r\n") : editorText;
    }

    /**
     * Reads {@code file}, truncating past {@code maxBytes}. Throws only when
     * the file cannot be read at all; an unreadable-content file (binary,
     * bad encoding) loads successfully as a non-{@link #editable()} buffer
     * so the viewer can still show it.
     */
    static FileContent load(Path file, long maxBytes) throws IOException {
        long size = Files.size(file);
        boolean truncated = size > maxBytes;
        byte[] bytes;
        if (truncated) {
            try (InputStream in = Files.newInputStream(file)) {
                bytes = in.readNBytes((int) maxBytes);
            }
        } else {
            bytes = Files.readAllBytes(file);
        }

        boolean decoded = !containsNul(bytes) && decodesAsUtf8(bytes);
        // Lenient decode for DISPLAY only; an undecodable buffer is never
        // written back, so the U+FFFD replacements cannot reach the file.
        String raw = new String(bytes, StandardCharsets.UTF_8);
        Terminator terminator = detectTerminator(raw);
        String text = terminator == Terminator.CRLF ? raw.replace("\r\n", "\n") : raw;
        if (truncated) {
            text = text + "\n\n… (truncated: file exceeds " + (maxBytes / (1024 * 1024)) + " MB)\n";
        }
        return new FileContent(text, truncated, decoded, terminator);
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean decodesAsUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static Terminator detectTerminator(String raw) {
        boolean crlf = false;
        boolean loneLf = false;
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '\n') {
                if (i > 0 && raw.charAt(i - 1) == '\r') {
                    crlf = true;
                } else {
                    loneLf = true;
                }
            }
        }
        if (crlf && loneLf) {
            return Terminator.MIXED;
        }
        if (crlf) {
            return Terminator.CRLF;
        }
        return loneLf ? Terminator.LF : Terminator.NONE;
    }
}
