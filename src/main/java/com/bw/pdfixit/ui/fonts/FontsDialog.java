package com.bw.pdfixit.ui.fonts;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.fonts.FontSource;
import com.bw.pdfixit.fonts.ImportedFont;
import com.bw.pdfixit.fonts.ImportedFonts;
import com.bw.pdfixit.Log;
import org.apache.pdfbox.cos.COSDictionary;
import com.bw.pdfixit.fonts.PdfFontEmbedder;
import com.bw.pdfixit.fonts.PdfFontMerger;
import com.bw.pdfixit.fonts.PdfFonts;
import com.bw.pdfixit.model.DocumentProxy;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows the fonts of the document and replaces the selected one by one of the bundled fonts.
 * The best match is proposed, the user may choose another one.
 */
public final class FontsDialog {

    private static String[] columns() {
        return new String[]{I18n.t("fonts.col.font"), I18n.t("fonts.col.type"), I18n.t("fonts.col.embedded"), I18n.t("fonts.col.pages")};
    }
    /**
     * Filler text for the preview: running text, digits, and the most common symbols and accents of Latin text.
     */
    private static final String PREVIEW_TEXT = "Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy "
            + "eirmod tempor invidunt ut labore et dolore magna aliquyam erat, sed diam voluptua. At vero eos et "
            + "accusam et justo duo dolores et ea rebum.\n"
            + "0123456789  € $ % & @  ÄÖÜ äöü ß  éèê ñ";

    private FontsDialog() {
    }

    static final class FontTableModel extends AbstractTableModel {
        private List<PdfFonts.FontInfo> fonts = List.of();

        void setFonts(List<PdfFonts.FontInfo> fonts) {
            this.fonts = fonts;
            fireTableDataChanged();
        }

        PdfFonts.FontInfo get(int row) {
            return fonts.get(row);
        }

        /**
         * The font of the row, null if there is no such row (e.g. the selection of the table is older than the list).
         */
        PdfFonts.FontInfo getOrNull(int row) {
            return row >= 0 && row < fonts.size() ? fonts.get(row) : null;
        }

        @Override
        public int getRowCount() {
            return fonts.size();
        }

        @Override
        public int getColumnCount() {
            return columns().length;
        }

        @Override
        public String getColumnName(int column) {
            return columns()[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            PdfFonts.FontInfo f = fonts.get(row);
            return switch (column) {
                case 0 -> f.name();
                case 1 -> f.type();
                case 2 -> f.embedded() ? I18n.t("common.yes") : I18n.t("common.no");
                default -> f.pageText();
            };
        }
    }

    public static void show(Component parent, DocumentProxy proxy) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), I18n.t("menu.fonts"),
                JDialog.ModalityType.APPLICATION_MODAL);

        FontTableModel model = new FontTableModel();
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(1).setPreferredWidth(100);
        table.getColumnModel().getColumn(2).setPreferredWidth(70);
        table.getColumnModel().getColumn(3).setPreferredWidth(110);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(560, 260));

        JLabel summary = new JLabel(" ");
        JButton replace = new JButton(I18n.t("fonts.replace"));
        JButton merge = new JButton(I18n.t("fonts.merge"));
        merge.setToolTipText(I18n.t("fonts.merge.tip"));
        JButton close = new JButton(I18n.t("about.close"));
        close.addActionListener(e -> dialog.dispose());

        Runnable reload = () -> {
            // Remember the font itself: its name changes when it is replaced, and the order may change.
            PdfFonts.FontInfo selected = model.getOrNull(table.getSelectedRow());
            COSDictionary selectedFont = selected == null ? null : selected.dictionary();
            String selectedName = selected == null ? null : selected.name();
            List<PdfFonts.FontInfo> fonts = proxy.getFonts();
            // The table clears its selection only after the listeners of the model were called, and the list may be
            // shorter now: don't let them see a row which doesn't exist any more.
            table.clearSelection();
            model.setFonts(fonts);
            long missing = fonts.stream().filter(f -> !f.embedded()).count();
            summary.setText(I18n.t("fonts.summary", fonts.size(), missing));
            int row = findRow(fonts, selectedFont, selectedName);
            if (row >= 0) {
                table.setRowSelectionInterval(row, row);
                table.scrollRectToVisible(table.getCellRect(row, 0, true));
            }
        };

        Runnable updateButton = () -> {
            PdfFonts.FontInfo selectedFont = model.getOrNull(table.getSelectedRow());
            if (selectedFont == null) {
                replace.setEnabled(false);
                replace.setToolTipText(I18n.t("fonts.replace.selectTip"));
                return;
            }
            String reason = selectedFont.cannotReplaceReason();
            replace.setEnabled(reason == null);
            replace.setToolTipText(reason != null ? reason
                    : I18n.t("fonts.replace.tip"));
        };
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting())
                updateButton.run();
        });
        table.getModel().addTableModelListener(e -> updateButton.run());

        replace.addActionListener(e -> {
            PdfFonts.FontInfo font = model.getOrNull(table.getSelectedRow());
            if (font == null)
                return;
            FontSource source = chooseReplacement(dialog, font);
            if (source == null)
                return;
            try {
                proxy.replaceFont(font.dictionary(), source);
                reload.run();
            } catch (Exception ex) {
                Log.exception(ex);
                JOptionPane.showMessageDialog(dialog, ex.getMessage(), I18n.t("fonts.replaceFailed"), JOptionPane.ERROR_MESSAGE);
            }
        });

        merge.addActionListener(e -> {
            try {
                PdfFontMerger.Result found = proxy.findDuplicateFonts();
                if (found.duplicates() == 0) {
                    JOptionPane.showMessageDialog(dialog, I18n.t("fonts.noMerge"),
                            I18n.t("fonts.merge.title"), JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                int option = JOptionPane.showConfirmDialog(dialog, I18n.t("fonts.merge.question", found.duplicates(), sizeText(found.savedBytes())),
                        I18n.t("fonts.merge.title"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (option != JOptionPane.OK_OPTION)
                    return;
                PdfFontMerger.Result merged = proxy.mergeDuplicateFonts();
                reload.run();
                summary.setText(summary.getText() + " – " + I18n.t("fonts.merged", merged.duplicates()));
            } catch (Exception ex) {
                Log.exception(ex);
                JOptionPane.showMessageDialog(dialog, ex.getMessage(), I18n.t("fonts.mergeFailed"), JOptionPane.ERROR_MESSAGE);
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(merge);
        buttons.add(replace);
        buttons.add(close);
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        south.add(summary, BorderLayout.WEST);
        south.add(buttons, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        content.add(scroll, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        dialog.add(content);

        reload.run();
        updateButton.run();
        dialog.getRootPane().setDefaultButton(close);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    /**
     * Asks for the font to use. The best match of the bundled fonts is preselected.
     *
     * @return The chosen font, null if cancelled.
     */
    private static FontSource chooseReplacement(JDialog owner, PdfFonts.FontInfo font) {
        ReplacePanel panel = new ReplacePanel(font.name(), PdfFontEmbedder.bestMatch(font.dictionary()));
        int option = JOptionPane.showConfirmDialog(owner, panel, I18n.t("fonts.replace.title"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return option == JOptionPane.OK_OPTION ? panel.getSelected() : null;
    }

    /**
     * A line of the font list which only introduces the fonts below it. It can't be chosen.
     */
    record Header(String text) {
    }

    /**
     * Ignores the headers: choosing one (by mouse or keyboard) keeps the font chosen before.
     */
    private static final class FontListModel extends DefaultComboBoxModel<Object> {
        @Override
        public void setSelectedItem(Object item) {
            if (!(item instanceof Header))
                super.setSelectedItem(item);
        }
    }

    /**
     * The content of the "Replace Font" dialog: the choice of the font with a preview. The bundled fonts are listed
     * first, the imported fonts (see {@link ImportedFonts}) below them under their own heading.<br>
     * The size of the panel doesn't depend on the font of the preview: the text area has a fixed size and scrolls if
     * the text doesn't fit. Otherwise a wider font would make the content larger than the dialog, which was
     * packed for the first font.
     */
    static final class ReplacePanel extends JPanel {
        private static final Dimension PREVIEW_SIZE = new Dimension(560, 176);

        private final Path store;
        private final PdfFontEmbedder.Variant best;
        private final FontListModel model = new FontListModel();
        private final JComboBox<Object> combo = new JComboBox<>(model);
        private final JTextArea preview;

        ReplacePanel(String fontName, PdfFontEmbedder.Variant best) {
            this(fontName, best, ImportedFonts.file());
        }

        /**
         * @param store The file with the imported fonts.
         */
        ReplacePanel(String fontName, PdfFontEmbedder.Variant best, Path store) {
            super(new GridBagLayout());
            this.store = store;
            this.best = best;
            fillList(best);
            combo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    String text = "";
                    Component c;
                    if (value instanceof Header h) {
                        text = h.text();
                        c = super.getListCellRendererComponent(list, text, index, false, false);
                        c.setFont(c.getFont().deriveFont(Font.BOLD));
                        c.setEnabled(false);
                        return c;
                    }
                    if (value instanceof PdfFontEmbedder.Variant v)
                        text = v.displayName() + " – " + v.family().description + (v.equals(best) ? " " + I18n.t("fonts.bestMatch") : "");
                    else if (value instanceof ImportedFont f)
                        text = f.getName() + " (" + f.getPath().getFileName() + ")" + (f.exists() ? "" : " – " + I18n.t("fonts.fileMissing"));
                    // The fonts below a heading are indented in the list, not in the closed box.
                    return super.getListCellRendererComponent(list, index >= 0 ? "    " + text : text, index, isSelected, cellHasFocus);
                }
            });

            preview = new JTextArea(PREVIEW_TEXT);
            preview.setEditable(false);
            preview.setLineWrap(true);
            preview.setWrapStyleWord(true);
            preview.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            JScrollPane previewScroll = new JScrollPane(preview, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            Color line = UIManager.getColor("Component.borderColor");
            previewScroll.setBorder(BorderFactory.createLineBorder(line != null ? line : Color.GRAY));
            previewScroll.setPreferredSize(PREVIEW_SIZE);
            previewScroll.setMinimumSize(PREVIEW_SIZE);

            JButton imported = new JButton(I18n.t("menu.fonts.manage"));
            imported.setToolTipText(I18n.t("fonts.manage.tip"));
            imported.addActionListener(e -> {
                ImportedFontsDialog.show(this, store);
                // The list may have changed.
                FontSource keep = getSelected();
                fillList(keep == null ? best : keep);
                showPreview();
            });

            combo.addActionListener(e -> showPreview());
            showPreview();

            JPanel choice = new JPanel(new BorderLayout(6, 0));
            choice.add(combo, BorderLayout.CENTER);
            choice.add(imported, BorderLayout.EAST);

            GridBagConstraints gc = new GridBagConstraints();
            gc.gridx = 0;
            gc.gridy = 0;
            gc.anchor = GridBagConstraints.WEST;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.weightx = 1;
            add(new JLabel(I18n.t("fonts.replaceBy", escape(fontName))), gc);
            gc.gridy++;
            gc.insets = new Insets(6, 0, 0, 0);
            add(choice, gc);
            gc.gridy++;
            gc.insets = new Insets(10, 0, 6, 0);
            add(previewScroll, gc);
            gc.gridy++;
            gc.insets = new Insets(0, 0, 0, 0);
            add(new JLabel(I18n.t("fonts.replace.note")), gc);
        }

        /**
         * Fills the list: the bundled fonts, then the imported ones. The font files are not read, only the list
         * of the paths is.
         *
         * @param select The font to select, if it is in the list.
         */
        private void fillList(FontSource select) {
            List<Object> items = new ArrayList<>();
            items.add(new Header(I18n.t("fonts.bundled")));
            items.addAll(PdfFontEmbedder.bundled());
            List<ImportedFont> imported = ImportedFonts.fonts(store);
            if (!imported.isEmpty()) {
                items.add(new Header(I18n.t("fonts.imported")));
                items.addAll(imported);
            }
            model.removeAllElements();
            items.forEach(model::addElement);
            model.setSelectedItem(items.contains(select) ? select : best);
        }

        /**
         * The chosen font, null if none.
         */
        FontSource getSelected() {
            return model.getSelectedItem() instanceof FontSource f ? f : null;
        }

        void setSelected(FontSource font) {
            model.setSelectedItem(font);
        }

        /**
         * Chooses an entry of the list as the user does, also a heading (which is ignored).
         */
        void selectItem(Object item) {
            combo.setSelectedItem(item);
        }

        JTextArea getPreview() {
            return preview;
        }

        /**
         * The entries of the list: headings and fonts.
         */
        List<Object> getItems() {
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < model.getSize(); ++i)
                items.add(model.getElementAt(i));
            return items;
        }

        /**
         * Shows the text in the chosen font. A font which can't be loaded must not break the dialog: the reason is
         * shown instead of the text.
         */
        private void showPreview() {
            try {
                Font f = previewFont(getSelected());
                preview.setText(PREVIEW_TEXT);
                if (f != null)
                    preview.setFont(f);
            } catch (Exception e) {
                preview.setFont(UIManager.getFont("TextArea.font") != null ? UIManager.getFont("TextArea.font") : preview.getFont());
                preview.setText(I18n.t("fonts.previewFailed", e.getMessage()));
            }
            preview.setCaretPosition(0);
        }
    }

    private static final Map<String, Font> PREVIEW_FONTS = new HashMap<>();

    /**
     * The font for the preview, loaded when it is first shown.
     *
     * @return The font, null if there is none.
     * @throws Exception If an imported font can't be read.
     */
    private static synchronized Font previewFont(FontSource source) throws Exception {
        if (source instanceof PdfFontEmbedder.Variant v) {
            Font cached = PREVIEW_FONTS.get(v.resource());
            if (cached == null) {
                try (InputStream is = FontsDialog.class.getResourceAsStream(v.resource())) {
                    if (is == null)
                        return null;
                    cached = Font.createFont(Font.TRUETYPE_FONT, is).deriveFont(18f);
                    PREVIEW_FONTS.put(v.resource(), cached);
                } catch (Exception e) {
                    Log.debug("No preview for %s: %s", v.resource(), e.getMessage());
                    return null;
                }
            }
            return cached;
        }
        if (source instanceof ImportedFont f) {
            if (!f.exists())
                throw new java.io.FileNotFoundException(I18n.t("fonts.fileGone", f.getPath()));
            // The file may have been replaced since.
            String key = f.getPath() + "|" + java.nio.file.Files.getLastModifiedTime(f.getPath()).toMillis();
            Font cached = PREVIEW_FONTS.get(key);
            if (cached == null) {
                // From a stream: Java works with a copy then and doesn't keep the file of the user open.
                try (InputStream is = java.nio.file.Files.newInputStream(f.getPath())) {
                    cached = Font.createFont(Font.TRUETYPE_FONT, is).deriveFont(18f);
                }
                PREVIEW_FONTS.put(key, cached);
            }
            return cached;
        }
        return null;
    }

    /**
     * Finds the row to select after the fonts changed: the same font (it may have another name now), else a font
     * with the same name (e.g. the one a duplicate was merged into).
     *
     * @return The index, -1 if there is none.
     */
    static int findRow(List<PdfFonts.FontInfo> fonts, COSDictionary font, String name) {
        if (font != null)
            for (int i = 0; i < fonts.size(); ++i)
                if (fonts.get(i).dictionary() == font)
                    return i;
        if (name != null)
            for (int i = 0; i < fonts.size(); ++i)
                if (fonts.get(i).name().equals(name))
                    return i;
        return -1;
    }

    private static String sizeText(long bytes) {
        if (bytes < 1024)
            return I18n.t("size.bytes", bytes);
        if (bytes < 1024 * 1024)
            return String.format("%.0f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
