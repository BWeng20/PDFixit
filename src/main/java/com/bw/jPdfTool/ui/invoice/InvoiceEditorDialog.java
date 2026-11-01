package com.bw.jPdfTool.ui.invoice;

import com.bw.jPdfTool.I18n;
import com.bw.jPdfTool.model.DocumentProxy;
import com.bw.jPdfTool.ui.UI;
import com.bw.jPdfTool.invoice.Party;
import com.bw.jPdfTool.invoice.InvoiceData;
import com.bw.jPdfTool.Log;
import com.bw.jPdfTool.Preferences;
import com.bw.jPdfTool.SafeFile;
import com.bw.jPdfTool.invoice.Role;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Creates or edits an e-invoice (ZUGFeRD/Factur-X, profile EN 16931 or XRechnung) and attaches it to the document.<br>
 * The seller and bank data is remembered for the next new invoice.
 */
public class InvoiceEditorDialog extends JDialog {

    /**
     * Common unit codes (UN/ECE Recommendation 20) with readable names.
     */
    static final Map<String, String> UNITS = new LinkedHashMap<>();

    static {
        UNITS.put("C62", "unit.C62");
        UNITS.put("H87", "unit.H87");
        UNITS.put("HUR", "unit.HUR");
        UNITS.put("MIN", "unit.MIN");
        UNITS.put("DAY", "unit.DAY");
        UNITS.put("WEE", "unit.WEE");
        UNITS.put("MON", "unit.MON");
        UNITS.put("ANN", "unit.ANN");
        UNITS.put("LS", "unit.LS");
        UNITS.put("SET", "unit.SET");
        UNITS.put("PR", "unit.PR");
        UNITS.put("XPK", "unit.XPK");
        UNITS.put("KGM", "unit.KGM");
        UNITS.put("GRM", "unit.GRM");
        UNITS.put("TNE", "unit.TNE");
        UNITS.put("MTR", "unit.MTR");
        UNITS.put("KMT", "unit.KMT");
        UNITS.put("MTK", "unit.MTK");
        UNITS.put("MTQ", "unit.MTQ");
        UNITS.put("LTR", "unit.LTR");
        UNITS.put("KWH", "unit.KWH");
    }

    /**
     * Preference: name of the seller preset used last, it is the default for new invoices.
     */
    private static final String PREF_LAST_SELLER = "invoice-last-seller";

    private static final int COL_NAME = 0;
    private static final int COL_QUANTITY = 1;
    private static final int COL_UNIT = 2;
    private static final int COL_PRICE = 3;
    private static final int COL_VAT = 4;
    private static final int COL_TOTAL = 5;

    private final InvoiceData data;
    private final DocumentProxy proxy;
    private final Locale locale = Locale.getDefault();

    /**
     * Fields with input that can't be parsed, with the reason.
     */
    private final Map<JComponent, String> invalid = new LinkedHashMap<>();

    private final LineTableModel lineModel = new LineTableModel();
    private final JTable table = new JTable(lineModel);
    private final JLabel netValue = amountLabel(false);
    private final JLabel vatValue = amountLabel(false);
    private final JLabel grossValue = amountLabel(true);
    private final JLabel status = new JLabel(" ");
    private final JButton attachButton = new JButton(I18n.t("editor.attach"));
    private final JButton loadButton = new JButton(I18n.t("editor.loadXml"));
    private final JButton saveButton = new JButton(I18n.t("editor.saveXml"));
    private final JButton cancelButton = new JButton(I18n.t("common.cancel"));

    private PresetChooser sellerPresets;

    /**
     * Fill the fields again from the data, see {@link #reloadForm()}.
     */
    private final List<Runnable> reloaders = new ArrayList<>();
    private final List<PartyPanel> partyPanels = new ArrayList<>();
    /**
     * Enable or disable the fields the profile of the invoice has or not, see {@link #restrict}.
     */
    private final List<Runnable> profileUpdaters = new ArrayList<>();
    private final XmlFieldsPanel xmlFields;
    private boolean reloading;

    /**
     * A field and how to read its value, to mark it if the value differs from the one when the editor was opened.
     */
    private static final class Tracked {
        final JComponent field;
        final Supplier<?> current;
        Object original;

        Tracked(JComponent field, Supplier<?> current) {
            this.field = field;
            this.current = current;
        }

        void mark() {
            boolean modified = !Outline.same(current.get(), original);
            if (field instanceof DateField d)
                d.setModified(modified);
            else
                Outline.apply(field, false, modified);
        }
    }

    private final List<Tracked> tracked = new ArrayList<>();
    /**
     * Items and amounts as they were when the editor was opened.
     */
    private final List<InvoiceData.Line> originalLines = new ArrayList<>();
    private final List<InvoiceData.VatAmount> originalAmounts = new ArrayList<>();
    /**
     * For each row of the items and amounts the original, null for a new row. Parallel to the lists of the data.
     */
    private final List<InvoiceData.Line> lineOrigins = new ArrayList<>();
    private final List<InvoiceData.VatAmount> amountOrigins = new ArrayList<>();
    private boolean originalsKnown;

    private boolean dirty;
    private boolean working;
    /** Why the sums can't be calculated, null if they can. */
    private String totalsError;
    /** Delays the update while the user types: it processes the whole invoice. */
    private final javax.swing.Timer updateTimer = new javax.swing.Timer(200, e -> update());

    {
        updateTimer.setRepeats(false);
    }
    private boolean attached;

    private InvoiceEditorDialog(Window owner, InvoiceData data, boolean existing, DocumentProxy proxy) {
        super(owner, existing ? I18n.t("editor.title.edit") : I18n.t("editor.title.create"), Dialog.ModalityType.APPLICATION_MODAL);
        this.data = data;
        this.proxy = proxy;

        JPanel main = new JPanel(new BorderLayout(0, 10));
        main.setBorder(BorderFactory.createEmptyBorder(12, 12, 10, 12));

        JPanel north = new JPanel(new BorderLayout(0, 8));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(I18n.t("common.invoice"), createInvoiceTab());
        tabs.addTab(I18n.t("role.seller"), createPartyTab(Role.SELLER, data.seller));
        tabs.addTab(I18n.t("role.buyer"), createPartyTab(Role.BUYER, data.buyer));
        tabs.addTab(I18n.t("editor.tab.payment"), createPaymentTab());
        // All values of the XML, also those without a field in the other tabs.
        xmlFields = new XmlFieldsPanel(data, () -> {
            data.readFromDocument();
            reloadForm();
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
        main.add(createItems(), BorderLayout.CENTER);
        main.add(createBottom(), BorderLayout.SOUTH);
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

        reloadForm();
        update();
        // Filling the fields is no change by the user.
        dirty = false;
        captureOriginals();
        pack();
        setMinimumSize(new Dimension(520, 480));
    }

    /**
     * Shows the editor (modal).
     *
     * @param xml      The current invoice of the document, null to create a new one.
     * @param proxy    The document the result is attached to.
     * @return True if an invoice was attached.
     */
    public static boolean showDialog(Component parent, byte[] xml, DocumentProxy proxy) {
        Window owner = parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        InvoiceData data = null;
        if (xml != null) {
            try {
                data = InvoiceData.fromXml(xml);
            } catch (Exception e) {
                Log.exception(e);
                int answer = JOptionPane.showConfirmDialog(owner,
                        I18n.t("editor.cannotRead", escape(String.valueOf(e.getMessage()))),
                        I18n.t("editor.title.edit"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.OK_OPTION)
                    return false;
            }
        }
        boolean existing = data != null;
        if (data == null) {
            data = new InvoiceData();
            applyDefaultPreset(data);
            data.lines.add(newLine(data));
        }
        InvoiceEditorDialog dialog = new InvoiceEditorDialog(owner, data, existing, proxy);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return dialog.attached;
    }

    // ---------------------------------------------------------------- tabs

    private JComponent createInvoiceTab() {
        FormPanel f = new FormPanel();
        // Type codes; another code of an existing invoice is added as own entry.
        JComboBox<String> type = codeCombo(DOCUMENT_TYPES, () -> data.documentCode, v -> data.documentCode = v,
                code -> I18n.t("editor.type.other", code));
        JComboBox<String> profile = codeCombo(PROFILES, () -> data.profile, v -> data.profile = v,
                id -> I18n.t("editor.profile.other", id));
        profile.setToolTipText(I18n.t("editor.profile.tip"));

        f.row(I18n.t("editor.type"), type, I18n.t("editor.profile"), profile);
        f.row(I18n.t("editor.number"), text(() -> data.number, 14, v -> data.number = v),
                I18n.t("editor.currency"), text(() -> data.currency, 4, v -> data.currency = v));
        f.row(I18n.t("invoice.issueDate"), date(() -> data.issueDate, v -> data.issueDate = v, I18n.t("invoice.issueDate")),
                I18n.t("editor.deliveryDate"), restrict(date(() -> data.deliveryDate, v -> data.deliveryDate = v, I18n.t("editor.deliveryDate")), InvoiceData.Field.DELIVERY_DATE));
        f.row(I18n.t("editor.serviceFrom"), restrict(date(() -> data.periodStart, v -> data.periodStart = v, I18n.t("editor.periodStart")), InvoiceData.Field.PERIOD),
                I18n.t("editor.serviceTo"), restrict(date(() -> data.periodEnd, v -> data.periodEnd = v, I18n.t("editor.periodEnd")), InvoiceData.Field.PERIOD));
        JTextField reference = text(() -> data.buyerReference, 14, v -> data.buyerReference = v);
        reference.setToolTipText(I18n.t("editor.reference.tip"));
        f.row(I18n.t("editor.buyerReference"), reference);
        f.row(I18n.t("editor.note"), restrict(text(() -> data.note, 14, v -> data.note = v), InvoiceData.Field.NOTE));
        JTextField exemption = text(() -> data.vatExemptionReason, 14, v -> data.vatExemptionReason = v);
        exemption.setToolTipText(I18n.t("editor.exemption.tip"));
        f.row(I18n.t("editor.exemption"), exemption);
        return f;
    }

    private static final Map<String, String> DOCUMENT_TYPES = new LinkedHashMap<>();
    private static final Map<String, String> PROFILES = new LinkedHashMap<>();

    static {
        DOCUMENT_TYPES.put(InvoiceData.TYPE_INVOICE, "invoice.type.380");
        DOCUMENT_TYPES.put(InvoiceData.TYPE_CREDIT_NOTE, "invoice.type.381");
        PROFILES.put(InvoiceData.PROFILE_EN16931, "editor.profile.en16931");
        PROFILES.put(InvoiceData.PROFILE_XRECHNUNG, "editor.profile.xrechnung");
    }

    /**
     * Combo box for a code with readable names. A code of the invoice that is not in the list
     * (e.g. another profile) is added, so it is kept unless the user chooses another one.
     *
     * @param unknownName Name for a code that is not in the list.
     */
    private JComboBox<String> codeCombo(Map<String, String> names, Supplier<String> getter, Consumer<String> setter,
                                        java.util.function.Function<String, String> unknownName) {
        JComboBox<String> combo = track(new JComboBox<>(), getter);
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                String code = (String) value;
                String text = code == null ? "" : names.containsKey(code) ? I18n.t(names.get(code)) : unknownName.apply(code);
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
            }
        });
        reloaders.add(() -> {
            String code = getter.get();
            combo.removeAllItems();
            names.keySet().forEach(combo::addItem);
            if (code != null && !names.containsKey(code))
                combo.addItem(code);
            combo.setSelectedItem(code == null ? names.keySet().iterator().next() : code);
        });
        combo.addActionListener(e -> {
            if (reloading || combo.getSelectedItem() == null)
                return;
            setter.accept((String) combo.getSelectedItem());
            changed();
        });
        return combo;
    }

    /**
     * Seller or buyer data with the preset chooser in the first row.
     * Choosing a preset copies its values into the invoice.
     */
    private PartyPanel createPartyTab(Role role, Party party) {
        PartyPanel[] panel = new PartyPanel[1];
        PresetChooser chooser = new PresetChooser(role, party, () -> {
            panel[0].reload();
            // A buyer may bring payment terms for the fields of the invoice that are still empty.
            if (role == Role.BUYER && data.applyBuyerPaymentDefaults())
                reloadForm();
            changed();
        }, role == Role.BUYER ? data::takePaymentConditions : null);
        panel[0] = new PartyPanel(role == Role.SELLER, I18n.t("editor.preset"), chooser);
        panel[0].setParty(party);
        panel[0].setBaseline(party.copy());
        panel[0].setOnChange(() -> {
            chooser.refreshButton();
            changed();
        });
        partyPanels.add(panel[0]);
        profileUpdaters.add(() -> panel[0].setSupported(data::supports));
        if (role == Role.SELLER)
            sellerPresets = chooser;
        return panel[0];
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
    private void rememberPreset() {
        String key = sellerPresets == null ? null : sellerPresets.getSelectedKey();
        if (key != null)
            Preferences.getInstance().set(PREF_LAST_SELLER, key.trim());
    }

    private JComponent createPaymentTab() {
        FormPanel f = new FormPanel();
        f.compactRow(I18n.t("invoice.dueDate"), restrict(date(() -> data.dueDate, v -> data.dueDate = v, I18n.t("invoice.dueDate")), InvoiceData.Field.PAYMENT));
        f.row(I18n.t("invoice.paymentTerms"), restrict(text(() -> data.paymentTerms, 14, v -> data.paymentTerms = v), InvoiceData.Field.PAYMENT));
        JLabel hint = new JLabel(I18n.t("editor.bankHint"));
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        f.row("", hint);
        return f;
    }

    // ---------------------------------------------------------------- items

    private JComponent createItems() {
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setSurrendersFocusOnKeystroke(true);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);

        DecimalRenderer decimal = new DecimalRenderer(0, this::lineModified);
        DecimalRenderer money = new DecimalRenderer(2, this::lineModified);
        table.getColumnModel().getColumn(COL_NAME).setCellRenderer(new MarkingRenderer(this::lineModified));
        table.getColumnModel().getColumn(COL_QUANTITY).setCellRenderer(decimal);
        table.getColumnModel().getColumn(COL_VAT).setCellRenderer(decimal);
        table.getColumnModel().getColumn(COL_PRICE).setCellRenderer(money);
        table.getColumnModel().getColumn(COL_TOTAL).setCellRenderer(money);
        for (int c : new int[]{COL_QUANTITY, COL_PRICE, COL_VAT})
            table.getColumnModel().getColumn(c).setCellEditor(new DecimalEditor(this::lineModified));

        JComboBox<String> units = new JComboBox<>(UNITS.keySet().toArray(new String[0]));
        units.setEditable(true);
        units.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, unitText(value), index, isSelected, cellHasFocus);
            }
        });
        table.getColumnModel().getColumn(COL_UNIT).setCellEditor(new DefaultCellEditor(units) {
            @Override
            public Object getCellEditorValue() {
                Object v = super.getCellEditorValue();
                if (v == null)
                    return null;
                // Typed "HUR (hour)" or "hur": keep the code only.
                String s = v.toString().trim();
                int idx = s.indexOf(' ');
                return (idx < 0 ? s : s.substring(0, idx)).toUpperCase(Locale.ROOT);
            }
        });
        table.getColumnModel().getColumn(COL_UNIT).setCellRenderer(new MarkingRenderer(this::lineModified) {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                return super.getTableCellRendererComponent(table, unitText(value), isSelected, hasFocus, row, column);
            }
        });

        int em = table.getFontMetrics(table.getFont()).charWidth('m');
        int[] widths = {22, 6, 9, 8, 5, 9};
        for (int i = 0; i < widths.length; ++i)
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i] * em);
        table.setRowHeight(Math.max(table.getRowHeight(), table.getFontMetrics(table.getFont()).getHeight() + 6));

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(59 * em, table.getRowHeight() * 7 + table.getTableHeader().getPreferredSize().height));

        JButton add = new JButton(I18n.t("editor.addItem"));
        add.addActionListener(e -> {
            stopEditing();
            data.lines.add(newLine(data));
            lineOrigins.add(null);
            int row = data.lines.size() - 1;
            lineModel.fireTableRowsInserted(row, row);
            table.changeSelection(row, COL_NAME, false, false);
            table.editCellAt(row, COL_NAME);
            Component editor = table.getEditorComponent();
            if (editor != null)
                editor.requestFocusInWindow();
            changed();
        });
        JButton remove = new JButton(I18n.t("redact.remove"));
        remove.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(e -> remove.setEnabled(table.getSelectedRowCount() > 0));
        remove.addActionListener(e -> {
            if (table.isEditing())
                table.getCellEditor().cancelCellEditing();
            int[] rows = table.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; --i) {
                data.lines.remove(rows[i]);
                if (rows[i] < lineOrigins.size())
                    lineOrigins.remove(rows[i]);
            }
            lineModel.fireTableDataChanged();
            changed();
        });
        JPanel itemButtons = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        itemButtons.add(add);
        itemButtons.add(remove);

        JPanel totals = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = 0;
        addTotal(totals, gc, I18n.t("invoice.netAmount"), netValue, false);
        addTotal(totals, gc, I18n.t("invoice.vat"), vatValue, false);
        addTotal(totals, gc, I18n.t("editor.total"), grossValue, true);

        // Items, or for profiles without items (MINIMUM, BASIC WL) the net amounts per VAT rate.
        cards.add(tablePanel(I18n.t("editor.items"), scroll, itemButtons), CARD_ITEMS);
        cards.add(createAmounts(em), CARD_AMOUNTS);

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.add(cards, BorderLayout.CENTER);
        JPanel totalsRow = new JPanel(new BorderLayout());
        totalsRow.add(totals, BorderLayout.EAST);
        panel.add(totalsRow, BorderLayout.SOUTH);
        return panel;
    }

    private static JPanel tablePanel(String title, JScrollPane scroll, JPanel buttons) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        JLabel caption = new JLabel(title);
        caption.setFont(caption.getFont().deriveFont(Font.BOLD));
        panel.add(caption, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private static final String CARD_ITEMS = "items";
    private static final String CARD_AMOUNTS = "amounts";
    private static final int COL_AMOUNT_NET = 0;
    private static final int COL_AMOUNT_RATE = 1;
    private static final int COL_AMOUNT_VAT = 2;

    private final java.awt.CardLayout cardLayout = new java.awt.CardLayout();
    private final JPanel cards = new JPanel(cardLayout);
    private final AmountTableModel amountModel = new AmountTableModel();
    private final JTable amountTable = new JTable(amountModel);
    /**
     * If the items are shown (and not the amounts).
     */
    private Boolean showingItems;

    /**
     * Net amounts per VAT rate, for profiles without items.
     */
    private JComponent createAmounts(int em) {
        amountTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        amountTable.setSurrendersFocusOnKeystroke(true);
        amountTable.setFillsViewportHeight(true);
        amountTable.getTableHeader().setReorderingAllowed(false);
        amountTable.setRowHeight(table.getRowHeight());
        DecimalRenderer money = new DecimalRenderer(2, this::amountModified);
        amountTable.getColumnModel().getColumn(COL_AMOUNT_NET).setCellRenderer(money);
        amountTable.getColumnModel().getColumn(COL_AMOUNT_RATE).setCellRenderer(new DecimalRenderer(0, this::amountModified));
        amountTable.getColumnModel().getColumn(COL_AMOUNT_VAT).setCellRenderer(money);
        for (int c : new int[]{COL_AMOUNT_NET, COL_AMOUNT_RATE})
            amountTable.getColumnModel().getColumn(c).setCellEditor(new DecimalEditor(this::amountModified));
        JScrollPane scroll = new JScrollPane(amountTable);
        scroll.setPreferredSize(new Dimension(59 * em, table.getRowHeight() * 7 + amountTable.getTableHeader().getPreferredSize().height));

        JButton add = new JButton(I18n.t("editor.addAmount"));
        add.setToolTipText(I18n.t("editor.addAmount.tip"));
        add.addActionListener(e -> {
            stopEditing();
            InvoiceData.VatAmount a = new InvoiceData.VatAmount();
            a.vatPercent = data.vatAmounts.isEmpty() ? new BigDecimal("19") : data.vatAmounts.get(data.vatAmounts.size() - 1).vatPercent;
            data.vatAmounts.add(a);
            amountOrigins.add(null);
            int row = data.vatAmounts.size() - 1;
            amountModel.fireTableRowsInserted(row, row);
            amountTable.changeSelection(row, COL_AMOUNT_NET, false, false);
            amountTable.editCellAt(row, COL_AMOUNT_NET);
            Component editor = amountTable.getEditorComponent();
            if (editor != null)
                editor.requestFocusInWindow();
            changed();
        });
        JButton remove = new JButton(I18n.t("redact.remove"));
        remove.setEnabled(false);
        amountTable.getSelectionModel().addListSelectionListener(e -> remove.setEnabled(amountTable.getSelectedRowCount() > 0));
        remove.addActionListener(e -> {
            if (amountTable.isEditing())
                amountTable.getCellEditor().cancelCellEditing();
            int[] rows = amountTable.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; --i) {
                data.vatAmounts.remove(rows[i]);
                if (rows[i] < amountOrigins.size())
                    amountOrigins.remove(rows[i]);
            }
            amountModel.fireTableDataChanged();
            changed();
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        buttons.add(add);
        buttons.add(remove);
        JLabel hint = new JLabel(I18n.t("editor.noItems"));
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        buttons.add(javax.swing.Box.createHorizontalStrut(12));
        buttons.add(hint);
        return tablePanel(I18n.t("editor.amounts"), scroll, buttons);
    }

    private class AmountTableModel extends AbstractTableModel {
        private final String[] names = {I18n.t("invoice.netAmount"), I18n.t("editor.col.vatPercent"), I18n.t("invoice.vat")};

        @Override
        public int getRowCount() {
            return data.vatAmounts.size();
        }

        @Override
        public int getColumnCount() {
            return names.length;
        }

        @Override
        public String getColumnName(int column) {
            return names[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return BigDecimal.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column != COL_AMOUNT_VAT;
        }

        @Override
        public Object getValueAt(int row, int column) {
            InvoiceData.VatAmount a = data.vatAmounts.get(row);
            return switch (column) {
                case COL_AMOUNT_NET -> a.net;
                case COL_AMOUNT_RATE -> a.vatPercent;
                default -> a.vat();
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            InvoiceData.VatAmount a = data.vatAmounts.get(row);
            if (column == COL_AMOUNT_NET)
                a.net = (BigDecimal) value;
            else if (column == COL_AMOUNT_RATE)
                a.vatPercent = (BigDecimal) value;
            else
                return;
            fireTableRowsUpdated(row, row);
            changed();
        }
    }

    /**
     * Disables the field if the profile of the invoice doesn't have it, see {@link InvoiceData#supports}.
     *
     * @return The field.
     */
    private <T extends JComponent> T restrict(T field, InvoiceData.Field group) {
        String toolTip = field.getToolTipText();
        profileUpdaters.add(() -> {
            boolean ok = data.supports(group);
            field.setEnabled(ok);
            field.setToolTipText(ok ? toolTip : I18n.t("party.notInProfile"));
        });
        return field;
    }

    /**
     * Adapts the editor to the profile: fields the profile doesn't have, items or amounts.
     */
    private void updateForProfile() {
        profileUpdaters.forEach(Runnable::run);
        boolean items = data.hasItems();
        if (showingItems == null || showingItems != items) {
            if (Boolean.TRUE.equals(showingItems) && table.isEditing())
                table.getCellEditor().cancelCellEditing();
            if (Boolean.FALSE.equals(showingItems) && amountTable.isEditing())
                amountTable.getCellEditor().cancelCellEditing();
            showingItems = items;
            cardLayout.show(cards, items ? CARD_ITEMS : CARD_AMOUNTS);
            lineModel.fireTableDataChanged();
            amountModel.fireTableDataChanged();
        }
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

    private static InvoiceData.Line newLine(InvoiceData data) {
        InvoiceData.Line line = new InvoiceData.Line();
        if (!data.lines.isEmpty()) {
            // Most invoices use the same rate and unit for all items.
            InvoiceData.Line last = data.lines.get(data.lines.size() - 1);
            line.vatPercent = last.vatPercent;
            line.unit = last.unit;
        } else if ("DE".equalsIgnoreCase(data.seller.country == null ? null : data.seller.country.trim())) {
            line.vatPercent = new BigDecimal("19");
        }
        return line;
    }

    private static String unitText(Object code) {
        if (code == null)
            return "";
        String name = UNITS.get(code.toString());
        return name == null ? code.toString() : code + " (" + I18n.t(name) + ")";
    }

    private class LineTableModel extends AbstractTableModel {
        private final String[] names = {I18n.t("editor.col.description"), I18n.t("editor.col.quantity"), I18n.t("editor.col.unit"), I18n.t("editor.col.unitPrice"), I18n.t("editor.col.vatPercent"), I18n.t("editor.col.totalNet")};

        @Override
        public int getRowCount() {
            return data.lines.size();
        }

        @Override
        public int getColumnCount() {
            return names.length;
        }

        @Override
        public String getColumnName(int column) {
            return names[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == COL_NAME || column == COL_UNIT ? String.class : BigDecimal.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column != COL_TOTAL;
        }

        @Override
        public Object getValueAt(int row, int column) {
            InvoiceData.Line l = data.lines.get(row);
            return switch (column) {
                case COL_NAME -> l.name;
                case COL_QUANTITY -> l.quantity;
                case COL_UNIT -> l.unit;
                case COL_PRICE -> l.price;
                case COL_VAT -> l.vatPercent;
                default -> l.total();
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            InvoiceData.Line l = data.lines.get(row);
            switch (column) {
                case COL_NAME -> l.name = (String) value;
                case COL_QUANTITY -> l.quantity = (BigDecimal) value;
                case COL_UNIT -> l.unit = (String) value;
                case COL_PRICE -> l.price = (BigDecimal) value;
                case COL_VAT -> l.vatPercent = (BigDecimal) value;
                default -> {
                    return;
                }
            }
            fireTableRowsUpdated(row, row);
            changed();
        }
    }

    /**
     * Cell renderer that draws a border around modified cells.
     */
    private static class MarkingRenderer extends DefaultTableCellRenderer {
        private final BiPredicate<Integer, Integer> modified;

        MarkingRenderer(BiPredicate<Integer, Integer> modified) {
            this.modified = modified;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (modified.test(row, column))
                setBorder(Outline.cellBorder(getBorder(), this));
            return this;
        }
    }

    private class DecimalRenderer extends MarkingRenderer {
        private final int minFraction;

        DecimalRenderer(int minFraction, BiPredicate<Integer, Integer> modified) {
            super(modified);
            this.minFraction = minFraction;
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        protected void setValue(Object value) {
            setText(value instanceof BigDecimal d ? formatDecimal(d, minFraction, locale) : "");
        }
    }

    /**
     * Edits a number in the format of the locale. Editing can't be finished with invalid input.
     */
    private class DecimalEditor extends DefaultCellEditor {
        private final JTextField field;
        private final BiPredicate<Integer, Integer> modified;

        DecimalEditor(BiPredicate<Integer, Integer> modified) {
            super(new JTextField());
            this.modified = modified;
            field = (JTextField) getComponent();
            field.setHorizontalAlignment(SwingConstants.RIGHT);
            setClickCountToStart(1);
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            super.getTableCellEditorComponent(table, value, isSelected, row, column);
            field.setText(value instanceof BigDecimal d ? formatDecimal(d, 0, locale) : "");
            Outline.apply(field, false, modified.test(row, column));
            return field;
        }

        @Override
        public Object getCellEditorValue() {
            try {
                return parseDecimal(field.getText(), locale);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Override
        public boolean stopCellEditing() {
            try {
                parseDecimal(field.getText(), locale);
            } catch (NumberFormatException e) {
                field.putClientProperty("JComponent.outline", "error");
                return false;
            }
            return super.stopCellEditing();
        }
    }

    // ---------------------------------------------------------------- bottom

    private JComponent createBottom() {
        status.setIconTextGap(6);

        attachButton.addActionListener(e -> attach());
        saveButton.setToolTipText(I18n.t("editor.saveXml.tip"));
        loadButton.setToolTipText(I18n.t("editor.loadXml.tip"));
        loadButton.addActionListener(e -> loadXml());
        saveButton.addActionListener(e -> saveXml());
        cancelButton.addActionListener(e -> cancel());
        getRootPane().setDefaultButton(attachButton);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        buttons.add(loadButton);
        buttons.add(saveButton);
        buttons.add(cancelButton);
        buttons.add(attachButton);

        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.add(status, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.EAST);
        return panel;
    }

    private List<String> problems() {
        List<String> p = new ArrayList<>(invalid.values());
        if (totalsError != null)
            p.add(I18n.t("editor.sumsError", totalsError));
        p.addAll(data.check());
        return p;
    }

    private void changed() {
        dirty = true;
        updateTimer.restart();
        markModified();
    }

    /**
     * Remembers the values shown now as the original ones. Later changes are marked, also after loading an XML file.
     */
    private void captureOriginals() {
        tracked.forEach(t -> t.original = t.current.get());
        originalLines.clear();
        for (InvoiceData.Line l : data.lines)
            originalLines.add(new InvoiceData.Line(l.name, l.quantity, l.unit, l.price, l.vatPercent));
        originalAmounts.clear();
        for (InvoiceData.VatAmount a : data.vatAmounts) {
            InvoiceData.VatAmount c = new InvoiceData.VatAmount();
            c.net = a.net;
            c.vatPercent = a.vatPercent;
            originalAmounts.add(c);
        }
        originalsKnown = true;
        assignOrigins();
        xmlFields.captureOriginals();
        markModified();
    }

    /**
     * Matches the rows to the original ones by position. Used when the rows were created again from the XML.
     */
    private void assignOrigins() {
        lineOrigins.clear();
        for (int i = 0; i < data.lines.size(); i++)
            lineOrigins.add(i < originalLines.size() ? originalLines.get(i) : null);
        amountOrigins.clear();
        for (int i = 0; i < data.vatAmounts.size(); i++)
            amountOrigins.add(i < originalAmounts.size() ? originalAmounts.get(i) : null);
    }

    private void markModified() {
        if (!originalsKnown)
            return;
        tracked.forEach(Tracked::mark);
        table.repaint();
        amountTable.repaint();
    }

    /**
     * Checks if a cell of the items differs from the original. All editable cells of a new item are modified.
     */
    private boolean lineModified(int row, int column) {
        if (!originalsKnown || column == COL_TOTAL || row < 0 || row >= data.lines.size())
            return false;
        InvoiceData.Line l = data.lines.get(row);
        InvoiceData.Line o = row < lineOrigins.size() ? lineOrigins.get(row) : null;
        if (o == null)
            return true;
        return !Outline.same(switch (column) {
            case COL_NAME -> l.name;
            case COL_QUANTITY -> l.quantity;
            case COL_UNIT -> l.unit;
            case COL_PRICE -> l.price;
            default -> l.vatPercent;
        }, switch (column) {
            case COL_NAME -> o.name;
            case COL_QUANTITY -> o.quantity;
            case COL_UNIT -> o.unit;
            case COL_PRICE -> o.price;
            default -> o.vatPercent;
        });
    }

    private boolean amountModified(int row, int column) {
        if (!originalsKnown || column == COL_AMOUNT_VAT || row < 0 || row >= data.vatAmounts.size())
            return false;
        InvoiceData.VatAmount a = data.vatAmounts.get(row);
        InvoiceData.VatAmount o = row < amountOrigins.size() ? amountOrigins.get(row) : null;
        if (o == null)
            return true;
        return column == COL_AMOUNT_NET ? !Outline.same(a.net, o.net) : !Outline.same(a.vatPercent, o.vatPercent);
    }

    /**
     * Updates sums and status after a change.
     */
    private void update() {
        updateTimer.stop();
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
        List<String> p = problems();
        if (p.isEmpty()) {
            List<String> hints = data.hints();
            if (hints.isEmpty()) {
                status.setIcon(null);
                status.setText(I18n.t("editor.ready"));
                status.setToolTipText(null);
            } else {
                status.setIcon(smallWarning());
                status.setText(I18n.t("editor.readyNote", hints.get(0)) + (hints.size() > 1 ? "  " + I18n.t("editor.more", hints.size() - 1) : ""));
                status.setToolTipText("<html>" + String.join("<br>", hints.stream().map(InvoiceEditorDialog::escape).toList()) + "</html>");
            }
        } else {
            status.setIcon(smallWarning());
            status.setText(p.get(0) + (p.size() > 1 ? "  " + I18n.t("editor.more", p.size() - 1) : ""));
            status.setToolTipText("<html>" + String.join("<br>", p.stream().map(InvoiceEditorDialog::escape).toList()) + "</html>");
        }
    }

    /**
     * The warning icon of the look and feel, scaled to the height of a text line.
     */
    private static javax.swing.Icon smallWarning() {
        javax.swing.Icon icon = UIManager.getIcon("OptionPane.warningIcon");
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

    private void stopEditing() {
        for (JTable t : new JTable[]{table, amountTable})
            if (t.isEditing() && !t.getCellEditor().stopCellEditing())
                t.getCellEditor().cancelCellEditing();
    }

    /**
     * Shows the problems if there are any.
     *
     * @return True if the data is complete.
     */
    private boolean confirmComplete(String action) {
        stopEditing();
        List<String> p = problems();
        if (p.isEmpty())
            return true;
        JOptionPane.showMessageDialog(this,
                I18n.t("editor.incomplete", I18n.t("editor.action." + action), String.join("<br>", p.stream().map(s -> "• " + escape(s)).toList())),
                getTitle(), JOptionPane.WARNING_MESSAGE);
        return false;
    }

    private void attach() {
        if (working || !confirmComplete("attached"))
            return;
        final byte[] xml;
        try {
            xml = data.toXml();
        } catch (Exception e) {
            showError(I18n.t("editor.createFailed"), e);
            return;
        }
        setWorking(true);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                proxy.attachInvoice(xml);
                return null;
            }

            @Override
            protected void done() {
                setWorking(false);
                try {
                    get();
                    rememberPreset();
                    attached = true;
                    dispose();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    showError(I18n.t("editor.attachFailed"), e.getCause() == null ? e : e.getCause());
                }
            }
        }.execute();
    }

    /**
     * Replaces the content by an invoice XML file. Nothing is changed if the file is no valid invoice.
     */
    private void loadXml() {
        if (working)
            return;
        stopEditing();
        if (dirty && JOptionPane.showConfirmDialog(this, I18n.t("editor.replaceQuestion"), getTitle(),
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION)
            return;
        JFileChooser chooser = new JFileChooser();
        String lastDir = UI.getPref(Preferences.USER_PREF_LAST_PDF_DIR, null);
        if (lastDir != null)
            chooser.setCurrentDirectory(new File(lastDir));
        chooser.setFileFilter(new FileNameExtensionFilter("XML", "xml"));
        chooser.setDialogTitle(I18n.t("editor.loadTitle"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
            return;
        byte[] xml;
        try {
            xml = Files.readAllBytes(chooser.getSelectedFile().toPath());
            data.replaceXml(xml);
        } catch (Exception e) {
            showError(I18n.t("editor.noValidInvoice"), e);
            return;
        }
        reloadForm();
        xmlFields.refresh();
        dirty = true;
        update();
    }

    private void saveXml() {
        if (working || !confirmComplete("saved"))
            return;
        JFileChooser chooser = new JFileChooser();
        String lastDir = UI.getPref(Preferences.USER_PREF_LAST_PDF_DIR, null);
        if (lastDir != null)
            chooser.setCurrentDirectory(new File(lastDir));
        chooser.setFileFilter(new FileNameExtensionFilter("XML", "xml"));
        chooser.setDialogTitle(I18n.t("menu.invoice.save"));
        String name = data.number == null || data.number.isBlank() ? "factur-x" : data.number.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), name + ".xml"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
            return;
        File file = chooser.getSelectedFile();
        if (file.exists() && !UI.askOverwrite(this, file.toPath()))
            return;
        try {
            byte[] xml = data.toXml();
            SafeFile.write(file.toPath(), os -> os.write(xml));
            rememberPreset();
            dirty = false;
        } catch (Exception e) {
            showError(I18n.t("editor.saveFailed"), e);
        }
    }

    private void cancel() {
        if (working)
            return;
        stopEditing();
        if (dirty) {
            int answer = JOptionPane.showConfirmDialog(this, I18n.t("party.discard"), getTitle(),
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.YES_OPTION)
                return;
        }
        dispose();
    }

    private void setWorking(boolean working) {
        this.working = working;
        attachButton.setEnabled(!working);
        loadButton.setEnabled(!working);
        saveButton.setEnabled(!working);
        cancelButton.setEnabled(!working);
        setCursor(working ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : null);
        if (working) {
            status.setIcon(null);
            status.setText(I18n.t("editor.attaching"));
            status.setToolTipText(null);
        } else {
            update();
        }
    }

    private void showError(String what, Throwable e) {
        Log.exception(e instanceof Exception ex ? ex : new Exception(e));
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        JTextArea area = new JTextArea(message, Math.min(16, message.split("\n").length + 1), 70);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("<html><b>" + escape(what) + "</b></html>"), BorderLayout.NORTH);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
        JOptionPane.showMessageDialog(this, panel, getTitle(), JOptionPane.ERROR_MESSAGE);
    }

    // ---------------------------------------------------------------- fields

    /**
     * Shows the data again in all fields, e.g. after it was changed in the tab "All fields".
     */
    private void reloadForm() {
        reloading = true;
        try {
            reloaders.forEach(Runnable::run);
            partyPanels.forEach(PartyPanel::reload);
            lineModel.fireTableDataChanged();
            amountModel.fireTableDataChanged();
        } finally {
            reloading = false;
        }
        // The rows may be new objects now.
        if (originalsKnown)
            assignOrigins();
        markModified();
    }

    /**
     * Marks the field while its value differs from the one when the editor was opened.
     */
    private <T extends JComponent> T track(T field, Supplier<?> current) {
        tracked.add(new Tracked(field, current));
        return field;
    }

    private JTextField text(Supplier<String> getter, int columns, Consumer<String> setter) {
        JTextField f = track(new JTextField(columns), getter);
        reloaders.add(() -> {
            String v = getter.get();
            f.setText(v == null ? "" : v);
            f.setCaretPosition(0);
        });
        onChange(f, () -> {
            if (reloading)
                return;
            setter.accept(f.getText());
            changed();
        });
        return f;
    }

    private DateField date(Supplier<LocalDate> getter, Consumer<LocalDate> setter, String caption) {
        DateField f = track(new DateField(locale), getter);
        reloaders.add(() -> {
            f.setDate(getter.get());
            invalid.remove(f);
        });
        f.addChangeListener(() -> {
            if (reloading) {
                return;
            }
            if (f.isInputValid()) {
                invalid.remove(f);
            } else {
                invalid.put(f, I18n.t("editor.invalidDate", caption, DateField.pattern(locale)));
            }
            setter.accept(f.getDate());
            changed();
        });
        return f;
    }

    private static void onChange(JTextField f, Runnable r) {
        f.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                r.run();
            }
        });
    }

    // ---------------------------------------------------------------- parsing and formatting

    /**
     * Parses a number in the format of the locale (without grouping) or with "." as decimal separator.
     * A trailing "%" is ignored.
     *
     * @return The number, null if the text is empty.
     * @throws NumberFormatException If the text is no number.
     */
    static BigDecimal parseDecimal(String text, Locale locale) {
        String t = text == null ? "" : text.replaceAll("[\\s  %]", "");
        if (t.isEmpty())
            return null;
        DecimalFormat df = (DecimalFormat) NumberFormat.getNumberInstance(locale);
        df.setParseBigDecimal(true);
        df.setGroupingUsed(false);
        ParsePosition pos = new ParsePosition(0);
        Number n = df.parse(t, pos);
        if (n instanceof BigDecimal d && pos.getIndex() == t.length())
            return d;
        return new BigDecimal(t);
    }

    /**
     * Formats a number for the locale, without grouping and with all significant digits.
     */
    static String formatDecimal(BigDecimal value, int minFraction, Locale locale) {
        DecimalFormat df = (DecimalFormat) NumberFormat.getNumberInstance(locale);
        df.setGroupingUsed(false);
        df.setMinimumFractionDigits(minFraction);
        df.setMaximumFractionDigits(Math.max(minFraction, Math.max(0, value.stripTrailingZeros().scale())));
        return df.format(value);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
