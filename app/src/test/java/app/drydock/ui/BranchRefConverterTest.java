package app.drydock.ui;

import app.drydock.git.BranchRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editable ComboBox writes {@code toString} into its editor, and the
 * modal derives create-vs-checkout mode from that editor text -- so the
 * converter must be the identity on the branch name. Decoration lives only
 * in {@link BranchRefConverter#describe}, used by the cell factory.
 */
class BranchRefConverterTest {

    private final BranchRefConverter converter = new BranchRefConverter();

    @Test
    void toStringIsTheBareBranchNameEvenWhenTheBranchIsOccupied() {
        BranchRef occupied = new BranchRef("main", false, Optional.of(Path.of("/src/olifer")), false);

        assertEquals("main", converter.toString(occupied));
        assertEquals("origin/feature/x", converter.toString(BranchRef.remote("origin/feature/x")));
        assertEquals("", converter.toString(null));
    }

    @Test
    void fromStringRoundTripsToStringExactly() {
        BranchRef branch = BranchRef.local("feat/minors");

        assertEquals(branch.name(), converter.fromString(converter.toString(branch)).name());
        assertEquals("typed-by-hand", converter.fromString("typed-by-hand").name());
    }

    @Test
    void describeAnnotatesOccupiedAndStaleBranchesForTheDropdownOnly() {
        BranchRef occupied = new BranchRef("main", false, Optional.of(Path.of("/src/olifer")), false);
        BranchRef stale = new BranchRef("ghost", false, Optional.of(Path.of("/gone")), true);

        assertTrue(BranchRefConverter.describe(occupied).contains("in use"));
        assertTrue(BranchRefConverter.describe(occupied).contains("/src/olifer"));
        assertTrue(BranchRefConverter.describe(stale).contains("stale"));
        assertEquals("idle", BranchRefConverter.describe(BranchRef.local("idle")));
    }
}
