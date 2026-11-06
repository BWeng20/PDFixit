package com.bw.pdfixit.ui.invoice;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.Preferences;
import com.bw.pdfixit.ui.HtmlLabel;
import com.bw.pdfixit.ui.wizard.WizardStep;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Step 1: what to do (a new invoice, add an invoice to an existing PDF, start from an XML file), the kind of document
 * and the profile with the hint which profiles are legal.
 */
final class GoalStep implements WizardStep {

    private final InvoiceWizardSession session;
    private final JPanel component = new JPanel(new BorderLayout(0, 10));
    private final JLabel pdfName = new JLabel(" ");
    private final JLabel xmlName = new JLabel(" ");
    private final JButton browsePdf = new JButton(I18n.t("wizard.goal.browse"));
    private final JButton browseXml = new JButton(I18n.t("wizard.goal.browse"));
    private boolean xmlLoaded;

    GoalStep(InvoiceWizardSession session) {
        this.session = session;
        JRadioButton create = new JRadioButton(I18n.t("wizard.goal.new"), true);
        JRadioButton add = new JRadioButton(I18n.t("wizard.goal.add"));
        JRadioButton xml = new JRadioButton(I18n.t("wizard.goal.xml"));
        ButtonGroup group = new ButtonGroup();
        group.add(create);
        group.add(add);
        group.add(xml);
        create.addActionListener(e -> select(InvoiceWizardSession.Mode.NEW));
        add.addActionListener(e -> select(InvoiceWizardSession.Mode.ADD));
        xml.addActionListener(e -> select(InvoiceWizardSession.Mode.XML));
        browsePdf.addActionListener(e -> choosePdf());
        browseXml.addActionListener(e -> chooseXml());
        select(InvoiceWizardSession.Mode.NEW);

        JPanel choices = new JPanel();
        choices.setLayout(new BoxLayout(choices, BoxLayout.Y_AXIS));
        choices.add(option(create, null, null, null));
        choices.add(option(add, I18n.t("wizard.goal.addHint"), pdfName, browsePdf));
        choices.add(option(xml, I18n.t("wizard.goal.xmlHint"), xmlName, browseXml));

        FormPanel kind = new FormPanel(true);
        kind.pairRow(I18n.t("editor.type"), session.fields.documentType(), I18n.t("editor.profile"),
                session.fields.profile(), false);
        HtmlLabel hint = new HtmlLabel(I18n.t("wizard.goal.profileHint"));
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        JPanel profile = new JPanel(new BorderLayout());
        profile.setBorder(BorderFactory.createTitledBorder(I18n.t("wizard.goal.profile")));
        profile.add(kind, BorderLayout.NORTH);
        JPanel hintPanel = new JPanel(new BorderLayout());
        hintPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
        hintPanel.add(hint, BorderLayout.CENTER);
        profile.add(hintPanel, BorderLayout.CENTER);

        component.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        component.add(choices, BorderLayout.NORTH);
        component.add(profile, BorderLayout.CENTER);
    }

    private static JComponent option(JRadioButton radio, String hint, JLabel file, JButton browse) {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
        panel.add(radio, BorderLayout.NORTH);
        if (hint != null) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 0));
            row.add(Box.createHorizontalStrut(18));
            JLabel text = new JLabel(hint);
            text.setForeground(UIManager.getColor("Label.disabledForeground"));
            row.add(text);
            row.add(browse);
            row.add(file);
            panel.add(row, BorderLayout.CENTER);
        }
        return panel;
    }

    private void select(InvoiceWizardSession.Mode mode) {
        session.mode = mode;
        browsePdf.setEnabled(mode == InvoiceWizardSession.Mode.ADD);
        browseXml.setEnabled(mode == InvoiceWizardSession.Mode.XML);
    }

    private void choosePdf() {
        JFileChooser chooser = new JFileChooser();
        String dir = Preferences.getInstance().getString(Preferences.USER_PREF_LAST_PDF_DIR, null);
        if (dir != null)
            chooser.setCurrentDirectory(new File(dir));
        chooser.setFileFilter(new FileNameExtensionFilter("PDF", "pdf"));
        chooser.setDialogTitle(I18n.t("wizard.goal.choosePdf"));
        if (chooser.showOpenDialog(component) != JFileChooser.APPROVE_OPTION)
            return;
        File file = chooser.getSelectedFile();
        session.existingPdf = file.toPath();
        pdfName.setText(file.getName());
        if (file.getParentFile() != null)
            Preferences.getInstance().set(Preferences.USER_PREF_LAST_PDF_DIR, file.getParentFile().getAbsolutePath());
    }

    private void chooseXml() {
        JFileChooser chooser = new JFileChooser();
        // The folder of the last XML file, before the first one the folder of the last PDF.
        String dir = Preferences.getInstance().getString(Preferences.USER_PREF_LAST_XML_DIR,
                Preferences.getInstance().getString(Preferences.USER_PREF_LAST_PDF_DIR, null));
        if (dir != null)
            chooser.setCurrentDirectory(new File(dir));
        chooser.setFileFilter(new FileNameExtensionFilter("XML", "xml"));
        chooser.setDialogTitle(I18n.t("editor.loadTitle"));
        if (chooser.showOpenDialog(component) != JFileChooser.APPROVE_OPTION)
            return;
        File file = chooser.getSelectedFile();
        if (file.getParentFile() != null)
            Preferences.getInstance().set(Preferences.USER_PREF_LAST_XML_DIR, file.getParentFile().getAbsolutePath());
        try {
            session.items.stopEditing();
            session.data.replaceXml(Files.readAllBytes(file.toPath()));
            session.fields.reload();
            session.items.reload();
            session.items.reloaded();
            // Another seller and buyer: the presets that fit them.
            session.fields.selectMatchingPresets();
            session.refresh();
            xmlLoaded = true;
            xmlName.setText(file.getName());
        } catch (Exception e) {
            Log.exception(e);
            JOptionPane.showMessageDialog(component, I18n.t("editor.noValidInvoice") + "\n" + e.getMessage(),
                    I18n.t("wizard.invoice.title"), JOptionPane.ERROR_MESSAGE);
        }
    }

    @Override
    public String title() {
        return I18n.t("wizard.goal");
    }

    @Override
    public JComponent component() {
        return component;
    }

    @Override
    public List<String> problems() {
        if (session.mode == InvoiceWizardSession.Mode.ADD && session.existingPdf == null)
            return List.of(I18n.t("wizard.goal.noPdf"));
        if (session.mode == InvoiceWizardSession.Mode.XML && !xmlLoaded)
            return List.of(I18n.t("wizard.goal.noXml"));
        return List.of();
    }

    @Override
    public void onEnter() {
    }
}
