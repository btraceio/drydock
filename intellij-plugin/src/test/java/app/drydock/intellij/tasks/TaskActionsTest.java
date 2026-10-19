package app.drydock.intellij.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskActionsTest {

    @Test
    void removeQuestionNamesTheAgentsThatKeepRunning() {
        assertEquals("Remove drydock from Drydock? 2 agents keep running.", TaskActions.removeQuestion("drydock", 2));
        assertEquals("Remove drydock from Drydock? 1 agent keeps running.", TaskActions.removeQuestion("drydock", 1));
    }

    @Test
    void toolbarButtonsStayPutAndGreyOutContextMenusHide() {
        assertEquals(new TaskActions.Shown(true, false), TaskActions.shown(false, false), "toolbar, not applicable");
        assertEquals(new TaskActions.Shown(true, true), TaskActions.shown(false, true), "toolbar, applicable");
        assertEquals(new TaskActions.Shown(false, false), TaskActions.shown(true, false), "menu, not applicable");
        assertEquals(new TaskActions.Shown(true, true), TaskActions.shown(true, true), "menu, applicable");
    }
}
