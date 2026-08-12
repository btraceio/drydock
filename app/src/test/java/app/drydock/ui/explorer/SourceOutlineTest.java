package app.drydock.ui.explorer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The file shape skim mode and the minimap are both derived from. */
class SourceOutlineTest {

    private static final String SIDEBAR = """
            package ui;

            final class Sidebar {

                private static final int MIN_W = 220;

                int width() {
                    return (int) sizing.clamp(widthProperty.get());
                }

                void onRelease(MouseEvent e) {
                    widthProperty.set(tracker.raw());
                    tracker = null;
                }

                private void snapToGuide() {
                    // untouched helper
                }
            }
            """;

    @Test
    void aMemberOpenedAndClosedOnOneLineDoesNotSwallowTheNextOne() {
        SourceOutline outline = SourceOutline.parse("""
                final class Compact {
                    @Override public String toString() { return "x"; }

                    void afterTheOneLiner() {
                        doWork();
                    }
                }
                """);
        // The one-liner used to stay "pending" past its own closing brace and
        // then claim the NEXT member's, so afterTheOneLiner lost its row
        // entirely -- invisible in skim mode and in the minimap.
        assertEquals(List.of("@Override public String toString() { return \"x\"; }",
                        "void afterTheOneLiner() {"),
                outline.members().stream().map(SourceOutline.Member::signature).toList());
        assertEquals(2, outline.members().get(0).startLine());
        assertEquals(2, outline.members().get(0).endLine());
        assertEquals(4, outline.members().get(1).startLine());
    }

    @Test
    void membersAreTheTopLevelDeclarationsOfTheType() {
        SourceOutline outline = SourceOutline.parse(SIDEBAR);
        assertEquals(List.of("private static final int MIN_W = 220;", "int width() {",
                        "void onRelease(MouseEvent e) {", "private void snapToGuide() {"),
                outline.members().stream().map(SourceOutline.Member::signature).toList());
    }

    @Test
    void aMemberSpansToItsClosingBrace() {
        SourceOutline outline = SourceOutline.parse(SIDEBAR);
        SourceOutline.Member width = outline.members().get(1);
        assertEquals(7, width.startLine());
        assertEquals(9, width.endLine());
        assertEquals(3, width.lines());
        assertTrue(width.covers(8));
        assertFalse(width.covers(10));
    }

    @Test
    void privateHelpersAreMarkedSoTheyCanBeGrouped() {
        SourceOutline outline = SourceOutline.parse(SIDEBAR);
        assertTrue(outline.members().get(0).privateHelper(), "a private field is private too");
        assertFalse(outline.members().get(1).privateHelper());
        assertTrue(outline.members().get(3).privateHelper());
    }

    @Test
    void changedMembersAreTheOnesCoveringAChangedLine() {
        SourceOutline outline = SourceOutline.parse(SIDEBAR);
        Set<Integer> changed = Set.of(12);
        assertFalse(outline.members().get(1).isChanged(changed));
        assertTrue(outline.members().get(2).isChanged(changed), "line 12 is inside onRelease");
    }

    @Test
    void aTrailingCommentCannotMoveTheBraceDepth() {
        SourceOutline outline = SourceOutline.parse("""
                class A {
                    void a() { // opens nothing: }
                        run();
                    }

                    void b() {
                    }
                }
                """);
        assertEquals(List.of("void a() {", "void b() {"),
                outline.members().stream().map(SourceOutline.Member::signature).toList());
    }

    @Test
    void aFileWithNoBracesIsOneMember() {
        SourceOutline outline = SourceOutline.parse("# Title\n\nsome prose\n");
        assertEquals(1, outline.members().size());
        assertEquals("(whole file)", outline.members().get(0).signature());
        assertEquals(1, outline.members().get(0).startLine());
    }

    @Test
    void anEmptyFileStillHasALineCount() {
        SourceOutline outline = SourceOutline.parse("");
        assertEquals(1, outline.lineCount());
        assertEquals(1, outline.members().size());
    }

    @Test
    void memberAtIsEmptyBetweenMembers() {
        SourceOutline outline = SourceOutline.parse(SIDEBAR);
        assertTrue(outline.memberAt(1).isEmpty(), "the package line belongs to no member");
        assertEquals("int width() {", outline.memberAt(8).orElseThrow().signature());
    }

    @Test
    void memberAtOrAfterReturnsTheContainingMemberWhenThereIsOne() {
        SourceOutline outline = SourceOutline.parse(SIDEBAR);
        assertEquals("int width() {", outline.memberAtOrAfter(8).orElseThrow().signature(),
                "line 8 is inside width()");
    }

    @Test
    void memberAtOrAfterFallsForwardToTheNextMemberBetweenMembers() {
        SourceOutline outline = SourceOutline.parse(SIDEBAR);
        // Line 6 is the blank line between MIN_W and width() -- inside no
        // member, so "reveal line 6" should mean the next one down.
        assertEquals("int width() {", outline.memberAtOrAfter(6).orElseThrow().signature());
    }

    @Test
    void memberAtOrAfterFindsTheFirstMemberFromBeforeTheFile() {
        SourceOutline outline = SourceOutline.parse(SIDEBAR);
        // Line 1 is `package ui;`, before every member -- exactly the open
        // path's jumpToLine=1 case that used to reveal nothing at all.
        assertEquals("private static final int MIN_W = 220;",
                outline.memberAtOrAfter(1).orElseThrow().signature());
    }

    @Test
    void memberAtOrAfterIsEmptyPastTheLastMember() {
        SourceOutline outline = SourceOutline.parse(SIDEBAR);
        assertTrue(outline.memberAtOrAfter(outline.lineCount() + 1).isEmpty(),
                "nothing starts after the end of the file");
    }

    @Test
    void memberAtOrAfterOnAnOutlineWithNoMembers() {
        // parse() never actually produces an empty member list (a file with
        // no braces still gets the "(whole file)" member), so this pins the
        // lookup's behaviour directly against the degenerate case rather
        // than relying on parse() to manufacture it.
        SourceOutline outline = new SourceOutline(List.of(), 1);
        assertTrue(outline.memberAtOrAfter(1).isEmpty());
    }
}
