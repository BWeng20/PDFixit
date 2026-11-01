package com.bw.jPdfTool.ui;

import com.bw.jPdfTool.Preferences;

import javax.swing.JCheckBoxMenuItem;

/**
 * A Menu Checkbox that is bound to a boolean preferences values.
 */
public class JPrefCheckBoxMenuItem extends JCheckBoxMenuItem {

    final String prefKey;
    final boolean defaultValue;

    public JPrefCheckBoxMenuItem(String label, final String prefKey, final boolean defaultValue) {
        super(label);
        this.prefKey = prefKey;
        this.defaultValue = defaultValue;
        update();
        addActionListener(e -> Preferences.getInstance().set(prefKey, isSelected()));
    }

    private final java.beans.PropertyChangeListener prefListener = evt -> update();

    // The listener lives only while the item is displayed, so a disposed window is not kept by the preferences.
    @Override
    public void addNotify() {
        super.addNotify();
        Preferences.getInstance().addPropertyChangeListener(prefListener, prefKey);
        update();
    }

    @Override
    public void removeNotify() {
        Preferences.getInstance().removePropertyChangeListener(prefListener, prefKey);
        super.removeNotify();
    }

    protected void update() {
        boolean val = Preferences.getInstance().getBoolean(prefKey, defaultValue);
        if (val != isSelected())
            setSelected(val);
    }
}
