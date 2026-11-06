package com.bw.pdfixit.ui.invoice;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.Preferences;
import com.bw.pdfixit.template.LayoutFiles;
import com.bw.pdfixit.template.Problem;
import com.bw.pdfixit.template.TemplateCheck;
import com.bw.pdfixit.ui.UI;
import com.bw.pdfixit.ui.template.LayoutEditorDialog;
import com.bw.pdfixit.ui.wizard.WizardStep;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Step 6: the template (a letterhead PDF) for a new visible invoice, chosen from the last ones or with the file
 * browser, a preview of its pages with the regions of the layout, what the check of the template found, and the button
 * to edit the layout. It is left out if the invoice is attached to an existing PDF.<br>
 * The template is loaded in the background (check and preview); {@link #problems()} only reads the result.
 */
final class TemplateStep implements WizardStep {

    private final InvoiceWizardSession session;
    private final JPanel component = new JPanel(new BorderLayout(0, 8));
    private final JComboBox<String> recent = new JComboBox<>();
    private final JButton edit = new JButton(I18n.t("wizard.template.edit"));
    private final PagesView pages = new PagesView();
    private final JPanel findings = new JPanel();
    private boolean filling;

    /**
     * The state of the loaded template, null/empty while it is loading.
     */
    private boolean loading;
    private String loadError;
    private List<Problem> problems = List.of();
    /**
     * Counts the loads, so the result of an older one is dropped.
     */
    private int loadCount;

    TemplateStep(InvoiceWizardSession session) {
        this.session = session;
        recent.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                                                          boolean cellHasFocus) {
                String text = " ";
                if (value != null) {
                    Path file = Path.of((String) value);
                    text = file.getFileName() + (file.getParent() == null ? "" : "   (" + file.getParent() + ")");
                }
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
            }
        });
        recent.addActionListener(e -> {
            if (!filling && recent.getSelectedItem() != null)
                choose(Path.of((String) recent.getSelectedItem()));
        });
        JButton browse = new JButton(I18n.t("wizard.goal.browse"));
        browse.addActionListener(e -> browse());
        edit.setEnabled(false);
        edit.addActionListener(e -> editLayout());

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.add(new JLabel(I18n.t("wizard.template.label")), BorderLayout.WEST);
        top.add(recent, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        buttons.add(browse);
        buttons.add(edit);
        top.add(buttons, BorderLayout.EAST);

        findings.setLayout(new BoxLayout(findings, BoxLayout.Y_AXIS));
        JScrollPane pageScroll = new JScrollPane(pages);
        pageScroll.setPreferredSize(new Dimension(620, 520));
        pageScroll.getVerticalScrollBar().setUnitIncrement(16);
        JScrollPane findingScroll = new JScrollPane(findings, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        findingScroll.setPreferredSize(new Dimension(340, 520));
        findingScroll.setBorder(BorderFactory.createTitledBorder(I18n.t("wizard.template.findings")));

        component.add(top, BorderLayout.NORTH);
        component.add(pageScroll, BorderLayout.CENTER);
        component.add(findingScroll, BorderLayout.EAST);
        pages.setMessage(I18n.t("wizard.template.none"));
    }

    @Override
    public String title() {
        return I18n.t("wizard.template");
    }

    @Override
    public JComponent component() {
        return component;
    }

    @Override
    public boolean skipped() {
        return session.mode == InvoiceWizardSession.Mode.ADD;
    }

    @Override
    public List<String> problems() {
        if (session.template == null)
            return List.of(I18n.t("wizard.template.choose"));
        if (loading)
            return List.of(I18n.t("wizard.template.loading"));
        if (loadError != null)
            return List.of(loadError);
        List<String> errors = new ArrayList<>();
        for (Problem p : problems)
            if (p.severity() == Problem.Severity.ERROR)
                errors.add(p.message());
        return errors;
    }

    @Override
    public void onEnter() {
        fillRecent();
        if (session.template == null) {
            // The template used last, if it is still there.
            if (recent.getItemCount() > 0)
                choose(Path.of(recent.getItemAt(0)));
        } else {
            // The file or its layout may have changed since.
            load();
        }
    }

    /**
     * Fills the list with the last templates; one that is gone is dropped.
     */
    private void fillRecent() {
        filling = true;
        try {
            recent.removeAllItems();
            for (String path : Preferences.getInstance().getRecentTemplates()) {
                if (Files.isRegularFile(Path.of(path)))
                    recent.addItem(path);
                else
                    Preferences.getInstance().removeRecentTemplate(path);
            }
            if (session.template != null) {
                String current = session.template.toAbsolutePath().toString();
                if (((javax.swing.DefaultComboBoxModel<String>) recent.getModel()).getIndexOf(current) < 0)
                    recent.insertItemAt(current, 0);
                recent.setSelectedItem(current);
            }
        } finally {
            filling = false;
        }
    }

    private void browse() {
        JFileChooser chooser = new JFileChooser();
        String dir = Preferences.getInstance().getString(Preferences.USER_PREF_LAST_TEMPLATE_DIR, null);
        if (dir != null)
            chooser.setCurrentDirectory(new File(dir));
        chooser.setFileFilter(new FileNameExtensionFilter("PDF", "pdf"));
        chooser.setDialogTitle(I18n.t("layoutEditor.chooseTemplate"));
        if (chooser.showOpenDialog(component) != JFileChooser.APPROVE_OPTION)
            return;
        File file = chooser.getSelectedFile();
        if (file.getParentFile() != null)
            Preferences.getInstance().set(Preferences.USER_PREF_LAST_TEMPLATE_DIR, file.getParentFile().getAbsolutePath());
        choose(file.toPath());
    }

    /**
     * Takes another template.
     */
    private void choose(Path file) {
        session.template = file.toAbsolutePath();
        session.found = null;
        Preferences.getInstance().addRecentTemplate(session.template.toString());
        fillRecent();
        load();
    }

    private void editLayout() {
        if (session.template == null)
            return;
        LayoutEditorDialog.edit(session.dialog(), session.template);
        load();
    }

    /**
     * Reads the layout, checks the template and renders its pages, in the background.
     */
    private void load() {
        final Path file = session.template;
        final int count = ++loadCount;
        loading = true;
        loadError = null;
        edit.setEnabled(false);
        pages.setMessage(I18n.t("wizard.template.loading"));
        new SwingWorker<Loaded, Void>() {
            @Override
            protected Loaded doInBackground() throws Exception {
                LayoutFiles.Found found = LayoutFiles.find(file);
                List<Problem> checked = TemplateCheck.check(file, found);
                PagesView.Rendered rendered = null;
                try {
                    rendered = PagesView.render(Files.readAllBytes(file));
                } catch (Exception e) {
                    // Encrypted or damaged: the check says why.
                    Log.info("Template preview: %s", e.getMessage());
                }
                return new Loaded(found, checked, rendered);
            }

            @Override
            protected void done() {
                if (count != loadCount)
                    return;
                loading = false;
                try {
                    Loaded loaded = get();
                    session.found = loaded.found();
                    problems = loaded.problems();
                    if (loaded.rendered() != null)
                        pages.setPages(loaded.rendered(), loaded.found().layout());
                    else
                        pages.setMessage(I18n.t("wizard.template.noPreview"));
                } catch (Exception e) {
                    Log.exception(e);
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    loadError = I18n.t("wizard.template.loadFailed", cause.getMessage());
                    problems = List.of();
                    pages.setMessage(I18n.t("wizard.template.noPreview"));
                }
                edit.setEnabled(true);
                showFindings();
            }
        }.execute();
    }

    private record Loaded(LayoutFiles.Found found, List<Problem> problems, PagesView.Rendered rendered) {
    }

    private void showFindings() {
        findings.removeAll();
        if (loadError != null)
            findings.add(row(UI.getIcon("svg/status-error"), loadError));
        for (Problem p : problems)
            findings.add(row(icon(p.severity()), p.message()));
        if (loadError == null && problems.isEmpty())
            findings.add(row(UI.getIcon("svg/status-ok"), I18n.t("wizard.template.noFindings")));
        findings.revalidate();
        findings.repaint();
    }

    static Icon icon(Problem.Severity severity) {
        return switch (severity) {
            case ERROR -> UI.getIcon("svg/status-error");
            case WARNING -> InvoiceEditor.smallWarning();
            case INFO -> InvoiceEditor.smallInfo();
        };
    }

    static JComponent row(Icon icon, String text) {
        JLabel label = new JLabel("<html><body style='width: 230px'>" + escape(text) + "</body></html>", icon,
                JLabel.LEADING);
        label.setVerticalTextPosition(JLabel.TOP);
        label.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }
}
