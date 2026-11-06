package com.bw.pdfixit.ui.invoice;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.Preferences;
import com.bw.pdfixit.invoice.InvoiceData;
import com.bw.pdfixit.invoice.Party;
import com.bw.pdfixit.invoice.Role;
import com.bw.pdfixit.ui.UI;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The form to create or edit an e-invoice (ZUGFeRD/Factur-X, profile EN 16931 or XRechnung), shown in the
 * {@link InvoiceEditorPanel}. It edits one {@link InvoiceData}, for another invoice a new editor is created.<br>
 * The fields are made by {@link InvoiceFields} and arranged in tabs ({@link InvoiceDetailsPanel}, seller, buyer,
 * {@link PaymentPanel}, all fields of the XML), the items by {@link ItemsPanel}; the editor adds the sums and the
 * status and knows if the invoice was changed.<br>
 * The seller and bank data is remembered for the next new invoice.
 */
class InvoiceEditor extends JPanel {

    /**
     * Preference: name of the seller preset used last, it is the default for new invoices.
     */
    private static final String PREF_LAST_SELLER = "invoice-last-seller";

    /**
     * Preferred width of the tables in character widths. The editor is narrow, the columns share the width.
     */
    static final int TABLE_WIDTH_IN_EM = 40;

    private final InvoiceData data;
    private final Locale locale = Locale.getDefault();

    private final InvoiceFields fields;
    private final ItemsPanel items;
    private final JLabel netValue = amountLabel(false);
    private final JLabel vatValue = amountLabel(false);
    private final JLabel grossValue = amountLabel(true);
    private final JLabel status = new JLabel();
    private final JTextArea statusText = new JTextArea();

    private final XmlFieldsPanel xmlFields;

    private boolean dirty;
    private int editCount;
    private boolean working;
    /**
     * The invoice XML when the originals were captured: the invoice of the document. Null if it couldn't be created.
     */
    private byte[] baselineXml;
    private Runnable changeListener;
    /**
     * Why the sums can't be calculated, null if they can.
     */
    private String totalsError;
    /**
     * Delays the update while the user types: it processes the whole invoice.
     */
    private final javax.swing.Timer updateTimer = new javax.swing.Timer(200, e -> update());

    {
        updateTimer.setRepeats(false);
    }

    /**
     * @param data The invoice to edit, see {@link #newInvoice()} for a new one.
     */
    InvoiceEditor(InvoiceData data) {
        super(new BorderLayout(0, 10));
        this.data = data;
        this.fields = new InvoiceFields(data, this::changed);
        JPanel main = this;

        JPanel north = new JPanel(new BorderLayout(0, 8));
        // As high as the selected tab needs, not as the highest one: no empty space between a short tab and the items.
        JTabbedPane tabs = new JTabbedPane() {
            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                Component selected = getSelectedComponent();
                if (selected == null)
                    return d;
                int highest = 0;
                for (int i = 0; i < getTabCount(); ++i)
                    highest = Math.max(highest, getComponentAt(i).getPreferredSize().height);
                return new Dimension(d.width, d.height - highest + selected.getPreferredSize().height);
            }
        };
        tabs.addChangeListener(e -> tabs.revalidate());
        tabs.addTab(I18n.t("ui.invoice.tab.invoice"), new InvoiceDetailsPanel(fields));
        tabs.addTab(I18n.t("ui.invoice.role.seller"), fields.party(Role.SELLER));
        tabs.addTab(I18n.t("ui.invoice.role.buyer"), fields.party(Role.BUYER));
        tabs.addTab(I18n.t("editor.tab.payment"), new PaymentPanel(fields));
        // All values of the XML, also those without a field in the other tabs.
        xmlFields = new XmlFieldsPanel(data, () -> {
            data.readFromDocument();
            reloadForm();
            fields.selectMatchingPresets();
            changed();
        });
        tabs.addTab(I18n.t("editor.tab.allFields"), xmlFields);
        tabs.setToolTipTextAt(tabs.getTabCount() - 1, I18n.t("editor.tab.allFields.tip"));
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() == xmlFields) {
                stopEditing();
                xmlFields.refresh();
            }
        });
        north.add(tabs, BorderLayout.CENTER);

        main.add(north, BorderLayout.NORTH);
        items = new ItemsPanel(fields);
        main.add(createItems(), BorderLayout.CENTER);

        reloadForm();
        update();
        // Filling the fields is no change by the user.
        dirty = false;
        captureOriginals();
    }

    /**
     * A new invoice, with the seller used last time and one empty item.
     */
    static InvoiceData newInvoice() {
        InvoiceData data = new InvoiceData();
        applyDefaultPreset(data);
        data.lines.add(ItemsPanel.newLine(data));
        return data;
    }

    InvoiceData getData() {
        return data;
    }

    /**
     * If the invoice was changed since the editor was created, attached or saved.
     */
    boolean isDirty() {
        return dirty;
    }

    /**
     * The number of edits so far (a change of a field, or an imported XML). Unlike {@link #isDirty()} it is not changed
     * by the program, so the panel can tell if the user edited the invoice.
     */
    int editCount() {
        return editCount;
    }

    /**
     * @param listener Called after a change of the invoice (the sums are updated a moment after typing), may be null.
     */
    void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    /**
     * If the invoice differs from the one when the editor was created or the invoice was attached to the document.
     * The marked fields are the details of the difference.
     */
    boolean isModified() {
        byte[] now = currentXml();
        return now == null || baselineXml == null ? dirty : !java.util.Arrays.equals(now, baselineXml);
    }

    private byte[] currentXml() {
        try {
            return data.toXml();
        } catch (Exception e) {
            return null;
        }
    }

    void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    /**
     * The invoice was attached to the document: the current values are the original ones now.
     */
    void markAttached() {
        stopEditing();
        captureOriginals();
        dirty = false;
        update();
    }

    /**
     * Replaces the content by an invoice XML. Nothing is changed if the XML is no valid invoice.
     *
     * @throws IOException If the XML can't be read or is no CII invoice.
     */
    void replaceXml(byte[] xml) throws IOException {
        stopEditing();
        data.replaceXml(xml);
        reloadForm();
        // Another seller and buyer: the presets that fit them.
        fields.selectMatchingPresets();
        xmlFields.refresh();
        dirty = true;
        editCount++;
        update();
    }

    /**
     * Fills the seller of a new invoice with the preset used last time (or the first one).
     * The buyer is left empty, it usually changes from invoice to invoice.
     */
    private static void applyDefaultPreset(InvoiceData d) {
        List<Party> presets = InvoiceData.loadParties(Role.SELLER);
        if (presets.isEmpty())
            return;
        Party preset = PresetChooser.find(presets, UI.getPref(PREF_LAST_SELLER, null));
        d.seller.copyFrom(preset == null ? presets.get(0) : preset);
    }

    /**
     * Remembers the selected seller preset for the next new invoice.
     */
    void rememberPreset() {
        rememberPreset(fields);
    }

    /**
     * Remembers the seller preset selected in the fields for the next new invoice (also used by the wizard).
     */
    static void rememberPreset(InvoiceFields fields) {
        String key = fields.selectedSellerPreset();
        if (key != null)
            Preferences.getInstance().set(PREF_LAST_SELLER, key.trim());
    }

    // ---------------------------------------------------------------- items and sums

    /**
     * The items, below them the status and the sums.
     */
    private JComponent createItems() {
        JPanel totals = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = 0;
        addTotal(totals, gc, I18n.t("invoice.netAmount"), netValue, false);
        addTotal(totals, gc, I18n.t("invoice.vat"), vatValue, false);
        addTotal(totals, gc, I18n.t("editor.total"), grossValue, true);

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.add(items, BorderLayout.CENTER);
        JPanel totalsRow = new JPanel(new BorderLayout(10, 0));
        totalsRow.add(createStatusPanel(), BorderLayout.CENTER);
        totalsRow.add(totals, BorderLayout.EAST);
        panel.add(totalsRow, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * Adapts the editor to the profile: fields the profile doesn't have, items or amounts.
     */
    private void updateForProfile() {
        fields.updateForProfile();
        items.updateForProfile();
    }

    private static void addTotal(JPanel panel, GridBagConstraints gc, String caption, JLabel value, boolean total) {
        gc.gridx = 0;
        gc.anchor = GridBagConstraints.EAST;
        gc.insets = new Insets(1, 0, 1, 14);
        JLabel c = new JLabel(caption);
        if (total)
            c.setFont(c.getFont().deriveFont(Font.BOLD));
        else
            c.setForeground(UIManager.getColor("Label.disabledForeground"));
        panel.add(c, gc);
        gc.gridx = 1;
        gc.insets = new Insets(1, 0, 1, 0);
        panel.add(value, gc);
        gc.gridy++;
    }

    private static JLabel amountLabel(boolean total) {
        JLabel l = new JLabel(" ", SwingConstants.RIGHT);
        if (total)
            l.setFont(l.getFont().deriveFont(Font.BOLD, l.getFont().getSize2D() * 1.15f));
        return l;
    }

    // ---------------------------------------------------------------- bottom

    /**
     * The status with the hints and problems, in several lines. It lies beside the totals, separated by a line.
     */
    private JComponent createStatusPanel() {
        statusText.setEditable(false);
        statusText.setFocusable(false);
        statusText.setLineWrap(true);
        statusText.setWrapStyleWord(true);
        statusText.setOpaque(false);
        statusText.setBorder(null);
        statusText.setFont(UIManager.getFont("Label.font"));
        statusText.setRows(3);
        // A text area with line wrap and without columns is as wide as it is now. Then the editor could never get
        // narrower again, and the divider of the window could not be moved back. The width comes from the layout.
        statusText.setColumns(20);
        JScrollPane scroll = new JScrollPane(statusText, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        // A narrower or wider editor wraps the notes differently.
        scroll.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                fitStatusRows();
            }
        });
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        status.setVerticalAlignment(SwingConstants.TOP);
        status.setBorder(BorderFactory.createEmptyBorder(1, 0, 0, 6));

        JPanel text = new JPanel(new BorderLayout());
        text.setOpaque(false);
        text.add(status, BorderLayout.WEST);
        text.add(scroll, BorderLayout.CENTER);
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.add(text, BorderLayout.CENTER);
        panel.add(new javax.swing.JSeparator(SwingConstants.VERTICAL), BorderLayout.EAST);
        return panel;
    }

    private void setStatus(javax.swing.Icon icon, List<String> lines) {
        status.setIcon(icon);
        statusText.setText(String.join("\n", lines));
        statusText.setCaretPosition(0);
        fitStatusRows();
    }

    /**
     * The notes are as high as their text needs at the width they have (at least 3 lines, like the sums beside them, at
     * most 8: more scroll), so nothing is cut off.
     */
    private void fitStatusRows() {
        java.awt.FontMetrics fm = statusText.getFontMetrics(statusText.getFont());
        // Not known before the first layout: the width that the columns give.
        int width = statusText.getParent() instanceof JViewport viewport && viewport.getWidth() > 0
                ? viewport.getWidth() : statusText.getColumns() * fm.charWidth('m');
        int lines = 0;
        for (String line : statusText.getText().split("\n"))
            lines += wrappedLines(line, fm, width - 4);
        int rows = Math.max(3, Math.min(8, lines));
        if (rows != statusText.getRows()) {
            statusText.setRows(rows);
            statusText.revalidate();
        }
    }

    /**
     * The number of lines that a text needs if it is wrapped at the words.
     */
    private static int wrappedLines(String text, java.awt.FontMetrics fm, int width) {
        int lines = 1;
        int used = 0;
        int space = fm.charWidth(' ');
        for (String word : text.split(" ")) {
            int w = fm.stringWidth(word);
            if (used > 0 && used + space + w > width) {
                lines++;
                used = w;
            } else {
                used += (used > 0 ? space : 0) + w;
            }
        }
        return lines;
    }

    /**
     * What is missing or wrong, empty if the invoice can be attached or saved.
     */
    List<String> problems() {
        List<String> p = new ArrayList<>(fields.invalidInput());
        if (totalsError != null)
            p.add(I18n.t("editor.sumsError", totalsError));
        p.addAll(data.check());
        return p;
    }

    private void changed() {
        dirty = true;
        editCount++;
        updateTimer.restart();
        markModified();
    }

    /**
     * Remembers the values shown now as the original ones. Later changes are marked, also after loading an XML file.
     */
    private void captureOriginals() {
        fields.captureOriginals();
        items.captureOriginals();
        baselineXml = currentXml();
        items.assignOrigins();
        xmlFields.captureOriginals();
        markModified();
    }

    private void markModified() {
        fields.markModified();
        items.markModified();
    }

    /**
     * Updates sums and status after a change.
     */
    private void update() {
        updateTimer.stop();
        if (changeListener != null)
            changeListener.run();
        String currency = data.currency == null ? null : data.currency.trim().toUpperCase(Locale.ROOT);
        try {
            InvoiceData.Totals t = data.totals();
            netValue.setText(InvoiceDialog.formatAmount(t.net().toPlainString(), currency, locale));
            vatValue.setText(InvoiceDialog.formatAmount(t.vat().toPlainString(), currency, locale));
            grossValue.setText(InvoiceDialog.formatAmount(t.gross().toPlainString(), currency, locale));
            totalsError = null;
        } catch (Exception e) {
            Log.error("Failed to calculate the invoice sums: %s", e);
            totalsError = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
            netValue.setText("–");
            vatValue.setText("–");
            grossValue.setText("–");
        }
        // After the sums: switching to a profile without items fills the amounts from the items.
        updateForProfile();
        if (working)
            return;
        // What is missing (the panel above says if the invoice is complete), or hints for a complete invoice.
        List<String> p = problems();
        List<String> lines = p.isEmpty() ? data.hints() : p;
        if (lines.isEmpty())
            setStatus(null, List.of());
        else
            setStatus(p.isEmpty() ? smallInfo() : smallWarning(), lines.stream().map(h -> "• " + h).toList());
    }

    /**
     * Notes that don't prevent the invoice, but are worth a look. Only a complete invoice shows them.
     */
    List<String> hints() {
        return data.hints();
    }

    /**
     * The warning icon of the look and feel, scaled to the height of a text line.
     */
    static javax.swing.Icon smallWarning() {
        return smallIcon("OptionPane.warningIcon");
    }

    /**
     * The information icon of the look and feel, scaled to the height of a text line.
     */
    static javax.swing.Icon smallInfo() {
        return smallIcon("OptionPane.informationIcon");
    }

    private static javax.swing.Icon smallIcon(String key) {
        javax.swing.Icon icon = UIManager.getIcon(key);
        if (icon == null)
            return null;
        int h = new JLabel().getFontMetrics(UIManager.getFont("Label.font")).getHeight();
        if (icon.getIconHeight() <= h)
            return icon;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        icon.paintIcon(null, g, 0, 0);
        g.dispose();
        return new javax.swing.ImageIcon(img.getScaledInstance(-1, h, java.awt.Image.SCALE_SMOOTH));
    }

    /**
     * Ends the editing of a table cell, invalid input is discarded.
     */
    void stopEditing() {
        items.stopEditing();
    }

    /**
     * While working the status shows the given text instead of the problems and hints.
     *
     * @param message The text to show, null when the work is done.
     */
    void setWorking(String message) {
        working = message != null;
        if (working)
            setStatus(null, List.of(message));
        else
            update();
    }

    /**
     * Shows the data again in all fields, e.g. after it was changed in the tab "All fields".
     */
    private void reloadForm() {
        fields.reload();
        items.reload();
        // The rows may be new objects now.
        items.reloaded();
        markModified();
    }
}
