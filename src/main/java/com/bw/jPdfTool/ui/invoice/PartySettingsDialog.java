package com.bw.jPdfTool.ui.invoice;

import com.bw.jPdfTool.I18n;
import com.bw.jPdfTool.Log;
import com.bw.jPdfTool.invoice.InvoiceData;
import com.bw.jPdfTool.invoice.Party;
import com.bw.jPdfTool.invoice.PartyPresets;
import com.bw.jPdfTool.invoice.Role;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the party presets of the invoice editor: one list for sellers and buyers, and an editor for the
 * selected entry. Each entry says if it is offered as seller, as buyer or both.<br>
 * The presets are stored in {@link InvoiceData#partiesFile()} when the dialog is closed with "Save".
 */
public class PartySettingsDialog extends JDialog {

    private static String title() {
        return I18n.t("party.title");
    }

    private final Path file;
    /**
     * Outcome of the dialog.
     *
     * @param saved    True if the presets were saved.
     * @param selected The entry selected when saving, null if not saved or nothing selected.
     */
    public record Result(boolean saved, Party selected) {
    }

    private final DefaultListModel<Party> model = new DefaultListModel<>();
    private final JList<Party> list = new JList<>(model);
    private final Map<Role, JCheckBox> roleBoxes = new EnumMap<>(Role.class);
    private final PartyPanel editor;
    private final JButton remove = new JButton(I18n.t("redact.remove"));
    private final JButton copy = new JButton(I18n.t("party.copy"));
    /**
     * The entries as loaded, to reset an entry when a copy is made from its edited values.
     */
    private final Map<Party, Party> originals = new IdentityHashMap<>();

    private boolean dirty;
    private Party savedSelection;
    private boolean saved;
    private boolean updating;

    private PartySettingsDialog(Window owner, Path file, List<Party> parties) {
        super(owner, title(), Dialog.ModalityType.APPLICATION_MODAL);
        this.file = file;
        // Work on copies, "Cancel" leaves the presets untouched.
        for (Party s : parties) {
            Party c = s.copy();
            model.addElement(c);
            originals.put(c, s.copy());
        }

        JPanel roles = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        for (Role role : Role.values()) {
            JCheckBox box = new JCheckBox(role.title());
            box.setToolTipText(I18n.t("party.offer." + role.name().toLowerCase()));
            box.addActionListener(e -> {
                Party p = list.getSelectedValue();
                if (updating || p == null)
                    return;
                p.setRole(role, box.isSelected());
                dirty = true;
                list.repaint();
            });
            roleBoxes.put(role, box);
            if (roles.getComponentCount() > 0)
                roles.add(Box.createHorizontalStrut(12));
            roles.add(box);
        }
        // Seller form: it has all fields, a buyer simply doesn't use contact and bank account.
        editor = new PartyPanel(true, I18n.t("party.useAs"), roles, true);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Party p = (Party) value;
                super.getListCellRendererComponent(l, displayName(p) + roleSuffix(p), index, isSelected, cellHasFocus);
                if (p != null && (p.name == null || p.name.isBlank() || !(p.asSeller || p.asBuyer)))
                    setFont(getFont().deriveFont(Font.ITALIC));
                return this;
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting())
                selectionChanged();
        });

        editor.setOnChange(() -> {
            dirty = true;
            // The name is shown in the list.
            list.repaint();
        });

        JButton add = new JButton(I18n.t("party.add"));
        add.addActionListener(e -> addParty());
        remove.addActionListener(e -> removeParty());
        copy.setToolTipText(I18n.t("party.copy.tip"));
        copy.addActionListener(e -> createCopy());
        JPanel listButtons = new JPanel(new GridLayout(1, 3, 6, 0));
        listButtons.add(add);
        listButtons.add(copy);
        listButtons.add(remove);

        JScrollPane listScroll = new JScrollPane(list);
        int em = list.getFontMetrics(list.getFont()).charWidth('m');
        listScroll.setPreferredSize(new Dimension(26 * em, 10));
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.add(listScroll, BorderLayout.CENTER);
        left.add(listButtons, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(10, 0));
        center.add(left, BorderLayout.WEST);
        JPanel editorPanel = new JPanel(new BorderLayout());
        editorPanel.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground")));
        editorPanel.add(editor, BorderLayout.NORTH);
        center.add(editorPanel, BorderLayout.CENTER);

        JLabel location = new JLabel(I18n.t("party.storedIn", file.toAbsolutePath()));
        location.setForeground(UIManager.getColor("Label.disabledForeground"));
        JButton save = new JButton(I18n.t("common.save"));
        save.addActionListener(e -> save());
        JButton cancel = new JButton(I18n.t("common.cancel"));
        cancel.addActionListener(e -> cancel());
        getRootPane().setDefaultButton(save);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        buttons.add(cancel);
        buttons.add(save);
        JPanel bottom = new JPanel(new BorderLayout(10, 0));
        bottom.add(location, BorderLayout.CENTER);
        bottom.add(buttons, BorderLayout.EAST);

        JPanel main = new JPanel(new BorderLayout(0, 10));
        main.setBorder(BorderFactory.createEmptyBorder(12, 12, 10, 12));
        main.add(center, BorderLayout.CENTER);
        main.add(bottom, BorderLayout.SOUTH);
        setContentPane(main);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancel();
            }
        });
        getRootPane().registerKeyboardAction(e -> cancel(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        if (!model.isEmpty())
            list.setSelectedIndex(0);
        selectionChanged();
        pack();
        setMinimumSize(getSize());
    }

    /**
     * Shows the dialog (modal).
     *
     * @return True if the presets were saved.
     */
    public static boolean showDialog(Component parent) {
        return showDialog(parent, null, null, null).saved();
    }

    /**
     * Shows the dialog (modal) with the values of a party of an invoice taken over into the presets (not yet saved):
     * the hint or best matching entry is updated, if there is none a new entry is added.
     *
     * @param proposal Values to take over, null to just edit the presets.
     * @param hint     The preset the party was chosen from, may be null.
     * @param role     Role of the party in the invoice.
     */
    public static Result showDialog(Component parent, Party proposal, Party hint, Role role) {
        Window owner = parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        Path file = InvoiceData.partiesFile();
        List<Party> parties;
        try {
            parties = InvoiceData.loadParties(file);
        } catch (Exception e) {
            Log.exception(e);
            JOptionPane.showMessageDialog(owner,
                    I18n.t("party.cannotRead", file, e.getMessage()),
                    title(), JOptionPane.WARNING_MESSAGE);
            parties = new ArrayList<>();
        }
        PartySettingsDialog dialog = new PartySettingsDialog(owner, file, parties);
        if (proposal != null)
            dialog.applyProposal(proposal, hint, role);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return new Result(dialog.saved, dialog.savedSelection);
    }

    /**
     * Name and label to show for a party in lists.
     */
    static String displayName(Party p) {
        if (p == null)
            return "";
        return p.name == null || p.name.isBlank() ? I18n.t("party.noName") : p.displayName();
    }

    private void applyProposal(Party proposal, Party hint, Role role) {
        List<Party> entries = Collections.list(model.elements());
        Party target = PartyPresets.applyProposal(entries, proposal, hint, role);
        refreshModel(entries);
        select(target);
        // An update with the same values is no change.
        dirty = !target.sameData(originals.get(target));
        editor.getNameField().requestFocusInWindow();
    }

    private void refreshModel(List<Party> entries) {
        model.clear();
        entries.forEach(model::addElement);
    }

    private void select(Party p) {
        list.setSelectedValue(p, true);
        selectionChanged();
    }

    private void createCopy() {
        Party p = list.getSelectedValue();
        if (p == null)
            return;
        List<Party> entries = Collections.list(model.elements());
        Party c = PartyPresets.createCopy(entries, p, originals.get(p));
        refreshModel(entries);
        select(c);
        dirty = true;
        editor.getLabelField().requestFocusInWindow();
        editor.getLabelField().selectAll();
    }

    /**
     * Shows the role in the list if the entry is not offered for both.
     */
    static String roleSuffix(Party p) {
        if (p == null || (p.asSeller && p.asBuyer))
            return "";
        if (p.asSeller)
            return "  " + I18n.t("party.suffix.seller");
        if (p.asBuyer)
            return "  " + I18n.t("party.suffix.buyer");
        return "  " + I18n.t("party.suffix.none");
    }

    private void selectionChanged() {
        Party p = list.getSelectedValue();
        // Fields that differ from the saved entry are marked, a new entry has nothing to compare with.
        editor.setBaseline(p == null ? null : originals.get(p));
        editor.setParty(p);
        updating = true;
        try {
            for (var e : roleBoxes.entrySet()) {
                e.getValue().setSelected(p != null && p.hasRole(e.getKey()));
                e.getValue().setEnabled(p != null);
            }
        } finally {
            updating = false;
        }
        remove.setEnabled(p != null);
        copy.setEnabled(p != null);
    }

    private void addParty() {
        model.addElement(new Party());
        list.setSelectedIndex(model.size() - 1);
        list.ensureIndexIsVisible(model.size() - 1);
        dirty = true;
        editor.getNameField().requestFocusInWindow();
    }

    private void removeParty() {
        int idx = list.getSelectedIndex();
        if (idx < 0)
            return;
        int answer = JOptionPane.showConfirmDialog(this, I18n.t("party.removeQuestion", displayName(model.get(idx))),
                getTitle(), JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION)
            return;
        model.remove(idx);
        dirty = true;
        if (!model.isEmpty())
            list.setSelectedIndex(Math.min(idx, model.size() - 1));
        selectionChanged();
    }

    private void save() {
        String duplicate = PartyPresets.findDuplicate(Collections.list(model.elements()));
        if (duplicate != null) {
            JOptionPane.showMessageDialog(this,
                    I18n.t("party.duplicate", duplicate),
                    getTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            InvoiceData.saveParties(file, Collections.list(model.elements()));
            savedSelection = list.getSelectedValue() == null ? null : list.getSelectedValue().copy();
            saved = true;
            dispose();
        } catch (Exception e) {
            Log.exception(e);
            JOptionPane.showMessageDialog(this,
                    I18n.t("party.cannotSave", file, e.getMessage()),
                    getTitle(), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void cancel() {
        if (dirty) {
            int answer = JOptionPane.showConfirmDialog(this, I18n.t("party.discard"), getTitle(),
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.YES_OPTION)
                return;
        }
        dispose();
    }
}
