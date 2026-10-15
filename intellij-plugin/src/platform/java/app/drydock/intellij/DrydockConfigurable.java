package app.drydock.intellij;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/** Probe settings: where the repo manager looks for workspaces. Real shape decided later. */
public final class DrydockConfigurable implements Configurable {

    private JTextField rootField;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Drydock";
    }

    @Override
    public @Nullable JComponent createComponent() {
        rootField = new JTextField(DrydockSettings.getInstance().repositoriesRoot());
        JPanel panel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        panel.add(new JLabel("Repositories root:"));
        panel.add(rootField);
        rootField.setColumns(40);
        return panel;
    }

    @Override
    public boolean isModified() {
        return rootField != null && !rootField.getText().strip().equals(DrydockSettings.getInstance().repositoriesRoot());
    }

    @Override
    public void apply() throws ConfigurationException {
        String value = rootField.getText().strip();
        if (value.isEmpty()) {
            throw new ConfigurationException("Repositories root must not be empty");
        }
        DrydockSettings.getInstance().setRepositoriesRoot(value);
    }

    @Override
    public void reset() {
        rootField.setText(DrydockSettings.getInstance().repositoriesRoot());
    }
}
