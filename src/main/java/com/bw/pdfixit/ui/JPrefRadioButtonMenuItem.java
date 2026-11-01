package com.bw.pdfixit.ui;

import com.bw.pdfixit.Preferences;

import javax.swing.JRadioButtonMenuItem;

/**
 * A Menu Radio Button that is bound to a boolean preferences values.
 */
public class JPrefRadioButtonMenuItem extends JRadioButtonMenuItem {

    final String prefKey;
    final boolean negate;
    final boolean defaultValue;

    public JPrefRadioButtonMenuItem(String label, String key, boolean negate, boolean defaultValue) {
        super(label);
        this.prefKey = key;
        this.negate = negate;
        this.defaultValue = defaultValue;

        update();
        addActionListener(e -> {
            boolean sel = isSelected();
            if (negate)
                sel = !sel;
            Preferences.getInstance().set(prefKey, sel);
        });
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
        boolean val = Preferences.getInstance().getBoolean(prefKey, negate == (!defaultValue));
        if (negate)
            val = !val;
        if (val != isSelected())
            setSelected(val);
    }

}
