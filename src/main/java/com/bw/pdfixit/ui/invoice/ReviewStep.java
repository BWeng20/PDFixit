package com.bw.pdfixit.ui.invoice;

import com.bw.pdfixit.DocumentInformation;
import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.PdfStandardProfile;
import com.bw.pdfixit.Preferences;
import com.bw.pdfixit.invoice.EInvoice;
import com.bw.pdfixit.invoice.InvoiceData;
import com.bw.pdfixit.template.ContinuationChoice;
import com.bw.pdfixit.template.InvoiceRenderer;
import com.bw.pdfixit.template.Layout;
import com.bw.pdfixit.template.LayoutFiles;
import com.bw.pdfixit.template.Problem;
import com.bw.pdfixit.ui.UI;
import com.bw.pdfixit.ui.template.LayoutEditorDialog;
import com.bw.pdfixit.ui.wizard.WizardDialog;
import com.bw.pdfixit.ui.wizard.WizardStep;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Step 7: the generated invoice as preview, the result of the check (EN 16931 and PDF/A-3), the problems with a link to
 * the step or the layout editor that fixes them, the PDF/A level and the printed language, and the question where the
 * following pages come from if the invoice needs more than one page and the template has one.<br>
 * The invoice is generated in the background whenever the step is entered or an option changes; {@link #problems()}
 * only reads the result. Finish hands the PDF to the window.
 */
final class ReviewStep implements WizardStep {

    /**
     * Preference: the language of the printed invoice ("de" or "en").
     */
    private static final String PREF_PRINT_LANGUAGE = Preferences.USER_PREF_INVOICE_PRINT_LANGUAGE;

    private static final int MAX_MESSAGES = 20;

    /**
     * Where a problem is fixed.
     */
    private enum Link {
        NONE, STEP, LAYOUT, CHOICE
    }

    private record Row(Problem.Severity severity, String text, Link link, int step) {
    }

    /**
     * The result of one generation.
     *
     * @param pdf      The finished invoice, null if there is none.
     * @param valid    If the PDF passed the check.
     * @param rows     What is shown.
     * @param blocking Why the invoice can't be taken, empty if it can.
     * @param rendered The preview.
     * @param choice   True if the following pages need a choice.
     */
    private record Outcome(byte[] pdf, boolean valid, List<Row> rows, List<String> blocking,
                           PagesView.Rendered rendered, boolean choice) {
    }

    private final InvoiceWizardSession session;
    private final JPanel component = new JPanel(new BorderLayout(0, 8));
    private final JComboBox<PdfStandardProfile> level = new JComboBox<>(new PdfStandardProfile[]{
            PdfStandardProfile.PDFA_3U, PdfStandardProfile.PDFA_3B, PdfStandardProfile.PDFA_3A});
    private final JComboBox<Locale> language = new JComboBox<>(new Locale[]{Locale.GERMAN, Locale.ENGLISH});
    private final PagesView pages = new PagesView();
    private final JPanel rowPanel = new JPanel();
    private final JLabel summary = new JLabel();
    private WizardDialog dialog;
    private boolean filling;

    private Outcome outcome;
    private boolean generating;
    private int generation;

    ReviewStep(InvoiceWizardSession session) {
        this.session = session;
        level.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                                                          boolean cellHasFocus) {
                String text = value == null ? "" : I18n.t("wizard.level." + ((PdfStandardProfile) value).conformance());
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
            }
        });
        level.setToolTipText(I18n.t("wizard.review.level.tip"));
        language.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                                                          boolean cellHasFocus) {
                // The name of a language in that language.
                Locale l = (Locale) value;
                return super.getListCellRendererComponent(list, l == null ? "" : l.getDisplayLanguage(l), index,
                        isSelected, cellHasFocus);
            }
        });
        language.setToolTipText(I18n.t("wizard.review.language.tip"));
        filling = true;
        level.setSelectedItem(Preferences.getInstance().getInvoicePdfaLevel());
        String saved = Preferences.getInstance().getString(PREF_PRINT_LANGUAGE, null);
        language.setSelectedItem("en".equals(saved) || (saved == null && !I18n.getLocale().getLanguage().equals("de"))
                ? Locale.ENGLISH : Locale.GERMAN);
        filling = false;
        level.addActionListener(e -> {
            if (filling)
                return;
            Preferences.getInstance().setInvoicePdfaLevel((PdfStandardProfile) level.getSelectedItem());
            generate(false);
        });
        language.addActionListener(e -> {
            if (filling)
                return;
            Preferences.getInstance().set(PREF_PRINT_LANGUAGE, ((Locale) language.getSelectedItem()).getLanguage());
            generate(false);
        });

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 0));
        options.add(new JLabel(I18n.t("wizard.review.level")));
        options.add(level);
        options.add(javax.swing.Box.createHorizontalStrut(16));
        options.add(new JLabel(I18n.t("wizard.review.language")));
        options.add(language);

        rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.Y_AXIS));
        summary.setBorder(BorderFactory.createEmptyBorder(4, 4, 6, 4));
        JPanel result = new JPanel(new BorderLayout());
        result.add(summary, BorderLayout.NORTH);
        result.add(rowPanel, BorderLayout.CENTER);
        JScrollPane resultScroll = new JScrollPane(new ScrollableColumn(result), JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        resultScroll.setBorder(BorderFactory.createTitledBorder(I18n.t("wizard.review.result")));
        resultScroll.setPreferredSize(new Dimension(380, 520));
        JScrollPane pageScroll = new JScrollPane(pages);
        pageScroll.setPreferredSize(new Dimension(620, 520));
        pageScroll.getVerticalScrollBar().setUnitIncrement(16);

        component.add(options, BorderLayout.NORTH);
        component.add(pageScroll, BorderLayout.CENTER);
        component.add(resultScroll, BorderLayout.EAST);
    }

    /**
     * A panel that is as wide as the scroll pane and scrolls vertically only, so the texts wrap.
     */
    private static final class ScrollableColumn extends JPanel implements javax.swing.Scrollable {
        ScrollableColumn(JComponent content) {
            super(new BorderLayout());
            add(content, BorderLayout.NORTH);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return visible.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    void setDialog(WizardDialog dialog) {
        this.dialog = dialog;
    }

    @Override
    public String title() {
        return I18n.t("wizard.review");
    }

    @Override
    public JComponent component() {
        return component;
    }

    @Override
    public List<String> problems() {
        if (generating || outcome == null)
            return List.of(I18n.t("wizard.review.generating"));
        return outcome.blocking();
    }

    @Override
    public void onEnter() {
        session.items.stopEditing();
        session.refresh();
        generate(true);
    }

    /**
     * Hands the invoice to the window.
     *
     * @return False if there is none, or the user declined to replace the open document.
     */
    boolean finish() {
        if (generating || outcome == null || outcome.pdf() == null || !outcome.valid())
            return false;
        return session.ui.openGenerated(outcome.pdf());
    }

    // ---------------------------------------------------------------- generation

    /**
     * Generates the invoice in the background and shows the result.
     *
     * @param ask True to ask where the following pages come from, if that is needed.
     */
    private void generate(boolean ask) {
        final int count = ++generation;
        generating = true;
        outcome = null;
        summary.setIcon(null);
        summary.setText(I18n.t("wizard.review.generating"));
        rowPanel.removeAll();
        pages.setMessage(I18n.t("wizard.review.generating"));

        List<String> dataProblems = session.dataProblems();
        if (!dataProblems.isEmpty()) {
            List<Row> rows = new ArrayList<>();
            for (String p : dataProblems) {
                int step = session.stepOfProblem(p);
                rows.add(new Row(Problem.Severity.ERROR, p, step < 0 ? Link.NONE : Link.STEP, step));
            }
            show(new Outcome(null, false, rows, List.of(dataProblems.get(0)), null, false));
            generating = false;
            return;
        }
        // The invoice as it is now: the user may go back and edit while the PDF is made.
        final InvoiceData snapshot;
        try {
            snapshot = InvoiceData.fromXml(session.data.toXml());
        } catch (Exception e) {
            Log.exception(e);
            show(failure(e));
            generating = false;
            return;
        }
        final InvoiceWizardSession.Mode mode = session.mode;
        final PdfStandardProfile pdfaLevel = (PdfStandardProfile) level.getSelectedItem();
        final Locale printed = (Locale) language.getSelectedItem();
        final Path template = session.template;
        final Layout layout = session.found == null ? null : session.found.layout();
        final Path existing = session.existingPdf;
        new SwingWorker<Outcome, Void>() {
            @Override
            protected Outcome doInBackground() {
                try {
                    return mode == InvoiceWizardSession.Mode.ADD
                            ? attach(existing, snapshot, pdfaLevel)
                            : render(template, layout, snapshot, printed, pdfaLevel);
                } catch (Exception e) {
                    Log.exception(e);
                    return failure(e);
                }
            }

            @Override
            protected void done() {
                if (count != generation)
                    return;
                try {
                    Outcome result = get();
                    generating = false;
                    show(result);
                    if (result.choice() && ask)
                        askChoice();
                } catch (Exception e) {
                    generating = false;
                    show(failure(e));
                }
            }
        }.execute();
    }

    private static Outcome failure(Throwable e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        return new Outcome(null, false, List.of(new Row(Problem.Severity.ERROR, message, Link.NONE, -1)),
                List.of(message), null, false);
    }

    /**
     * A new invoice on the template.
     */
    private static Outcome render(Path template, Layout layout, InvoiceData data, Locale printed,
                                  PdfStandardProfile level) throws Exception {
        byte[] bytes = Files.readAllBytes(template);
        InvoiceRenderer.Generated g = InvoiceRenderer.generate(bytes, layout, data, printed, level);
        List<Row> rows = new ArrayList<>();
        List<String> blocking = new ArrayList<>();
        for (Problem p : g.render().problems()) {
            String text = p.page() > 0 ? I18n.t("wizard.review.onPage", p.message(), p.page()) : p.message();
            rows.add(new Row(p.severity(), text, p.element() != null ? Link.LAYOUT : Link.NONE, -1));
            if (p.severity() == Problem.Severity.ERROR)
                blocking.add(text);
        }
        boolean choice = g.render().choiceNeeded();
        if (choice) {
            rows.add(new Row(Problem.Severity.ERROR, I18n.t("wizard.choice.text"), Link.CHOICE, -1));
            blocking.add(I18n.t("wizard.choice.text"));
        }
        return finished(g.pdf(), g.validation(), rows, blocking, choice);
    }

    /**
     * The invoice attached to an existing PDF, which keeps its pages and its document information.
     */
    private static Outcome attach(Path existing, InvoiceData data, PdfStandardProfile level) throws Exception {
        byte[] original = Files.readAllBytes(existing);
        byte[] pdf;
        try {
            // Repairs what PDF/A forbids (PdfStandard.repairPdfa inside), attaches and validates.
            pdf = EInvoice.attachXmlStrict(original, data.toXml(), level);
        } catch (EInvoice.NotConformException e) {
            return failure(e);
        }
        // The library replaces the producer etc.: the document keeps its own.
        try (PDDocument from = Loader.loadPDF(original); PDDocument to = Loader.loadPDF(pdf)) {
            if (DocumentInformation.copy(from, to)) {
                ByteArrayOutputStream os = new ByteArrayOutputStream(pdf.length + 4096);
                to.save(os);
                pdf = os.toByteArray();
            }
        }
        EInvoice.Validation validation = EInvoice.validate(pdf, "invoice");
        return finished(pdf, validation, new ArrayList<>(), new ArrayList<>(), false);
    }

    /**
     * Adds the result of the check and the preview to the rows.
     */
    private static Outcome finished(byte[] pdf, EInvoice.Validation validation, List<Row> rows, List<String> blocking,
                                    boolean choice) throws IOException {
        boolean valid = false;
        PagesView.Rendered rendered = null;
        if (pdf != null && validation != null) {
            valid = validation.valid();
            if (!valid && blocking.isEmpty())
                blocking.add(I18n.t("wizard.review.invalid"));
            for (String m : new LinkedHashSet<>(validation.messages()).stream().limit(MAX_MESSAGES).toList()) {
                boolean warning = m.toLowerCase(Locale.ROOT).startsWith("warning");
                rows.add(new Row(warning ? Problem.Severity.WARNING : Problem.Severity.ERROR, m, Link.NONE, -1));
            }
            rendered = PagesView.render(pdf);
        }
        return new Outcome(pdf, valid, rows, blocking, rendered, choice);
    }

    /**
     * Shows the result.
     */
    private void show(Outcome result) {
        outcome = result;
        if (result.pdf() != null && result.valid()) {
            summary.setIcon(UI.getIcon("svg/status-ok"));
            summary.setText(I18n.t("wizard.review.valid"));
        } else {
            summary.setIcon(UI.getIcon("svg/status-error"));
            summary.setText(I18n.t(result.pdf() == null ? "wizard.review.noPdf" : "wizard.review.invalid"));
        }
        if (result.rendered() != null)
            pages.setPages(result.rendered(), null);
        else
            pages.setMessage(I18n.t("wizard.review.noPreview"));
        rowPanel.removeAll();
        for (Row row : result.rows())
            rowPanel.add(rowComponent(row));
        rowPanel.revalidate();
        rowPanel.repaint();
    }

    private JComponent rowComponent(Row row) {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(TemplateStep.row(TemplateStep.icon(row.severity()), row.text()), BorderLayout.CENTER);
        JButton button = null;
        switch (row.link()) {
            case STEP -> {
                button = new JButton(I18n.t("wizard.review.goStep", session.stepTitle(row.step())));
                button.addActionListener(e -> dialog.goTo(row.step()));
            }
            case LAYOUT -> {
                button = new JButton(I18n.t("wizard.template.edit"));
                button.addActionListener(e -> editLayout());
            }
            case CHOICE -> {
                button = new JButton(I18n.t("wizard.choice.button"));
                button.addActionListener(e -> askChoice());
            }
            default -> {
            }
        }
        if (button != null) {
            JPanel south = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
            south.add(button);
            panel.add(south, BorderLayout.SOUTH);
        }
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height + 40));
        return panel;
    }

    // ---------------------------------------------------------------- links

    /**
     * Opens the layout editor of the template, then generates again with the layout as it is now.
     */
    private void editLayout() {
        LayoutEditorDialog.edit(dialog, session.template);
        try {
            session.found = LayoutFiles.find(session.template);
        } catch (Exception e) {
            Log.exception(e);
            show(failure(e));
            return;
        }
        generate(false);
    }

    /**
     * Asks where the following pages come from, and generates again with the answer.
     */
    private void askChoice() {
        if (session.found == null)
            return;
        JCheckBox remember = new JCheckBox(I18n.t("wizard.choice.remember"), true);
        Object[] message = {I18n.t("wizard.choice.text"), javax.swing.Box.createVerticalStrut(8), remember};
        String[] options = {I18n.t("wizard.choice.page1"), I18n.t("wizard.choice.pdf"), I18n.t("wizard.choice.blank"),
                I18n.t("wizard.cancel")};
        int answer = JOptionPane.showOptionDialog(dialog, message, I18n.t("wizard.invoice.title"),
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        Layout.Continuation source;
        Path pdf = null;
        switch (answer) {
            case 0 -> source = Layout.Continuation.PAGE1;
            case 1 -> {
                pdf = choosePdf();
                if (pdf == null)
                    return;
                source = Layout.Continuation.PDF;
            }
            case 2 -> source = Layout.Continuation.BLANK;
            default -> {
                return;
            }
        }
        Layout layout = session.found.layout();
        try {
            if (remember.isSelected())
                ContinuationChoice.applyAndRemember(layout, source, pdf, session.template);
            else
                ContinuationChoice.apply(layout, source, pdf);
        } catch (IOException e) {
            // The choice is used for this invoice anyway.
            Log.exception(e);
            ContinuationChoice.apply(layout, source, pdf);
            JOptionPane.showMessageDialog(dialog, I18n.t("wizard.choice.notSaved", e.getMessage()),
                    I18n.t("wizard.invoice.title"), JOptionPane.WARNING_MESSAGE);
        }
        generate(false);
    }

    private Path choosePdf() {
        JFileChooser chooser = new JFileChooser();
        String dir = Preferences.getInstance().getString(Preferences.USER_PREF_LAST_TEMPLATE_DIR, null);
        if (dir != null)
            chooser.setCurrentDirectory(new File(dir));
        chooser.setFileFilter(new FileNameExtensionFilter("PDF", "pdf"));
        chooser.setDialogTitle(I18n.t("wizard.choice.pdf.title"));
        if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION)
            return null;
        return chooser.getSelectedFile().toPath();
    }
}
