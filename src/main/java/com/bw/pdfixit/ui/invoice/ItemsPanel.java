package com.bw.pdfixit.ui.invoice;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.invoice.InvoiceData;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * The items of an invoice in a table, or for profiles without items (MINIMUM, BASIC WL) the net amounts per VAT rate.
 * Which of the two is shown follows the profile, see {@link #updateForProfile()}. Cells that differ from the values at
 * {@link #captureOriginals()} are marked.
 */
class ItemsPanel extends JPanel {

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

    private static final int COL_NAME = 0;
    private static final int COL_QUANTITY = 1;
    private static final int COL_UNIT = 2;
    private static final int COL_PRICE = 3;
    private static final int COL_VAT = 4;
    private static final int COL_TOTAL = 5;

    private static final String CARD_ITEMS = "items";
    private static final String CARD_AMOUNTS = "amounts";
    private static final int COL_AMOUNT_NET = 0;
    private static final int COL_AMOUNT_RATE = 1;
    private static final int COL_AMOUNT_VAT = 2;

    private final InvoiceFields fields;
    private final InvoiceData data;
    private final Locale locale;

    private final LineTableModel lineModel = new LineTableModel();
    private final JTable table = new JTable(lineModel);
    private final CardLayout cardLayout;
    private final AmountTableModel amountModel = new AmountTableModel();
    private final JTable amountTable = new JTable(amountModel);
    /**
     * If the items are shown (and not the amounts).
     */
    private Boolean showingItems;

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

    ItemsPanel(InvoiceFields fields) {
        super(new CardLayout());
        cardLayout = (CardLayout) getLayout();
        this.fields = fields;
        this.data = fields.data();
        this.locale = fields.locale;

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
        // The headers are never cut off ("Unit price (n…"), the description takes what is left.
        for (int i = 1; i < widths.length; ++i) {
            TableColumn column = table.getColumnModel().getColumn(i);
            Component header = table.getTableHeader().getDefaultRenderer()
                    .getTableCellRendererComponent(table, column.getHeaderValue(), false, false, -1, i);
            int needed = header.getPreferredSize().width + em / 2;
            column.setMinWidth(needed);
            column.setPreferredWidth(Math.max(column.getPreferredWidth(), needed));
        }
        table.setRowHeight(Math.max(table.getRowHeight(), table.getFontMetrics(table.getFont()).getHeight() + 6));
        TermHelp.applyToHeader(table, new String[]{"BT-153"}, new String[]{"BT-129"}, new String[]{"BT-130"},
                new String[]{"BT-146"}, new String[]{"BT-152"}, new String[]{"BT-131"});

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(InvoiceEditor.TABLE_WIDTH_IN_EM * em, table.getRowHeight() * 5 + table.getTableHeader().getPreferredSize().height));

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
            fields.changed();
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
            fields.changed();
        });
        JPanel itemButtons = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        itemButtons.add(add);
        itemButtons.add(remove);

        // Items, or for profiles without items (MINIMUM, BASIC WL) the net amounts per VAT rate.
        add(tablePanel(I18n.t("editor.items"), scroll, itemButtons), CARD_ITEMS);
        add(createAmounts(em), CARD_AMOUNTS);
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
        TermHelp.applyToHeader(amountTable, new String[]{"BT-116"}, new String[]{"BT-119"}, new String[]{"BT-117"});
        JScrollPane scroll = new JScrollPane(amountTable);
        scroll.setPreferredSize(new Dimension(InvoiceEditor.TABLE_WIDTH_IN_EM * em, table.getRowHeight() * 5 + amountTable.getTableHeader().getPreferredSize().height));

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
            fields.changed();
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
            fields.changed();
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

    /**
     * Shows the items or the amounts, as the profile of the invoice has them.
     */
    void updateForProfile() {
        boolean items = data.hasItems();
        if (showingItems == null || showingItems != items) {
            if (Boolean.TRUE.equals(showingItems) && table.isEditing())
                table.getCellEditor().cancelCellEditing();
            if (Boolean.FALSE.equals(showingItems) && amountTable.isEditing())
                amountTable.getCellEditor().cancelCellEditing();
            showingItems = items;
            cardLayout.show(this, items ? CARD_ITEMS : CARD_AMOUNTS);
            lineModel.fireTableDataChanged();
            amountModel.fireTableDataChanged();
        }
    }

    /**
     * Shows the rows of the data again; the rows may be new objects.
     */
    void reload() {
        lineModel.fireTableDataChanged();
        amountModel.fireTableDataChanged();
    }

    /**
     * Matches the rows to the original ones again after {@link #reload()}, if the originals are known.
     */
    void reloaded() {
        if (originalsKnown)
            assignOrigins();
    }

    /**
     * Ends the editing of a table cell, invalid input is discarded.
     */
    void stopEditing() {
        for (JTable t : new JTable[]{table, amountTable})
            if (t.isEditing() && !t.getCellEditor().stopCellEditing())
                t.getCellEditor().cancelCellEditing();
    }

    /**
     * Remembers the rows shown now as the original ones. Later changes are marked.
     */
    void captureOriginals() {
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
    }

    /**
     * Matches the rows to the original ones by position. Used when the rows were created again from the XML.
     */
    void assignOrigins() {
        lineOrigins.clear();
        for (int i = 0; i < data.lines.size(); i++)
            lineOrigins.add(i < originalLines.size() ? originalLines.get(i) : null);
        amountOrigins.clear();
        for (int i = 0; i < data.vatAmounts.size(); i++)
            amountOrigins.add(i < originalAmounts.size() ? originalAmounts.get(i) : null);
    }

    /**
     * Repaints the tables, so modified cells are marked.
     */
    void markModified() {
        if (!originalsKnown)
            return;
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
     * A new item: the rate and the unit of the last one (most invoices use the same for all items), 19 % for the first
     * item of a German seller.
     */
    static InvoiceData.Line newLine(InvoiceData data) {
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
            fields.changed();
        }
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
            fields.changed();
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
}
