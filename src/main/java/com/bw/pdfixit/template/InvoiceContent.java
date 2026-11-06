package com.bw.pdfixit.template;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the engine prints, already formatted in the printed language: the values of the fields, the rows of the table
 * of items and the rows of the totals. Made from an invoice by {@code InvoiceRenderer}.
 */
public class InvoiceContent {

    /**
     * A row of the totals block, e.g. "USt 19 %" and "152,00 €".
     *
     * @param caption    The caption; printed only if the label of {@code totals} is shown.
     * @param amount     The amount.
     * @param emphasized True for the final amount: bold, with a rule above.
     */
    public record TotalsRow(String caption, String amount, boolean emphasized) {
    }

    /**
     * The values by field id ({@link LayoutField#id}); a missing or blank value is not printed.
     */
    public final Map<String, String> values = new HashMap<>();

    /**
     * The rows of the table of items, the cells by column. A missing cell is empty.
     */
    public final List<Map<Layout.Column, String>> lines = new ArrayList<>();

    public final List<TotalsRow> totals = new ArrayList<>();

    /**
     * Adds a row to the table.
     *
     * @param cells The cells in the order of {@link Layout.Column}: pos, name, quantity, unit, price, vatPercent, total.
     */
    public void addLine(String... cells) {
        Map<Layout.Column, String> row = new EnumMap<>(Layout.Column.class);
        Layout.Column[] columns = Layout.Column.values();
        for (int i = 0; i < cells.length && i < columns.length; ++i)
            if (cells[i] != null)
                row.put(columns[i], cells[i]);
        lines.add(row);
    }
}
