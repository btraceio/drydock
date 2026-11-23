package app.drydock.babysit;

import java.nio.file.Path;

/**
 * A second process for {@link BabysitStateFileTest}: charges one rerun each
 * of {@code count} distinct run ids starting at {@code firstRunId}, one
 * locked transaction each, into the babysitting state file at {@code file}.
 * Only the OS file lock stands between it and the test's own charges.
 */
public final class BabysitStateWriterProcess {

    private BabysitStateWriterProcess() {
    }

    public static void main(String[] args) throws Exception {
        BabysitStateFile state = new BabysitStateFile(Path.of(args[0]));
        long firstRunId = Long.parseLong(args[1]);
        int count = Integer.parseInt(args[2]);
        for (int i = 0; i < count; i++) {
            if (!(state.chargeRerun(firstRunId + i, BabysitStateFile.MAX_RERUNS_PER_RUN)
                    instanceof BabysitStateFile.RerunCharge.Charged)) {
                throw new IllegalStateException("run " + (firstRunId + i) + " was not charged");
            }
        }
    }
}
