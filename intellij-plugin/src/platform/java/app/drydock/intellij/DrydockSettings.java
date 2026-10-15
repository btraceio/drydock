package app.drydock.intellij;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.Nullable;

/** Probe settings state. Single-writer discipline lives here until the real store lands. */
@State(name = "DrydockSettings", storages = @Storage("drydock.xml"))
public final class DrydockSettings implements PersistentStateComponent<DrydockSettings.State> {

    public static final class State {
        public String repositoriesRoot = System.getProperty("user.home");
    }

    private State state = new State();

    public static DrydockSettings getInstance() {
        return ApplicationManager.getApplication().getService(DrydockSettings.class);
    }

    public String repositoriesRoot() {
        return state.repositoriesRoot;
    }

    public void setRepositoriesRoot(String value) {
        state.repositoriesRoot = value;
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(State loaded) {
        state = loaded;
    }
}
