package app.drydock.sdlc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Encodes an already captured {@link WorkflowBundle} into the wire archive the remote
 * {@code workflow_stage.py} unpacker accepts: one ZIP holding the regular-file bytes plus a
 * {@code .drydock-manifest.json} member listing every entry (path, type, sha256, size,
 * executable, link target). It never touches the filesystem, so what is shipped is exactly
 * what was captured, not what the worktree or remote checkout holds now.
 */
public final class WorkflowArchiveEncoder {
    /** Wire cap shared with the remote helper. */
    public static final long WIRE_CAP_BYTES = 40L * 1024 * 1024;
    public static final String MANIFEST_NAME = ".drydock-manifest.json";

    /** Encoded archive: the ZIP bytes and its own digest for transfer verification. */
    public record Archive(byte[] bytes, String sha256, int entryCount) {
        public Archive {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /** The archive would exceed the wire cap, or the bundle is inconsistent. */
    public static final class ArchiveException extends IOException {
        public ArchiveException(String message) {
            super(message);
        }
    }

    private WorkflowArchiveEncoder() {
    }

    public static Archive encode(WorkflowBundle bundle) throws IOException {
        return encode(bundle, WIRE_CAP_BYTES);
    }

    static Archive encode(WorkflowBundle bundle, long capBytes) throws IOException {
        if (bundle.entries().size() > WorkflowLimits.BUNDLE_ENTRIES
                || bundle.totalBytes() > WorkflowLimits.AGGREGATE_FILE_BYTES) {
            throw new ArchiveException("bundle exceeds workflow limits");
        }
        List<String> manifest = new ArrayList<>();
        for (WorkflowBundle.Entry e : bundle.entries()) {
            switch (e) {
                case WorkflowBundle.File f -> {
                    if (f.size() > WorkflowLimits.FILE_BYTES
                            || !WorkflowBundle.sha256Hex(f.content()).equals(f.sha256())) {
                        throw new ArchiveException("captured file does not match its hash: " + f.path());
                    }
                    manifest.add("{\"path\":" + quote(f.path()) + ",\"type\":\"file\",\"sha256\":\""
                            + f.sha256() + "\",\"size\":" + f.size() + ",\"executable\":" + f.executable() + "}");
                }
                case WorkflowBundle.Directory d ->
                        manifest.add("{\"path\":" + quote(d.path()) + ",\"type\":\"dir\"}");
                case WorkflowBundle.Link l -> manifest.add("{\"path\":" + quote(l.path())
                        + ",\"type\":\"link\",\"target\":" + quote(l.target()) + "}");
            }
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(new CappedOutput(raw, capBytes))) {
            put(zip, MANIFEST_NAME, ("{\"version\":1,\"entries\":[" + String.join(",", manifest) + "]}")
                    .getBytes(StandardCharsets.UTF_8));
            for (WorkflowBundle.Entry e : bundle.entries()) {
                if (e instanceof WorkflowBundle.File f) {
                    put(zip, f.path(), f.content());
                }
            }
        }
        byte[] out = raw.toByteArray();
        return new Archive(out, WorkflowBundle.sha256Hex(out), bundle.entries().size());
    }

    private static void put(ZipOutputStream zip, String name, byte[] body) throws IOException {
        ZipEntry ze = new ZipEntry(name);
        ze.setTime(0L); // deterministic bytes for identical captures
        zip.putNextEntry(ze);
        zip.write(body);
        zip.closeEntry();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20 || c == 0x7f) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** Fails as soon as one byte beyond the cap would be produced (limit+one), before buffering more. */
    private static final class CappedOutput extends OutputStream {
        private final OutputStream out;
        private final long cap;
        private long written;

        CappedOutput(OutputStream out, long cap) {
            this.out = out;
            this.cap = cap;
        }

        @Override
        public void write(int b) throws IOException {
            check(1);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            check(len);
            out.write(b, off, len);
        }

        private void check(int n) throws IOException {
            written += n;
            if (written > cap) {
                throw new ArchiveException("archive exceeds " + cap + " bytes");
            }
        }
    }
}
