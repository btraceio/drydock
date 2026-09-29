package app.drydock;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every diag verb the {@code DrydockApplication} comments advertise has a
 * {@code case} that implements it.
 *
 * <p>Two did not, for a long time. {@code reviewkey} was documented from Task
 * 18 and never wired; {@code comment} was documented, and its implementation
 * ({@code ReviewDiffColumn.diagOpenComposer}) was fully written and had zero
 * callers. Both fell through to a {@code default} that printed
 * {@code "[diag] mark <arg>"}, so a driver script asking for one printed a
 * plausible beacon and did nothing -- a screenshot run that approved nothing
 * looked exactly like one that worked.</p>
 *
 * <p>The default branches now name an unrecognised verb, which makes the NEXT
 * one loud at runtime. This makes it loud at build time instead, for the case
 * nobody runs: a verb can be documented and unwired for two months without
 * anyone typing it.</p>
 *
 * <p>Deliberately one-directional. The comments document a SUBSET -- the
 * explorer block lists six of roughly two dozen cases -- so an unlisted case
 * is fine and a listed verb with no case is not.</p>
 */
class DiagVerbsAreWiredTest {

    /**
     * A table row: {@code //   verb:arg   description}, where the description
     * is separated by at least two spaces. Continuation lines indent far
     * deeper and prose puts one space after the word, so the alignment is
     * what tells a verb from a sentence.
     */
    private static final Pattern TABLE_ROW =
            Pattern.compile("^\\s*//\\s{3}([a-z][a-zA-Z]*)(?::\\S*)?\\s{2,}\\S");

    /** Prose: {@code open (switch to ...), type (insert ...), shot (...)}. */
    private static final Pattern PROSE_ENTRY =
            Pattern.compile("\\b([a-z][a-zA-Z]*) \\(");

    /**
     * A whole case label, which may carry SEVERAL literals: the settings
     * dispatcher has {@code case "uislider", "tslider" ->}. Matching a bare
     * {@code case "x"} reported tslider as unwired when it is not -- the first
     * thing this test found was a bug in itself.
     */
    private static final Pattern CASE_LABEL =
            Pattern.compile("case\\s+((?:\"[^\"]*\"\\s*,\\s*)*\"[^\"]*\")\\s*->");

    private static final Pattern CASE_LITERAL = Pattern.compile("\"([^\"]*)\"");

    @Test
    void everyDocumentedDiagVerbHasACase() throws IOException {
        String source = Files.readString(drydockApplication());
        Set<String> documented = documentedVerbs(source);

        assertFalse(documented.isEmpty(),
                "parsed no verbs at all -- the comment format changed and this test went blind");

        Set<String> wired = caseLabels(source);
        List<String> unwired = documented.stream().filter(verb -> !wired.contains(verb)).toList();

        assertEquals(List.of(), unwired,
                "documented diag verbs with no case in DrydockApplication -- a script asking for "
                        + "one does nothing and says so only at runtime. Wire it or stop "
                        + "documenting it. Parsed " + documented.size() + " verbs: " + documented);
    }

    /**
     * The guard that made the two silent verbs findable at all. Without it an
     * unknown verb is indistinguishable from {@code mark}, which is how both
     * defects survived: the beacon looked right.
     */
    @Test
    void anUnknownVerbIsReportedRatherThanTreatedAsAMark() throws IOException {
        String source = Files.readString(drydockApplication());

        assertTrue(source.contains("UNKNOWN explorerScript verb"),
                "explorerScript's default must name the verb it did not recognise");
        assertTrue(source.contains("UNKNOWN tabScript verb"),
                "tabScript's default must name the verb it did not recognise");
        assertTrue(source.contains("unknown settings verb"),
                "settingsScript's default already did this; it must keep doing it");
        assertTrue(source.contains("case \"mark\""),
                "mark must be a real case: it used to BE the default, so a default that "
                        + "reports unknown verbs would otherwise break every driver's markers");
    }

    /** Every literal that appears in a {@code case ... ->} label. */
    private static Set<String> caseLabels(String source) {
        Set<String> labels = new LinkedHashSet<>();
        Matcher label = CASE_LABEL.matcher(source);
        while (label.find()) {
            Matcher literal = CASE_LITERAL.matcher(label.group(1));
            while (literal.find()) {
                labels.add(literal.group(1));
            }
        }
        return labels;
    }

    private static Set<String> documentedVerbs(String source) {
        Set<String> verbs = new LinkedHashSet<>();
        for (String line : source.lines().toList()) {
            Matcher row = TABLE_ROW.matcher(line);
            if (row.find()) {
                verbs.add(row.group(1));
            }
        }
        // The explorer block is prose rather than a table: everything between
        // "Verbs:" and the sentence that closes the paragraph.
        int end = source.indexOf("Each step's delay is measured from startup");
        int start = end < 0 ? -1 : source.lastIndexOf("Verbs:", end);
        if (start >= 0 && end > start) {
            // Unwrap first: the block is line-wrapped, so "type" can end one
            // line and "(insert ...)" begin the next, and a contiguous
            // "type (" never appears. Missing a verb here fails SILENTLY --
            // the test simply never checks it -- which is the same shape as
            // the defect it exists to catch.
            String prosePart = source.substring(start, end)
                    .replaceAll("(?m)^\\s*//", " ")
                    .replaceAll("\\s+", " ");
            Matcher prose = PROSE_ENTRY.matcher(prosePart);
            while (prose.find()) {
                verbs.add(prose.group(1));
            }
        }
        return verbs;
    }

    /** The test's working directory is the {@code app} module. */
    private static Path drydockApplication() {
        Path relative = Path.of("src/main/java/app/drydock/DrydockApplication.java");
        if (Files.exists(relative)) {
            return relative;
        }
        Path fromRoot = Path.of("app").resolve(relative);
        assertTrue(Files.exists(fromRoot), "cannot find DrydockApplication.java from "
                + Path.of("").toAbsolutePath());
        return fromRoot;
    }
}
