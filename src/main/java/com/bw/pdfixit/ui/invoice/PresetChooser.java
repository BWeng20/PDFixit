package com.bw.pdfixit.ui.invoice;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.invoice.InvoiceData;
import com.bw.pdfixit.invoice.Party;
import com.bw.pdfixit.invoice.PartyPresets;
import com.bw.pdfixit.invoice.Role;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Combo box with the seller or buyer presets and buttons to take over changes and to manage them.<br>
 * Choosing a preset copies its values into the target party. Editing the target never changes the selection.
 */
class PresetChooser extends JPanel {

    /**
     * Combo box entry for "no preset".
     */
    private static final Party NO_PRESET = new Party();

    /**
     * All choosers, to fill them again when the presets were changed.
     */
    private static final Set<PresetChooser> INSTANCES = Collections.newSetFromMap(new WeakHashMap<>());

    private final Role role;
    private final Party target;
    private final Consumer<Party> currentValues;
    private final JComboBox<Party> combo = new JComboBox<>();
    private final JButton update = new JButton();
    private List<Party> presets = List.of();
    private boolean updating;

    /**
     * @param role      Shows the presets offered for this role.
     * @param target    Receives the values of a chosen preset.
     * @param onApplied Called after a preset was copied into the target (to show the new values).
     */
    PresetChooser(Role role, Party target, Runnable onApplied) {
        this(role, target, onApplied, null);
    }

    /**
     * @param currentValues Adds more values of the invoice to the party, which is taken over into a preset
     *                      (e.g. the payment conditions of a buyer). May be null.
     */
    PresetChooser(Role role, Party target, Runnable onApplied, Consumer<Party> currentValues) {
        super(new BorderLayout(6, 0));
        this.role = role;
        this.target = target;
        this.currentValues = currentValues;
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                String text = value == NO_PRESET || value == null ? "-" : PartySettingsDialog.displayName((Party) value);
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
            }
        });
        combo.setToolTipText(I18n.t("preset.fill." + role.name().toLowerCase()));
        reload(PartyPresets.find(InvoiceData.loadParties(role), target, role));
        combo.addActionListener(e -> {
            if (updating)
                return;
            if (combo.getSelectedItem() instanceof Party preset && preset != NO_PRESET) {
                target.copyFrom(preset);
                onApplied.run();
            }
            refreshButton();
        });

        update.addActionListener(e -> edit(proposal()));
        JButton manage = new JButton(I18n.t("preset.manage"));
        manage.setToolTipText(I18n.t("preset.manage.tip"));
        manage.addActionListener(e -> edit(null));
        INSTANCES.add(this);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        buttons.add(update);
        buttons.add(manage);
        add(combo, BorderLayout.CENTER);
        add(buttons, BorderLayout.EAST);
        refreshButton();
    }

    /**
     * The party with all current values of the invoice, which belong into a preset.
     */
    private Party proposal() {
        Party p = target.copy();
        if (currentValues != null)
            currentValues.accept(p);
        return p;
    }

    /**
     * Opens the parties, with the values of the proposal taken over if there is one.
     * The presets changed, so all choosers (seller and buyer) are filled again.
     */
    private void edit(Party proposal) {
        PartySettingsDialog.Result r = PartySettingsDialog.showDialog(this, proposal, getSelected(), role);
        if (!r.saved())
            return;
        for (PresetChooser c : List.copyOf(INSTANCES))
            c.reload(c == this && r.selected() != null && r.selected().hasRole(role) ? r.selected() : c.keepSelection());
    }

    /**
     * The selected preset if the party still belongs to it, else the one that fits the party best.
     * The selection comes from before the presets were changed, so the entry may have been renamed.
     */
    private Party keepSelection() {
        return PartyPresets.find(InvoiceData.loadParties(role), target, role);
    }

    /**
     * The selected preset, null if none is selected.
     */
    Party getSelected() {
        return combo.getSelectedItem() instanceof Party p && p != NO_PRESET ? p : null;
    }

    /**
     * Name and label of the selected preset, null if none is selected.
     */
    String getSelectedKey() {
        Party p = getSelected();
        return p == null ? null : key(p);
    }

    /**
     * Shows if the changes of the target update a preset or create a new one.
     * Called after the target was edited. The selection of the combo box stays as it is.
     */
    void refreshButton() {
        Party p = getSelected();
        if (p == null)
            p = Party.findBestMatch(presets, target, role);
        boolean exists = p != null;
        update.setText(exists ? I18n.t("preset.update") : I18n.t("preset.create"));
        update.setToolTipText(exists
                ? I18n.t("preset.update.tip", PartySettingsDialog.displayName(p))
                : I18n.t("preset.add." + role.name().toLowerCase()));
    }

    /**
     * Fills the combo box again, without changing the target.
     *
     * @param select Preset to select (matched by name and label), null for none.
     */
    private void reload(Party select) {
        updating = true;
        try {
            presets = InvoiceData.loadParties(role);
            combo.removeAllItems();
            combo.addItem(NO_PRESET);
            Party selected = NO_PRESET;
            for (Party p : presets) {
                combo.addItem(p);
                if (selected == NO_PRESET && select != null && p.sameIdentity(select))
                    selected = p;
            }
            combo.setSelectedItem(selected);
        } finally {
            updating = false;
        }
        refreshButton();
    }

    /**
     * Text to remember a preset: name and label.
     */
    static String key(Party p) {
        return p.displayName();
    }

    /**
     * Finds the preset with this key, or the first one with this name.
     *
     * @return The preset or null.
     */
    static Party find(List<Party> presets, String key) {
        for (Party p : presets)
            if (key(p).equalsIgnoreCase(key == null ? "" : key.trim()))
                return p;
        for (Party p : presets)
            if (Party.sameName(p.name, key))
                return p;
        return null;
    }
}
