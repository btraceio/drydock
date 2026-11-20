package app.drydock.sdlc;

/** Settled fixed workflow ceilings; every value has exact-limit and limit-plus-one tests. */
public final class WorkflowLimits {
    public static final int CONFIG_BYTES = 256 * 1024;
    public static final int JSON_DEPTH = 32;
    public static final int PROMPT_BYTES = 128 * 1024;
    public static final int INCLUDE_ROOTS = 64;
    public static final int BUNDLE_ENTRIES = 2048;
    public static final long FILE_BYTES = 8L * 1024 * 1024;
    public static final long AGGREGATE_FILE_BYTES = 32L * 1024 * 1024;
    /** Resource traversal depth and symlink-chain depth. */
    public static final int TRAVERSAL_DEPTH = 32;
    public static final int PATH_UTF8_BYTES = 1024;
    public static final long DEADLINE_MILLIS = 15_000;
    public static final int RETAINED_BUNDLES_PER_SESSION = 16;
    public static final long RETAINED_SESSION_BYTES = 256L * 1024 * 1024;
    public static final long RETAINED_AGGREGATE_BYTES = 1024L * 1024 * 1024;

    private WorkflowLimits() {
    }
}
