package com.bw.pdfixit.ui;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.ValidationResult;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.List;

/**
 * Shows the result of a PDF/A verification (see {@link ValidationResult}): a heading, and either a
 * message ("no violations", the reason a check failed) or a table of the violated rules.
 * A button copies the result as text to the clipboard.
 */
public class VerificationResultPanel extends JPanel {

    public static String heading() {
        return I18n.t("verify.heading");
    }

    private final String[] COLUMNS = {I18n.t("verify.col.rule"), I18n.t("verify.col.message"), I18n.t("verify.col.count")};

    private List<ValidationResult.Finding> findings = List.of();
    private final JTable table;
    private final JScrollPane tableScroll;
    private final JLabel message = new JLabel();
    private final JPanel center = new JPanel(new BorderLayout());
    /**
     * The text of the result for the clipboard.
     */
    private String text = heading();

    public VerificationResultPanel() {
        super(new BorderLayout(0, 6));
        table = new JTable(new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return findings.size();
            }

            @Override
            public int getColumnCount() {
                return COLUMNS.length;
            }

            @Override
            public String getColumnName(int column) {
                return COLUMNS[column];
            }

            @Override
            public Class<?> getColumnClass(int column) {
                return column == 2 ? Integer.class : String.class;
            }

            @Override
            public Object getValueAt(int row, int column) {
                ValidationResult.Finding f = findings.get(row);
                return switch (column) {
                    case 0 -> f.rule();
                    case 1 -> f.message();
                    default -> f.count();
                };
            }
        });
        table.setDefaultRenderer(String.class, new WrappingRenderer());
        table.setFillsViewportHeight(true);
        table.setCellSelectionEnabled(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(170);
        table.getColumnModel().getColumn(1).setPreferredWidth(430);
        table.getColumnModel().getColumn(2).setPreferredWidth(50);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateRowHeights();
            }
        });
        tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(620, 220));

        JLabel heading = new JLabel(heading());
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        JButton copy = new JButton(I18n.t("common.copy"), UI.getIcon("copy"));
        copy.setToolTipText(I18n.t("verify.copy.tip"));
        copy.setFocusable(false);
        copy.addActionListener(e -> copyToClipboard());
        JPanel top = new JPanel(new BorderLayout());
        top.add(heading, BorderLayout.WEST);
        top.add(copy, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        message.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));
        add(center, BorderLayout.CENTER);
        setMessage("");
    }

    /**
     * Shows a result: the table of the findings, or a message for the other states.
     */
    public void setResult(ValidationResult result) {
        switch (result.getState()) {
            case CONFORM -> setMessage(I18n.t("verify.conform"));
            case NOT_DECLARED -> setMessage(ValidationResult.notDeclaredText());
            case FAILED -> setMessage(I18n.t("verify.failed", result.getError()));
            case NOT_CONFORM -> {
                findings = result.getFindings();
                ((AbstractTableModel) table.getModel()).fireTableDataChanged();
                center.removeAll();
                center.add(tableScroll, BorderLayout.CENTER);
                text = heading() + "\n" + String.join("\n", result.toLines());
                revalidate();
                repaint();
                SwingUtilities.invokeLater(this::updateRowHeights);
            }
        }
    }

    private void setMessage(String m) {
        message.setText(m);
        center.removeAll();
        center.add(message, BorderLayout.NORTH);
        text = heading() + "\n" + m;
        revalidate();
        repaint();
    }

    /**
     * @return The result as text, as copied to the clipboard.
     */
    public String toText() {
        return text;
    }

    private void copyToClipboard() {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (Exception e) {
            Log.error("Can't copy to clipboard: %s", e.getMessage());
        }
    }

    /**
     * The messages wrap: set the height of each row to what its wrapped message needs.
     */
    private void updateRowHeights() {
        for (int row = 0; row < table.getRowCount(); ++row) {
            int height = table.getRowHeight();
            for (int column = 0; column < table.getColumnCount(); ++column) {
                Component c = table.prepareRenderer(table.getCellRenderer(row, column), row, column);
                c.setSize(table.getColumnModel().getColumn(column).getWidth(), Short.MAX_VALUE);
                height = Math.max(height, c.getPreferredSize().height);
            }
            if (table.getRowHeight(row) != height)
                table.setRowHeight(row, height);
        }
    }

    /**
     * Renders text that wraps at the width of its column.
     */
    private static final class WrappingRenderer extends JTextArea implements TableCellRenderer {
        WrappingRenderer() {
            setLineWrap(true);
            setWrapStyleWord(true);
            setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            setText(String.valueOf(value));
            setFont(table.getFont());
            setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
            setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            return this;
        }
    }
}
