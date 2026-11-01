package com.bw.pdfixit.ui.fonts;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.fonts.ImportedFont;
import com.bw.pdfixit.fonts.PdfFontEmbedder;
import com.bw.pdfixit.fonts.ImportedFonts;
import com.bw.pdfixit.Log;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.DefaultListSelectionModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Manages the font files the user imported: they can replace the fonts of a document besides the bundled fonts.
 * Only the path is stored (see {@link ImportedFonts}), removing an entry doesn't delete the file.
 */
public final class ImportedFontsDialog {

    private static File lastDirectory;

    private ImportedFontsDialog() {
    }

    public static void show(Component parent) {
        show(parent, ImportedFonts.file());
    }

    static void show(Component parent, Path store) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), I18n.t("fonts.manage.title"),
                JDialog.ModalityType.APPLICATION_MODAL);

        DefaultListModel<ImportedFont> model = new DefaultListModel<>();
        Runnable reload = () -> {
            model.clear();
            try {
                for (ImportedFonts.Entry e : ImportedFonts.load(store))
                    model.addElement(new ImportedFont(e.name(), Path.of(e.path())));
            } catch (Exception e) {
                Log.exception(e);
                JOptionPane.showMessageDialog(dialog, e.getMessage(), I18n.t("fonts.readFailed"), JOptionPane.ERROR_MESSAGE);
            }
        };

        JList<ImportedFont> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                String text = "";
                if (value instanceof ImportedFont f)
                    // Only checks if the file is there, the font is not read.
                    text = "<html><b>" + escape(f.getName()) + "</b>" + (f.exists() ? "" : " <font color='#c04040'>(" + I18n.t("fonts.fileMissing") + ")</font>")
                            + "<br><font size='-1'>" + escape(f.getPath().toString()) + "</font></html>";
                return super.getListCellRendererComponent(l, text, index, isSelected, cellHasFocus);
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(560, 200));
        scroll.setBorder(BorderFactory.createTitledBorder(I18n.t("fonts.imported")));

        // Read only: the bundled fonts can't be changed, so nothing can be selected.
        DefaultListModel<PdfFontEmbedder.Variant> bundledModel = new DefaultListModel<>();
        bundledModel.addAll(PdfFontEmbedder.bundled());
        JList<PdfFontEmbedder.Variant> bundledList = new JList<>(bundledModel);
        bundledList.setSelectionModel(new DefaultListSelectionModel() {
            @Override
            public void setSelectionInterval(int index0, int index1) {
            }

            @Override
            public void addSelectionInterval(int index0, int index1) {
            }
        });
        bundledList.setFocusable(false);
        bundledList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                String text = "";
                if (value instanceof PdfFontEmbedder.Variant v)
                    text = "<html><b>" + escape(v.displayName()) + "</b> <font size='-1'>&ndash; "
                            + escape(v.family().description) + "</font></html>";
                return super.getListCellRendererComponent(l, text, index, false, false);
            }
        });
        JScrollPane bundledScroll = new JScrollPane(bundledList);
        bundledScroll.setPreferredSize(new Dimension(560, 130));
        bundledScroll.setBorder(BorderFactory.createTitledBorder(I18n.t("fonts.bundledReadOnly")));

        JButton add = new JButton(I18n.t("fonts.add"));
        add.setToolTipText(I18n.t("fonts.add.tip"));
        JButton remove = new JButton(I18n.t("redact.remove"));
        remove.setToolTipText(I18n.t("fonts.remove.tip"));
        remove.setEnabled(false);
        JButton close = new JButton(I18n.t("about.close"));
        close.addActionListener(e -> dialog.dispose());
        list.addListSelectionListener(e -> remove.setEnabled(!list.isSelectionEmpty()));

        add.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(lastDirectory);
            chooser.setDialogTitle(I18n.t("fonts.import.title"));
            chooser.setMultiSelectionEnabled(true);
            chooser.setFileFilter(new FileNameExtensionFilter(I18n.t("fonts.import.filter"), "ttf"));
            if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION)
                return;
            lastDirectory = chooser.getCurrentDirectory();
            List<Path> files = Arrays.stream(chooser.getSelectedFiles()).map(File::toPath).toList();
            ImportedFonts.ImportResult result = ImportedFonts.importFiles(store, files);
            reload.run();
            if (!result.errors().isEmpty())
                JOptionPane.showMessageDialog(dialog, "<html>" + String.join("<br>", result.errors().stream().map(ImportedFontsDialog::escape).toList())
                        + "</html>", I18n.t("fonts.notImported"), JOptionPane.WARNING_MESSAGE);
        });

        remove.addActionListener(e -> {
            try {
                for (ImportedFont f : list.getSelectedValuesList())
                    ImportedFonts.remove(store, f);
            } catch (Exception ex) {
                Log.exception(ex);
                JOptionPane.showMessageDialog(dialog, ex.getMessage(), I18n.t("fonts.removeFailed"), JOptionPane.ERROR_MESSAGE);
            }
            reload.run();
        });

        JLabel info = new JLabel(I18n.t("fonts.manage.info"));
        info.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(add);
        buttons.add(remove);
        buttons.add(close);
        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        content.add(info, BorderLayout.NORTH);
        JPanel lists = new JPanel(new BorderLayout());
        lists.add(bundledScroll, BorderLayout.NORTH);
        lists.add(scroll, BorderLayout.CENTER);
        content.add(lists, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        south.add(buttons, BorderLayout.EAST);
        content.add(south, BorderLayout.SOUTH);
        dialog.add(content);

        reload.run();
        dialog.getRootPane().setDefaultButton(close);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
