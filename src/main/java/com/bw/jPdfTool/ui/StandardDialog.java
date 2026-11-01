package com.bw.jPdfTool.ui;

import com.bw.jPdfTool.I18n;
import com.bw.jPdfTool.Log;
import com.bw.jPdfTool.PdfStandard;
import com.bw.jPdfTool.PdfStandardProfile;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lets the user choose the PDF/A level of the document. For each level the dialog shows what would be changed
 * (a trial run on a copy of the document) and, in a warning color, what would be lost or can't be repaired.
 * Standards veraPDF can't validate are listed disabled: a repair could not be verified.
 */
public final class StandardDialog extends JDialog {

    /**
     * The chosen level.
     *
     * @param profile The level, null to remove the PDF/A declaration.
     */
    public record Choice(PdfStandardProfile profile) {
    }

    /**
     * Result of the trial run for one level.
     */
    private record Hints(PdfStandard.Impact impact, Map<String, Integer> changes, String error) {
    }

    private final byte[] pdf;
    private final PdfStandardProfile current;
    private final JLabel hints = new JLabel();
    private final JButton apply = new JButton(I18n.t("common.apply"));
    private final List<JRadioButton> radios = new ArrayList<>();
    private final List<PdfStandardProfile> radioProfiles = new ArrayList<>();
    private SwingWorker<Hints, Void> worker;
    private Choice choice;

    private StandardDialog(Window owner, byte[] pdf, PdfStandardProfile current) {
        super(owner, I18n.t("ui.standard.title"), Dialog.ModalityType.APPLICATION_MODAL);
        this.pdf = pdf;
        this.current = current;
        setContentPane(createContent());
        pack();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().setDefaultButton(apply);
    }

    /**
     * Shows the dialog (modal).
     *
     * @param pdf     The complete document, it is only read.
     * @param current The level the document declares, null for none.
     * @return The choice, null if cancelled.
     */
    public static Choice showDialog(Component parent, byte[] pdf, PdfStandardProfile current) {
        Window owner = parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        StandardDialog dialog = new StandardDialog(owner, pdf, current);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return dialog.choice;
    }

    private JPanel createContent() {
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // Two columns: the PDF/A levels, and the other standards.
        JPanel pdfa = column("PDF/A");
        JPanel other = column(I18n.t("standard.other"));
        ButtonGroup group = new ButtonGroup();
        addRadio(pdfa, group, null, I18n.t("standard.none"), I18n.t("standard.none.tip"));
        for (PdfStandardProfile p : PdfStandardProfile.values())
            addRadio(p.isPdfa() ? pdfa : other, group, p, p.toString(), p.description());
        JPanel list = new JPanel(new GridLayout(1, 2, 16, 0));
        list.add(pdfa);
        list.add(other);
        content.add(list, BorderLayout.NORTH);

        hints.setVerticalAlignment(JLabel.TOP);
        hints.setPreferredSize(new Dimension(520, 190));
        hints.setBorder(BorderFactory.createTitledBorder(I18n.t("standard.whatHappens")));
        content.add(hints, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        apply.addActionListener(e -> {
            choice = new Choice(selected());
            dispose();
        });
        JButton cancel = new JButton(I18n.t("common.cancel"));
        cancel.addActionListener(e -> dispose());
        buttons.add(apply);
        buttons.add(cancel);
        content.add(buttons, BorderLayout.SOUTH);

        for (int i = 0; i < radios.size(); ++i)
            if (radioProfiles.get(i) == current)
                radios.get(i).setSelected(true);
        update();
        return content;
    }

    private static JPanel column(String title) {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setBorder(BorderFactory.createTitledBorder(title));
        return column;
    }

    private void addRadio(JPanel list, ButtonGroup group, PdfStandardProfile profile, String text, String tooltip) {
        JRadioButton r = new JRadioButton(profile == current ? I18n.t("standard.current", text) : text);
        r.setToolTipText(tooltip);
        r.setAlignmentX(Component.LEFT_ALIGNMENT);
        r.addActionListener(e -> update());
        group.add(r);
        list.add(r);
        radios.add(r);
        radioProfiles.add(profile);
    }

    private PdfStandardProfile selected() {
        for (int i = 0; i < radios.size(); ++i)
            if (radios.get(i).isSelected())
                return radioProfiles.get(i);
        return current;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    /**
     * Runs the trial run for the selected level in the background and shows the result.
     */
    private void update() {
        final PdfStandardProfile target = selected();
        if (worker != null)
            worker.cancel(true);
        if (target == current) {
            apply.setEnabled(false);
            hints.setIcon(null);
            hints.setText(I18n.t("standard.isCurrent"));
            return;
        }
        apply.setEnabled(false);
        hints.setIcon(null);
        hints.setText(I18n.t("standard.calculating"));
        worker = new SwingWorker<>() {
            @Override
            protected Hints doInBackground() throws Exception {
                try (var copy = org.apache.pdfbox.Loader.loadPDF(pdf)) {
                    PdfStandard.Impact impact = PdfStandard.impact(copy, target);
                    try {
                        return new Hints(impact, PdfStandard.convertTo(copy, target), null);
                    } catch (Exception e) {
                        return new Hints(impact, Map.of(), e.getMessage());
                    }
                }
            }

            @Override
            protected void done() {
                if (isCancelled())
                    return;
                try {
                    show(get());
                } catch (Exception e) {
                    Log.error("PDF standard preview failed: %s", e.getMessage());
                    hints.setText(I18n.t("standard.previewFailed", escape(String.valueOf(e.getMessage()))));
                    apply.setEnabled(true);
                }
            }
        };
        worker.execute();
    }

    private void show(Hints result) {
        StringBuilder html = new StringBuilder("<html>");
        if (result.error() != null)
            html.append(I18n.t("standard.notPossible")).append(escape(result.error())).append("<br>");
        if (!result.impact().lost().isEmpty()) {
            html.append(I18n.t("standard.lost"));
            result.impact().lost().forEach(s -> html.append("&bull; ").append(escape(s)).append("<br>"));
        }
        if (!result.impact().notRepaired().isEmpty()) {
            html.append(I18n.t("standard.notRepaired"));
            result.impact().notRepaired().forEach(s -> html.append("&bull; ").append(escape(s)).append("<br>"));
        }
        result.impact().info().forEach(s -> html.append("<i>").append(escape(s)).append("</i><br>"));
        if (result.error() == null) {
            if (result.changes().isEmpty()) {
                html.append(I18n.t("standard.noFurther"));
            } else {
                html.append(I18n.t("standard.willChange"));
                result.changes().forEach((what, times) -> html.append("&bull; ").append(escape(what))
                        .append(times > 1 ? " (" + times + "x)" : "").append("<br>"));
            }
        }
        html.append("</html>");
        hints.setText(html.toString());
        // The warning is shown by an icon and bold headings: the colors of the look and feel are not readable
        // on every background.
        boolean warn = result.error() != null || !result.impact().lost().isEmpty()
                || !result.impact().notRepaired().isEmpty();
        hints.setIcon(warn ? UIManager.getIcon(result.error() != null ? "OptionPane.errorIcon" : "OptionPane.warningIcon")
                : null);
        hints.setIconTextGap(10);
        apply.setEnabled(result.error() == null);
    }
}
