package app.drydock.sdlc;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of the supporting resources of one selected workflow.
 * Entries use normalized repository-relative {@code /} paths only; no host
 * absolute path, credential or filesystem handle is representable.
 */
public record WorkflowBundle(List<Entry> entries, int fileCount, int directoryCount, int linkCount, long totalBytes) {

    public WorkflowBundle {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (fileCount < 0 || directoryCount < 0 || linkCount < 0 || totalBytes < 0) {
            throw new IllegalArgumentException("negative count");
        }
    }

    public static WorkflowBundle empty() {
        return new WorkflowBundle(List.of(), 0, 0, 0, 0);
    }

    /** Builds a bundle whose counts are measured from the entries. */
    public static WorkflowBundle of(List<Entry> entries) {
        int files = 0;
        int dirs = 0;
        int links = 0;
        long bytes = 0;
        for (Entry e : entries) {
            switch (e) {
                case File f -> {
                    files++;
                    bytes += f.size();
                }
                case Directory d -> dirs++;
                case Link l -> links++;
            }
        }
        return new WorkflowBundle(entries, files, dirs, links, bytes);
    }

    public sealed interface Entry permits File, Directory, Link {
        String path();
    }

    public record File(String path, byte[] content, String sha256, boolean executable) implements Entry {
        public File {
            path = normalize(path);
            content = Objects.requireNonNull(content, "content").clone();
            Objects.requireNonNull(sha256, "sha256");
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        public long size() {
            return content.length;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof File f && path.equals(f.path) && Arrays.equals(content, f.content)
                    && sha256.equals(f.sha256) && executable == f.executable;
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, Arrays.hashCode(content), sha256, executable);
        }

        @Override
        public String toString() {
            return "File[" + path + ", " + content.length + " bytes, " + sha256 + (executable ? ", exec]" : "]");
        }
    }

    public record Directory(String path) implements Entry {
        public Directory {
            path = normalize(path);
        }
    }

    /** A symlink whose {@code target} is relative and contained in the bundle root. */
    public record Link(String path, String target) implements Entry {
        public Link {
            path = normalize(path);
            Objects.requireNonNull(target, "target");
            if (target.isEmpty() || target.startsWith("/") || target.indexOf('\\') >= 0
                    || target.indexOf('\0') >= 0 || isWindowsAbsolute(target)) {
                throw new IllegalArgumentException("link target must be relative: " + target);
            }
            int depth = 0;
            int base = path.split("/").length - 1;
            for (String seg : target.split("/")) {
                if (seg.equals("..")) {
                    depth--;
                } else if (!seg.isEmpty() && !seg.equals(".")) {
                    depth++;
                }
                if (base + depth < 0) {
                    throw new IllegalArgumentException("link target escapes bundle: " + target);
                }
            }
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isWindowsAbsolute(String s) {
        return s.length() >= 2 && s.charAt(1) == ':' && Character.isLetter(s.charAt(0));
    }

    private static String normalize(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0
                || isWindowsAbsolute(path)) {
            throw new IllegalArgumentException("entry path must be relative: " + path);
        }
        for (String seg : path.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..") || seg.equals(".git")) {
                throw new IllegalArgumentException("entry path not normalized: " + path);
            }
        }
        return path;
    }
}
