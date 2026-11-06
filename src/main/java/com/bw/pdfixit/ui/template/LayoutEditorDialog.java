package com.bw.pdfixit.ui.template;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.PdfStandardProfile;
import com.bw.pdfixit.Preferences;
import com.bw.pdfixit.fonts.ImportedFont;
import com.bw.pdfixit.fonts.ImportedFonts;
import com.bw.pdfixit.fonts.PdfFontEmbedder;
import com.bw.pdfixit.template.Element;
import com.bw.pdfixit.template.InvoiceContent;
import com.bw.pdfixit.template.InvoiceRenderer;
import com.bw.pdfixit.template.Label;
import com.bw.pdfixit.template.Layout;
import com.bw.pdfixit.template.LayoutField;
import com.bw.pdfixit.template.LayoutFiles;
import com.bw.pdfixit.template.LayoutImage;
import com.bw.pdfixit.template.Placement;
import com.bw.pdfixit.template.Problem;
import com.bw.pdfixit.template.Rect;
import com.bw.pdfixit.template.Region;
import com.bw.pdfixit.template.SampleInvoice;
import com.bw.pdfixit.template.TemplateCheck;
import com.bw.pdfixit.template.TemplateEngine;
import com.bw.pdfixit.ui.AreaCanvas;
import com.bw.pdfixit.ui.HelpDialog;
import com.bw.pdfixit.ui.RoundSvgButton;
import com.bw.pdfixit.ui.ViewArea;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The layout editor: the user places the fields of the invoice on the pages of a template (a letterhead PDF) and saves
 * the layout beside it (see {@link LayoutFiles}). The template is never changed.<br>
 * The page shows the template with a live preview: the engine fills it with a made-up invoice
 * ({@link SampleInvoice}). Fixed elements and body regions are rectangles that can be moved and resized; labels and the
 * flowing parts are shown where the preview drew them and can be selected, moved (labels) and deleted.
 */
public final class LayoutEditorDialog extends JDialog {

    /**
     * Resolution of the page image.
     */
    private static final double DPI = 150;
    private static final int[] ZOOMS = {0, 75, 100, 125, 150, 200};
    /**
     * Items of the sample invoice: few show the first page with all parts, many give following pages.
     */
    private static final int ITEMS_FIRST_PAGE = 3;
    private static final int ITEMS_FOLLOWING_PAGES = 45;

    private final Path templatePath;
    private final byte[] template;
    private final int templatePages;
    private final Layout layout;
    private boolean dirty;
    private boolean saved;

    /**
     * False: page 1 is shown; true: the following pages.
     */
    private boolean next;
    private Locale language = Locale.GERMAN;

    private final LayoutCanvas canvas = new LayoutCanvas();
    private final JScrollPane canvasScroll = new JScrollPane(canvas);
    private final DefaultListModel<Entry> entries = new DefaultListModel<>();
    private final JList<Entry> list = new JList<>(entries);
    private final JPanel properties = new JPanel(new GridBagLayout());
    private final DefaultListModel<Problem> problems = new DefaultListModel<>();
    private final JList<Problem> problemList = new JList<>(problems);
    private final JCheckBox sample = new JCheckBox(I18n.t("layoutEditor.sample"), true);
    private final JSpinner sampleItems = new JSpinner(new SpinnerNumberModel(ITEMS_FIRST_PAGE, 1, 500, 1));
    private final JComboBox<String> font = new JComboBox<>();
    private final JComboBox<Object> continuation = new JComboBox<>();
    private final JToggleButton page1 = new JToggleButton(I18n.t("layoutEditor.page1"), true);
    private final JToggleButton following = new JToggleButton(I18n.t("layoutEditor.following"));
    private double pageWidth = 595;

    /**
     * What the canvas shows now, parallel to its areas.
     */
    private final List<Shown> shown = new ArrayList<>();
    /**
     * Where the last preview drew labels and values on the shown page; empty while a new preview is made.
     */
    private List<Placement> placements = List.of();
    private int generation;
    private final Timer previewTimer = new Timer(250, e -> renderPreview());
    private boolean updating;

    /**
     * An entry of the element list: a fixed element, a flowing part or a body region.
     *
     * @param element The element, null for a body region.
     * @param fixed   True for an element of {@link Layout#fields}.
     * @param next    For a body region: the one of the following pages.
     */
    private record Entry(Element element, boolean fixed, boolean next, String heading) {
        Entry(Element element, boolean fixed, boolean next) {
            this(element, fixed, next, null);
        }

        boolean isRegion() {
            return element == null && heading == null;
        }

        /**
         * A group title of the list, it can't be selected.
         */
        boolean isHeading() {
            return heading != null;
        }
    }

    /**
     * What an area of the canvas shows.
     */
    private record Shown(LayoutCanvas.Kind kind, Element element, Rectangle2D drawn) {
    }

    private LayoutEditorDialog(Window owner, Path templatePath, byte[] template, int templatePages,
                               LayoutFiles.Found found) {
        super(owner, I18n.t("layoutEditor.title", templatePath.getFileName()), ModalityType.APPLICATION_MODAL);
        this.templatePath = templatePath;
        this.template = template;
        this.templatePages = templatePages;
        this.layout = found.layout();
        previewTimer.setRepeats(false);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancel();
            }
        });

        canvas.setAreaListener(new AreaCanvas.AreaListener() {
            @Override
            public void added(ViewArea area) {
                // New regions come from the list (a placement or "Add"); drawing on the page adds nothing.
                rebuildAreas();
            }

            @Override
            public void changed(int index, ViewArea area) {
                areaChanged(index, area.rect());
            }

            @Override
            public void removed(int index) {
                areaRemoved(index);
            }

            @Override
            public void selectionChanged() {
                if (updating)
                    return;
                int i = canvas.getSelected();
                if (i >= 0 && i < shown.size())
                    selectEntry(shown.get(i));
            }
        });
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean hasFocus) {
                Entry e = (Entry) value;
                if (e.isHeading()) {
                    JLabel title = (JLabel) super.getListCellRendererComponent(l, e.heading(), index, false, false);
                    title.setFont(title.getFont().deriveFont(Font.BOLD));
                    title.setForeground(UIManager.getColor("Label.disabledForeground"));
                    title.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createEmptyBorder(index == 0 ? 0 : 6, 0, 0, 0),
                            BorderFactory.createMatteBorder(0, 0, 1, 0, title.getForeground())));
                    return title;
                }
                JLabel row = (JLabel) super.getListCellRendererComponent(l, entryName(e), index, isSelected, hasFocus);
                row.setBorder(BorderFactory.createEmptyBorder(1, 10, 1, 2));
                return row;
            }
        });
        // Group titles are skipped by the selection.
        list.setSelectionModel(new DefaultListSelectionModel() {
            @Override
            public void setSelectionInterval(int index0, int index1) {
                if (index0 >= 0 && index0 < entries.size() && !entries.get(index0).isHeading())
                    super.setSelectionInterval(index0, index1);
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !updating)
                entrySelected();
        });
        problemList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean hasFocus) {
                Problem p = (Problem) value;
                JLabel label = (JLabel) super.getListCellRendererComponent(l, p.message(), index, isSelected, hasFocus);
                Icon icon = UIManager.getIcon(switch (p.severity()) {
                    case ERROR -> "OptionPane.errorIcon";
                    case WARNING -> "OptionPane.warningIcon";
                    case INFO -> "OptionPane.informationIcon";
                });
                label.setIcon(icon == null ? null : small(icon));
                return label;
            }
        });
        problemList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Problem p = problemList.getSelectedValue();
                if (p != null && p.element() != null)
                    selectByName(p.element());
            }
        });

        setContentPane(createContent());
        fillSettings();
        rebuildList();
        rebuildAreas();
        renderPreview();
        setSize(1200, 860);
        setLocationRelativeTo(owner);
    }

    /**
     * Opens the editor for a template. Errors of the template (e.g. a password) are shown, the editor is not opened
     * then.
     *
     * @return True if the layout was saved.
     */
    public static boolean edit(Window owner, Path templatePath) {
        byte[] template;
        int pages;
        LayoutFiles.Found found;
        try {
            template = Files.readAllBytes(templatePath);
            try (PDDocument doc = Loader.loadPDF(template)) {
                pages = doc.getNumberOfPages();
            }
            found = LayoutFiles.find(templatePath);
        } catch (Exception e) {
            Log.exception(e);
            String why = e instanceof org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
                    ? I18n.t("template.check.encrypted") : e.getMessage();
            JOptionPane.showMessageDialog(owner, I18n.t("layoutEditor.openFailed", why), I18n.t("layoutEditor.name"),
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (pages == 0) {
            JOptionPane.showMessageDialog(owner, I18n.t("template.check.noPages"), I18n.t("layoutEditor.name"),
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
        Preferences.getInstance().addRecentTemplate(templatePath.toAbsolutePath().toString());
        LayoutEditorDialog dialog = new LayoutEditorDialog(owner, templatePath, template, pages, found);
        dialog.setVisible(true);
        return dialog.saved;
    }

    /**
     * Lets the user choose a template PDF, then opens the editor.
     */
    public static void chooseAndEdit(Window owner) {
        JFileChooser chooser = new JFileChooser();
        String dir = Preferences.getInstance().getString(Preferences.USER_PREF_LAST_TEMPLATE_DIR, null);
        if (dir != null)
            chooser.setCurrentDirectory(new File(dir));
        chooser.setFileFilter(new FileNameExtensionFilter("PDF", "pdf"));
        chooser.setDialogTitle(I18n.t("layoutEditor.chooseTemplate"));
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION)
            return;
        File file = chooser.getSelectedFile();
        if (file.getParentFile() != null)
            Preferences.getInstance().set(Preferences.USER_PREF_LAST_TEMPLATE_DIR, file.getParentFile().getAbsolutePath());
        edit(owner, file.toPath());
    }

    // ---------------------------------------------------------------------------------------------- content

    private JComponent createContent() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Left: the elements, their properties, the settings of the layout.
        JPanel left = new JPanel(new BorderLayout(0, 8));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(18);
        JPanel listPanel = new JPanel(new BorderLayout(0, 4));
        listPanel.add(new JLabel(I18n.t("layoutEditor.elements")), BorderLayout.NORTH);
        listPanel.add(new JScrollPane(list), BorderLayout.CENTER);
        JButton add = new JButton(I18n.t("layoutEditor.add"));
        add.addActionListener(e -> addMenu().show(add, 0, add.getHeight()));
        JButton remove = new JButton(I18n.t("redact.remove"));
        remove.addActionListener(e -> removeSelected());
        JButton up = new JButton("▲");
        up.setToolTipText(I18n.t("layoutEditor.up"));
        up.addActionListener(e -> movePart(-1));
        JButton down = new JButton("▼");
        down.setToolTipText(I18n.t("layoutEditor.down"));
        down.addActionListener(e -> movePart(1));
        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        listButtons.add(add);
        listButtons.add(remove);
        listButtons.add(up);
        listButtons.add(down);
        listPanel.add(listButtons, BorderLayout.SOUTH);
        left.add(listPanel, BorderLayout.NORTH);

        properties.setBorder(BorderFactory.createTitledBorder(I18n.t("layoutEditor.properties")));
        JScrollPane propertiesScroll = new JScrollPane(properties, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        propertiesScroll.setBorder(null);
        left.add(propertiesScroll, BorderLayout.CENTER);

        JPanel settings = new JPanel(new GridBagLayout());
        settings.setBorder(BorderFactory.createTitledBorder(I18n.t("layoutEditor.settings")));
        GridBagConstraints gc = constraints();
        addRow(settings, gc, I18n.t("layoutEditor.font"), font);
        addRow(settings, gc, I18n.t("layoutEditor.continuation"), continuation);
        left.add(settings, BorderLayout.SOUTH);
        left.setPreferredSize(new Dimension(360, 100));
        root.add(left, BorderLayout.WEST);

        // Center: the page.
        JPanel center = new JPanel(new BorderLayout(0, 4));
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        ButtonGroup pages = new ButtonGroup();
        pages.add(page1);
        pages.add(following);
        page1.addActionListener(e -> showPage(false));
        following.addActionListener(e -> showPage(true));
        bar.add(page1);
        bar.add(following);
        bar.addSeparator();
        JComboBox<Integer> zoom = new JComboBox<>(java.util.Arrays.stream(ZOOMS).boxed().toArray(Integer[]::new));
        zoom.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean hasFocus) {
                int z = (Integer) value;
                return super.getListCellRendererComponent(l, z == 0 ? I18n.t("layoutEditor.fit") : z + " %", index,
                        isSelected, hasFocus);
            }
        });
        zoom.setMaximumSize(zoom.getPreferredSize());
        zoom.addActionListener(e -> {
            int z = (Integer) zoom.getSelectedItem();
            canvas.setDisplayWidth(z == 0 || canvas.getPixelsPerPoint() == 0 ? 0
                    : (int) Math.round(pageWidthPoints() * z / 100.0));
        });
        bar.add(new JLabel(I18n.t("layoutEditor.zoom") + " "));
        bar.add(zoom);
        bar.addSeparator();
        sample.setToolTipText(I18n.t("layoutEditor.sample.tip"));
        sample.addActionListener(e -> {
            sampleItems.setEnabled(sample.isSelected());
            renderPreview();
        });
        bar.add(sample);
        sampleItems.setToolTipText(I18n.t("layoutEditor.sampleItems.tip", ITEMS_FOLLOWING_PAGES));
        sampleItems.addChangeListener(e -> previewTimer.restart());
        bar.add(new JLabel(" " + I18n.t("layoutEditor.sampleItems") + " "));
        bar.add(sampleItems);
        bar.addSeparator();
        JComboBox<Locale> languages = new JComboBox<>(new Locale[]{Locale.GERMAN, Locale.ENGLISH});
        languages.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean hasFocus) {
                return super.getListCellRendererComponent(l, ((Locale) value).getDisplayLanguage(I18n.getLocale()),
                        index, isSelected, hasFocus);
            }
        });
        languages.setToolTipText(I18n.t("layoutEditor.language.tip"));
        languages.setMaximumSize(languages.getPreferredSize());
        languages.addActionListener(e -> {
            language = (Locale) languages.getSelectedItem();
            renderPreview();
        });
        bar.add(languages);
        bar.addSeparator();
        JButton reset = new JButton(I18n.t("layoutEditor.reset"));
        reset.addActionListener(e -> {
            JPopupMenu menu = new JPopupMenu();
            for (Layout.Form form : Layout.Form.values()) {
                JMenuItem item = new JMenuItem(I18n.t("layoutEditor.reset." + form.name()));
                item.addActionListener(a -> resetTo(form));
                menu.add(item);
            }
            menu.show(reset, 0, reset.getHeight());
        });
        bar.add(reset);
        bar.add(Box.createHorizontalGlue());
        center.add(bar, BorderLayout.NORTH);
        canvasScroll.getVerticalScrollBar().setUnitIncrement(24);
        center.add(canvasScroll, BorderLayout.CENTER);
        JLabel hint = new JLabel(I18n.t("layoutEditor.hint"));
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        center.add(hint, BorderLayout.SOUTH);
        root.add(center, BorderLayout.CENTER);

        // Bottom: the warnings and the buttons.
        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        problemList.setVisibleRowCount(4);
        problemList.setToolTipText(I18n.t("layoutEditor.problems.tip"));
        bottom.add(new JScrollPane(problemList), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        JButton save = new JButton(I18n.t("layoutEditor.save"));
        save.addActionListener(e -> save());
        JButton cancel = new JButton(I18n.t("common.cancel"));
        cancel.addActionListener(e -> cancel());
        // Help button like in the redaction dialog.
        RoundSvgButton help = RoundSvgButton.createHelp();
        help.setToolTipText(I18n.t("common.help"));
        help.addActionListener(e -> HelpDialog.show(this, "help.layoutEditor.text", true));
        buttons.add(help);
        buttons.add(save);
        buttons.add(cancel);
        bottom.add(buttons, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(save);
        return root;
    }

    private static GridBagConstraints constraints() {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = 0;
        gc.insets = new Insets(2, 4, 2, 4);
        gc.anchor = GridBagConstraints.WEST;
        return gc;
    }

    private static void addRow(JPanel panel, GridBagConstraints gc, String caption, JComponent field) {
        gc.gridx = 0;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        gc.gridwidth = 1;
        if (caption != null)
            panel.add(new JLabel(caption), gc);
        gc.gridx = caption != null ? 1 : 0;
        gc.gridwidth = caption != null ? 1 : 2;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, gc);
        gc.gridy++;
    }

    private static Icon small(Icon icon) {
        int h = 16;
        if (icon.getIconHeight() <= h)
            return icon;
        BufferedImage img = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        icon.paintIcon(null, g, 0, 0);
        g.dispose();
        return new ImageIcon(img.getScaledInstance(-1, h, Image.SCALE_SMOOTH));
    }

    // ---------------------------------------------------------------------------------------------- settings

    private void fillSettings() {
        updating = true;
        try {
            font.removeAllItems();
            for (PdfFontEmbedder.Family f : PdfFontEmbedder.Family.values())
                font.addItem(new PdfFontEmbedder.Variant(f, false, false).displayName());
            for (ImportedFont f : ImportedFonts.fonts())
                font.addItem(f.getName());
            boolean known = false;
            for (int i = 0; i < font.getItemCount(); ++i)
                known |= font.getItemAt(i).equalsIgnoreCase(layout.fontName);
            if (!known)
                font.addItem(layout.fontName);
            font.setSelectedItem(layout.fontName);

            continuation.removeAllItems();
            // Without a choice a template with two pages uses its page 2, a one-page template asks when needed.
            continuation.addItem(I18n.t(templatePages >= 2 ? "layoutEditor.continuation.default"
                    : "layoutEditor.continuation.none"));
            for (Layout.Continuation c : Layout.Continuation.values())
                continuation.addItem(c);
            continuation.setSelectedItem(layout.continuation == null ? continuation.getItemAt(0) : layout.continuation);
        } finally {
            updating = false;
        }
        continuation.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean hasFocus) {
                String text = value instanceof Layout.Continuation c ? I18n.t("layoutEditor.continuation." + c.json)
                        + (c == Layout.Continuation.PDF && layout.continuationFile != null
                        ? " (" + layout.continuationFile.getFileName() + ")" : "") : String.valueOf(value);
                return super.getListCellRendererComponent(l, text, index, isSelected, hasFocus);
            }
        });
        font.addActionListener(e -> {
            if (updating || font.getSelectedItem() == null)
                return;
            layout.fontName = (String) font.getSelectedItem();
            changed();
        });
        continuation.addActionListener(e -> {
            if (updating)
                return;
            Object v = continuation.getSelectedItem();
            if (v == Layout.Continuation.PDF) {
                JFileChooser chooser = new JFileChooser(templatePath.toAbsolutePath().getParent().toFile());
                chooser.setFileFilter(new FileNameExtensionFilter("PDF", "pdf"));
                chooser.setDialogTitle(I18n.t("layoutEditor.continuation.choose"));
                if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
                    updating = true;
                    continuation.setSelectedItem(layout.continuation == null ? continuation.getItemAt(0) : layout.continuation);
                    updating = false;
                    return;
                }
                layout.continuationFile = chooser.getSelectedFile().toPath().toAbsolutePath();
            }
            layout.continuation = v instanceof Layout.Continuation c ? c : null;
            changed();
        });
    }

    private void resetTo(Layout.Form form) {
        if (JOptionPane.showConfirmDialog(this, I18n.t("layoutEditor.reset.question"), I18n.t("layoutEditor.name"),
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
            return;
        Layout din = Layout.din5008(form);
        layout.fields.clear();
        layout.fields.addAll(din.fields);
        layout.regions.clear();
        layout.regions.addAll(din.regions);
        layout.parts.clear();
        layout.parts.addAll(din.parts);
        layout.columns.clear();
        layout.columns.addAll(din.columns);
        layout.tableHeader = din.tableHeader;
        changed();
    }

    // ---------------------------------------------------------------------------------------------- names

    /**
     * A readable name of a field, e.g. "Verkäufer: Straße".
     */
    static String fieldName(String id) {
        String label = I18n.t("print.label." + id);
        if (label.startsWith("["))
            return id;
        if ((id.startsWith("seller.") || id.startsWith("buyer.")) && !id.endsWith(".name") && !id.endsWith(".address")
                && !id.equals("seller.iban") && !id.equals("seller.bic") && !id.equals("seller.accountHolder"))
            return I18n.t("layoutEditor.group." + id.substring(0, id.indexOf('.'))) + ": " + label;
        return label;
    }

    private String elementName(Element e) {
        return switch (e.type) {
            case FIELD -> fieldName(e.id);
            case TEXT -> {
                String t = e.text(language.getLanguage()).replace('\n', ' ');
                yield I18n.t("layoutEditor.text") + (t.isBlank() ? "" : ": " + (t.length() > 24 ? t.substring(0, 24) + "…" : t));
            }
            case IMAGE -> I18n.t("layoutEditor.image");
        };
    }

    private String entryName(Entry e) {
        if (e.isRegion())
            return I18n.t(e.next() ? "layoutEditor.body.following" : "layoutEditor.body.page1");
        return elementName(e.element());
    }

    // ---------------------------------------------------------------------------------------------- list

    private void rebuildList() {
        Entry selected = list.getSelectedValue();
        updating = true;
        try {
            entries.clear();
            if (!layout.regions.isEmpty())
                entries.addElement(heading("layoutEditor.list.areas"));
            for (Region r : layout.regions)
                entries.addElement(new Entry(null, false, r.next()));
            for (boolean n : new boolean[]{false, true}) {
                boolean any = false;
                for (Element e : layout.fields)
                    if (e.next == n) {
                        if (!any)
                            entries.addElement(heading(n ? "layoutEditor.list.fixedNext" : "layoutEditor.list.fixed1"));
                        any = true;
                        entries.addElement(new Entry(e, true, n));
                    }
            }
            if (!layout.parts.isEmpty())
                entries.addElement(heading("layoutEditor.list.flow"));
            for (Element e : layout.parts)
                entries.addElement(new Entry(e, false, false));
            if (selected != null)
                for (int i = 0; i < entries.size(); ++i)
                    if (sameEntry(entries.get(i), selected))
                        list.setSelectedIndex(i);
        } finally {
            updating = false;
        }
        rebuildProperties();
    }

    private static Entry heading(String key) {
        return new Entry(null, false, false, I18n.t(key));
    }

    private static boolean sameEntry(Entry a, Entry b) {
        if (a.isHeading() || b.isHeading())
            return false;
        return a.isRegion() ? b.isRegion() && a.next() == b.next() : a.element() == b.element();
    }

    private void entrySelected() {
        rebuildProperties();
        Entry e = list.getSelectedValue();
        if (e == null)
            return;
        // Show the page of the element.
        boolean page = e.isRegion() ? e.next() : e.fixed() && e.element().next;
        if ((e.isRegion() || e.fixed()) && page != next)
            showPage(page);
        updating = true;
        try {
            for (int i = 0; i < shown.size(); ++i) {
                Shown s = shown.get(i);
                boolean match = e.isRegion() ? s.kind() == LayoutCanvas.Kind.BODY && s.element() == null
                        : s.element() == e.element() && s.kind() != LayoutCanvas.Kind.LABEL_PLACED
                        && s.kind() != LayoutCanvas.Kind.FLOW_LABEL && s.kind() != LayoutCanvas.Kind.LABEL_RECT;
                if (match) {
                    canvas.select(i);
                    return;
                }
            }
            canvas.select(-1);
        } finally {
            updating = false;
        }
    }

    private void selectEntry(Shown s) {
        for (int i = 0; i < entries.size(); ++i) {
            Entry e = entries.get(i);
            if (s.element() == null ? e.isRegion() && e.next() == next : e.element() == s.element()) {
                updating = true;
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
                updating = false;
                rebuildProperties();
                return;
            }
        }
    }

    private void selectByName(String name) {
        for (int i = 0; i < entries.size(); ++i) {
            Entry e = entries.get(i);
            if (!e.isRegion() && !e.isHeading() && name.equals(e.element().name())) {
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
                return;
            }
        }
    }

    /**
     * The menu of the elements that can be added: the fields not used on the shown page or in the body, grouped,
     * a text and a picture.
     */
    private JPopupMenu addMenu() {
        JPopupMenu menu = new JPopupMenu();
        String[][] groups = {
                {"invoice", "number", "issueDate", "deliveryDate", "periodStart", "periodEnd", "buyerReference", "note",
                        "vatExemptionReason", "lines", "totals", "page"},
                {"payment", "dueDate", "paymentTerms", "seller.accountHolder", "seller.iban", "seller.bic"},
                {"seller", "seller.address", "seller.name", "seller.street", "seller.zip", "seller.city", "seller.country",
                        "seller.vatId", "seller.taxId", "seller.email", "seller.contactName", "seller.phone"},
                {"buyer", "buyer.address", "buyer.name", "buyer.street", "buyer.zip", "buyer.city", "buyer.country",
                        "buyer.vatId", "buyer.taxId", "buyer.email"}};
        for (String[] group : groups) {
            JMenu sub = new JMenu(I18n.t("layoutEditor.group." + group[0]));
            for (int i = 1; i < group.length; ++i) {
                String id = group[i];
                if (isUsedHere(id))
                    continue;
                JMenuItem item = new JMenuItem(fieldName(id));
                item.addActionListener(e -> addField(id));
                sub.add(item);
            }
            sub.setEnabled(sub.getItemCount() > 0);
            menu.add(sub);
        }
        menu.addSeparator();
        JMenuItem text = new JMenuItem(I18n.t("layoutEditor.addText"));
        text.addActionListener(e -> addText());
        menu.add(text);
        JMenuItem image = new JMenuItem(I18n.t("layoutEditor.addImage"));
        image.addActionListener(e -> addImage());
        menu.add(image);
        if (layout.region(next) == null) {
            menu.addSeparator();
            JMenuItem body = new JMenuItem(I18n.t(next ? "layoutEditor.body.following" : "layoutEditor.body.page1"));
            body.addActionListener(e -> {
                layout.regions.add(new Region(next, next ? new Rect(25, 35, 165, 230) : new Rect(25, 100, 165, 165)));
                changed();
            });
            menu.add(body);
        }
        return menu;
    }

    /**
     * True if the field is in the body or fixed on the shown page.
     */
    private boolean isUsedHere(String id) {
        for (Element e : layout.parts)
            if (id.equals(e.id))
                return true;
        for (Element e : layout.fields)
            if (id.equals(e.id) && e.next == next)
                return true;
        return false;
    }

    /**
     * Adds a field: into the body if it may flow and isn't fixed anywhere, otherwise fixed on the shown page.
     */
    private void addField(String id) {
        LayoutField f = LayoutField.of(id);
        boolean fixedElsewhere = layout.fields.stream().anyMatch(e -> id.equals(e.id));
        Element e = Element.field(id);
        if (f.placement == LayoutField.Placement.FLOWING_ONLY
                || (f.placement == LayoutField.Placement.ANY && !fixedElsewhere)) {
            layout.parts.add(insertIndex(), e);
            select(new Entry(e, false, false));
        } else {
            e.next = next;
            e.rect = defaultRect(f.kind == LayoutField.Kind.BLOCK ? 20 : 5);
            layout.fields.add(e);
            select(new Entry(e, true, next));
        }
        changed();
    }

    /**
     * New parts go before the closing text and signature at the end, if there are any.
     */
    private int insertIndex() {
        int i = layout.parts.size();
        while (i > 0 && layout.parts.get(i - 1).type != Element.Type.FIELD)
            --i;
        return i;
    }

    private Rect defaultRect(double height) {
        return new Rect(25, next ? 20 : 30, 70, height);
    }

    private void addText() {
        Element e = new Element();
        e.type = Element.Type.TEXT;
        e.text.put("de", "Mit freundlichen Grüßen");
        e.text.put("en", "Kind regards");
        layout.parts.add(e);
        select(new Entry(e, false, false));
        changed();
    }

    private void addImage() {
        LayoutImage image = chooseImage();
        if (image == null)
            return;
        String name = "image1";
        for (int n = 1; layout.images.containsKey(name); ++n)
            name = "image" + n;
        try {
            layout.putImage(name, image);
        } catch (Layout.LayoutException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), I18n.t("layoutEditor.name"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        Element e = new Element();
        e.type = Element.Type.IMAGE;
        e.image = name;
        e.width = 40.0;
        layout.parts.add(e);
        select(new Entry(e, false, false));
        changed();
    }

    /**
     * Lets the user choose a PNG or JPEG. Tells that it is stored in the layout file.
     *
     * @return The picture, null if none was chosen or it can't be used (reported).
     */
    private LayoutImage chooseImage() {
        JFileChooser chooser = new JFileChooser(templatePath.toAbsolutePath().getParent().toFile());
        chooser.setFileFilter(new FileNameExtensionFilter(I18n.t("layoutEditor.imageFiles"), "png", "jpg", "jpeg"));
        chooser.setDialogTitle(I18n.t("layoutEditor.addImage"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
            return null;
        Path file = chooser.getSelectedFile().toPath();
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String format = name.endsWith(".png") ? LayoutImage.PNG : LayoutImage.JPEG;
        try {
            byte[] data = Files.readAllBytes(file);
            if (data.length > Layout.MAX_IMAGE_BYTES) {
                JOptionPane.showMessageDialog(this, I18n.t("layout.imageTooLarge", file.getFileName(),
                        Layout.MAX_IMAGE_BYTES / (1024 * 1024)), I18n.t("layoutEditor.name"), JOptionPane.ERROR_MESSAGE);
                return null;
            }
            JOptionPane.showMessageDialog(this, I18n.t("layoutEditor.imageStored"), I18n.t("layoutEditor.name"),
                    JOptionPane.INFORMATION_MESSAGE);
            return new LayoutImage(format, data);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), I18n.t("layoutEditor.name"), JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private void removeSelected() {
        Entry e = list.getSelectedValue();
        if (e == null)
            return;
        if (e.isRegion())
            layout.regions.removeIf(r -> r.next() == e.next());
        else if (e.fixed())
            layout.fields.remove(e.element());
        else
            layout.parts.remove(e.element());
        changed();
    }

    /**
     * Moves the selected part of the body up or down.
     */
    private void movePart(int direction) {
        Entry e = list.getSelectedValue();
        if (e == null || e.isRegion() || e.fixed())
            return;
        int i = layout.parts.indexOf(e.element());
        int j = i + direction;
        if (i < 0 || j < 0 || j >= layout.parts.size())
            return;
        layout.parts.set(i, layout.parts.get(j));
        layout.parts.set(j, e.element());
        changed();
    }

    private void select(Entry entry) {
        rebuildList();
        for (int i = 0; i < entries.size(); ++i)
            if (sameEntry(entries.get(i), entry)) {
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
            }
    }

    // ---------------------------------------------------------------------------------------------- properties

    private void rebuildProperties() {
        properties.removeAll();
        GridBagConstraints gc = constraints();
        Entry entry = list.getSelectedValue();
        if (entry == null) {
            addRow(properties, gc, null, note(I18n.t("layoutEditor.noSelection")));
        } else if (entry.isRegion()) {
            addRow(properties, gc, null, note(I18n.t("layoutEditor.body.tip")));
            if (!entry.next() && layout.region(true) == null)
                addRow(properties, gc, null, note(I18n.t("layoutEditor.body.derived")));
        } else {
            elementProperties(entry, gc);
        }
        gc.weighty = 1;
        addRow(properties, gc, null, new JPanel());
        properties.revalidate();
        properties.repaint();
    }

    private static JComponent note(String text) {
        JLabel l = new JLabel("<html>" + text + "</html>");
        l.setForeground(UIManager.getColor("Label.disabledForeground"));
        return l;
    }

    private void elementProperties(Entry entry, GridBagConstraints gc) {
        Element e = entry.element();
        LayoutField field = e.field();

        // Where: page 1, following pages, body.
        JComboBox<String> where = new JComboBox<>(new String[]{I18n.t("layoutEditor.place.page1"),
                I18n.t("layoutEditor.place.following"), I18n.t("layoutEditor.place.body")});
        where.setSelectedIndex(entry.fixed() ? (e.next ? 1 : 0) : 2);
        boolean fixedOnly = field != null && field.placement == LayoutField.Placement.FIXED_ONLY;
        boolean flowingOnly = field != null && field.placement == LayoutField.Placement.FLOWING_ONLY;
        where.setEnabled(!flowingOnly);
        where.addActionListener(a -> {
            int w = where.getSelectedIndex();
            if (w == 2 && fixedOnly) {
                where.setSelectedIndex(entry.fixed() && e.next ? 1 : 0);
                return;
            }
            moveElement(entry, w);
        });
        addRow(properties, gc, I18n.t("layoutEditor.place"), where);

        if (e.type == Element.Type.TEXT) {
            for (String lang : new String[]{"de", "en"}) {
                JTextArea area = new JTextArea(e.text.getOrDefault(lang, ""), 2, 18);
                area.setLineWrap(true);
                area.setWrapStyleWord(true);
                onChange(area, () -> {
                    if (area.getText().isEmpty())
                        e.text.remove(lang);
                    else
                        e.text.put(lang, area.getText());
                    changedSoft();
                });
                addRow(properties, gc, I18n.t("layoutEditor.text." + lang), new JScrollPane(area));
            }
        }
        if (e.type == Element.Type.IMAGE) {
            if (!entry.fixed()) {
                JSpinner width = new JSpinner(new SpinnerNumberModel(e.width == null ? 40.0 : e.width, 5.0, 200.0, 1.0));
                width.addChangeListener(c -> {
                    e.width = ((Number) width.getValue()).doubleValue();
                    changedSoft();
                });
                addRow(properties, gc, I18n.t("layoutEditor.width"), width);
            }
            JButton replace = new JButton(I18n.t("layoutEditor.replaceImage"));
            replace.addActionListener(a -> {
                LayoutImage image = chooseImage();
                if (image == null)
                    return;
                try {
                    layout.putImage(e.image, image);
                    changed();
                } catch (Layout.LayoutException ex) {
                    JOptionPane.showMessageDialog(this, ex.getMessage(), I18n.t("layoutEditor.name"), JOptionPane.ERROR_MESSAGE);
                }
            });
            addRow(properties, gc, null, replace);
        }
        if (e.type != Element.Type.IMAGE) {
            JSpinner size = new JSpinner(new SpinnerNumberModel((double) (e.size != null ? e.size : layout.fontSize),
                    5.0, 36.0, 0.5));
            size.addChangeListener(c -> {
                float v = ((Number) size.getValue()).floatValue();
                e.size = v == layout.fontSize ? null : v;
                changedSoft();
            });
            addRow(properties, gc, I18n.t("layoutEditor.size"), size);
            JCheckBox bold = new JCheckBox(I18n.t("layoutEditor.bold"), e.bold);
            bold.addActionListener(a -> {
                e.bold = bold.isSelected();
                changedSoft();
            });
            addRow(properties, gc, null, bold);
        }
        JComboBox<Element.Align> align = new JComboBox<>(Element.Align.values());
        align.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean hasFocus) {
                return super.getListCellRendererComponent(l, I18n.t("layoutEditor.align." + value), index, isSelected, hasFocus);
            }
        });
        align.setSelectedItem(e.align == null ? Element.Align.LEFT : e.align);
        align.addActionListener(a -> {
            Element.Align v = (Element.Align) align.getSelectedItem();
            e.align = v == Element.Align.LEFT ? null : v;
            changedSoft();
        });
        addRow(properties, gc, I18n.t("layoutEditor.align"), align);
        if (field == LayoutField.SELLER_ADDRESS || field == LayoutField.BUYER_ADDRESS) {
            JCheckBox single = new JCheckBox(I18n.t("layoutEditor.singleLine"), e.singleLine);
            single.addActionListener(a -> {
                e.singleLine = single.isSelected();
                changedSoft();
            });
            addRow(properties, gc, null, single);
        }
        if (field == LayoutField.LINES) {
            JCheckBox header = new JCheckBox(I18n.t("layoutEditor.tableHeader"), layout.tableHeader);
            header.addActionListener(a -> {
                layout.tableHeader = header.isSelected();
                changedSoft();
            });
            addRow(properties, gc, null, header);
        }
        if (e.type == Element.Type.FIELD && field != null)
            labelProperties(entry, field, gc);
        JButton remove = new JButton(I18n.t("layoutEditor.removeElement"));
        remove.addActionListener(a -> removeSelected());
        addRow(properties, gc, null, remove);
    }

    private void labelProperties(Entry entry, LayoutField field, GridBagConstraints gc) {
        Element e = entry.element();
        JLabel caption = new JLabel(I18n.t("layoutEditor.label"));
        caption.setFont(caption.getFont().deriveFont(Font.BOLD));
        gc.gridx = 0;
        gc.gridwidth = 2;
        gc.insets = new Insets(10, 4, 2, 4);
        properties.add(caption, gc);
        gc.insets = new Insets(2, 4, 2, 4);
        gc.gridy++;
        gc.gridwidth = 1;

        JCheckBox shown = new JCheckBox(I18n.t("layoutEditor.label.shown"), e.isLabelShown());
        shown.setToolTipText(I18n.t("layoutEditor.label.shown.tip"));
        shown.addActionListener(a -> {
            // Added again: with the default text at the default place.
            e.label = new Label(shown.isSelected());
            changed();
        });
        addRow(properties, gc, null, shown);
        if (!e.isLabelShown())
            return;
        JTextField text = new JTextField(e.label != null && e.label.text != null ? e.label.text : "", 14);
        if (e.type == Element.Type.FIELD)
            text.putClientProperty("JTextField.placeholderText",
                    com.bw.pdfixit.template.Overlay.defaultLabel(e.id, language));
        text.setToolTipText(I18n.t("layoutEditor.label.text.tip", com.bw.pdfixit.template.Overlay.defaultLabel(e.id, language)));
        onChange(text, () -> {
            label(e).text = text.getText().isBlank() ? null : text.getText();
            changedSoft();
        });
        addRow(properties, gc, I18n.t("layoutEditor.label.text"), text);
        if (entry.fixed()) {
            JCheckBox own = new JCheckBox(I18n.t("layoutEditor.label.own"), e.label != null && e.label.rect != null);
            own.setToolTipText(I18n.t("layoutEditor.label.own.tip"));
            own.addActionListener(a -> {
                Label l = label(e);
                if (own.isSelected()) {
                    Rect r = e.rect;
                    double w = Math.min(35, Math.max(10, r.x() - 2));
                    l.rect = new Rect(Math.max(0, r.x() - w - 1), r.y(), w, Math.max(r.height(), 5));
                } else {
                    l.rect = null;
                }
                changed();
            });
            addRow(properties, gc, null, own);
        } else if (field.kind == LayoutField.Kind.LINE || e.singleLine) {
            JComboBox<Label.Position> position = new JComboBox<>(Label.Position.values());
            position.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean hasFocus) {
                    return super.getListCellRendererComponent(l, I18n.t("layoutEditor.label.position." + value), index,
                            isSelected, hasFocus);
                }
            });
            position.setSelectedItem(e.label == null || e.label.position == null ? Label.Position.LEFT : e.label.position);
            position.addActionListener(a -> {
                Label.Position p = (Label.Position) position.getSelectedItem();
                label(e).position = p == Label.Position.LEFT ? null : p;
                changedSoft();
            });
            addRow(properties, gc, I18n.t("layoutEditor.label.position"), position);
        }
        if (!entry.fixed()) {
            JSpinner dx = new JSpinner(new SpinnerNumberModel(e.label == null ? 0.0 : e.label.dx, -50.0, 150.0, 0.5));
            dx.setToolTipText(I18n.t("layoutEditor.label.dx.tip"));
            dx.addChangeListener(c -> {
                label(e).dx = ((Number) dx.getValue()).doubleValue();
                changedSoft();
            });
            addRow(properties, gc, I18n.t("layoutEditor.label.dx"), dx);
        }
    }

    /**
     * The label of a field, created with the defaults if the layout has none.
     */
    private static Label label(Element e) {
        if (e.label == null)
            e.label = new Label(e.isLabelShown());
        return e.label;
    }

    /**
     * Moves an element to page 1 (0), the following pages (1) or the body (2).
     */
    private void moveElement(Entry entry, int where) {
        Element e = entry.element();
        if (entry.fixed())
            layout.fields.remove(e);
        else
            layout.parts.remove(e);
        if (where == 2) {
            e.rect = null;
            e.next = false;
            if (e.label != null)
                e.label.rect = null;
            layout.parts.add(insertIndex(), e);
        } else {
            boolean wasNext = next;
            next = where == 1;
            e.next = next;
            if (e.rect == null)
                e.rect = defaultRect(e.field() != null && e.field().kind == LayoutField.Kind.BLOCK ? 20 : 8);
            next = wasNext;
            layout.fields.add(e);
        }
        select(new Entry(e, where != 2, where == 1));
        changed();
    }

    private static void onChange(javax.swing.text.JTextComponent field, Runnable r) {
        field.getDocument().addDocumentListener(new DocumentListener() {
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

    // ---------------------------------------------------------------------------------------------- page

    private void showPage(boolean following) {
        if (next == following)
            return;
        next = following;
        // Also when the page is changed by selecting an element of the other page.
        (following ? this.following : page1).setSelected(true);
        placements = List.of();
        rebuildAreas();
        renderPreview();
    }

    /**
     * The width of the shown page in points (A4 until the first image is there).
     */
    private double pageWidthPoints() {
        return pageWidth;
    }

    /**
     * Shows the regions of the layout on the shown page, and the labels and flowing parts the last preview drew.
     */
    private void rebuildAreas() {
        shown.clear();
        List<ViewArea> areas = new ArrayList<>();
        List<LayoutCanvas.Kind> kinds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Region body = layout.region(next);
        if (body != null)
            add(areas, kinds, names, new Shown(LayoutCanvas.Kind.BODY, null, null), body.rect().toPoints(),
                    I18n.t(next ? "layoutEditor.body.following" : "layoutEditor.body.page1"));
        for (Element e : layout.fields) {
            if (e.next != next || e.rect == null)
                continue;
            add(areas, kinds, names, new Shown(LayoutCanvas.Kind.FIELD, e, null), e.rect.toPoints(), elementName(e));
            if (e.isLabelShown() && e.label != null && e.label.rect != null)
                add(areas, kinds, names, new Shown(LayoutCanvas.Kind.LABEL_RECT, e, null), e.label.rect.toPoints(),
                        I18n.t("layoutEditor.label") + ": " + elementName(e));
        }
        for (Placement p : placements) {
            List<Element> from = p.fixed() ? layout.fields : layout.parts;
            if (p.index() < 0 || p.index() >= from.size())
                continue;
            Element e = from.get(p.index());
            LayoutCanvas.Kind kind;
            if (p.fixed()) {
                if (!p.label() || (e.label != null && e.label.rect != null))
                    continue;
                kind = LayoutCanvas.Kind.LABEL_PLACED;
            } else {
                kind = p.label() ? LayoutCanvas.Kind.FLOW_LABEL : LayoutCanvas.Kind.FLOW_VALUE;
            }
            String name = p.label() ? I18n.t("layoutEditor.label") + ": " + elementName(e) : elementName(e);
            add(areas, kinds, names, new Shown(kind, e, p.area()), p.area(), name);
        }
        updating = true;
        try {
            canvas.setRegions(areas, kinds, names);
        } finally {
            updating = false;
        }
    }

    private void add(List<ViewArea> areas, List<LayoutCanvas.Kind> kinds, List<String> names, Shown s,
                     Rectangle2D area, String name) {
        shown.add(s);
        areas.add(new ViewArea(area));
        kinds.add(s.kind());
        names.add(name);
    }

    private static double mm(double points) {
        return Math.round(Rect.toMm(points) * 10) / 10.0;
    }

    private static Rect toRect(Rectangle2D r) {
        return new Rect(mm(r.getX()), mm(r.getY()), Math.max(1, mm(r.getWidth())), Math.max(1, mm(r.getHeight())));
    }

    /**
     * A region of the canvas was moved or resized.
     */
    private void areaChanged(int index, Rectangle2D area) {
        if (index < 0 || index >= shown.size())
            return;
        Shown s = shown.get(index);
        Element e = s.element();
        switch (s.kind()) {
            case BODY -> {
                layout.regions.removeIf(r -> r.next() == next);
                layout.regions.add(new Region(next, toRect(area)));
            }
            case FIELD -> e.rect = toRect(area);
            case LABEL_RECT -> label(e).rect = toRect(area);
            case LABEL_PLACED -> {
                // The label leaves the region of its field: it gets its own rectangle, a little wider than the text.
                Rectangle2D r = new Rectangle2D.Double(area.getX(), area.getY() - 1, area.getWidth() + Rect.toPoints(2),
                        area.getHeight() + 2);
                label(e).rect = toRect(r);
            }
            case FLOW_LABEL -> {
                if (e.field() == LayoutField.LINES || e.field() == LayoutField.TOTALS)
                    break;
                Label l = label(e);
                l.dx = mm(Rect.toPoints(l.dx) + area.getX() - s.drawn().getX());
                double dy = area.getY() - s.drawn().getY();
                boolean line = e.field() != null && (e.field().kind == LayoutField.Kind.LINE || e.singleLine);
                if (line && dy < -s.drawn().getHeight() / 2)
                    l.position = Label.Position.ABOVE;
                else if (line && dy > s.drawn().getHeight() / 2)
                    l.position = null;
            }
            case FLOW_VALUE -> {
                // Flowing parts are placed by the body; the order is changed in the list.
            }
        }
        changed();
    }

    /**
     * Delete on a region of the canvas: an element is removed, a label is deleted.
     */
    private void areaRemoved(int index) {
        if (index < 0 || index >= shown.size())
            return;
        Shown s = shown.get(index);
        switch (s.kind()) {
            case BODY -> layout.regions.removeIf(r -> r.next() == next);
            case FIELD -> layout.fields.remove(s.element());
            case LABEL_RECT, LABEL_PLACED, FLOW_LABEL -> s.element().label = new Label(false);
            case FLOW_VALUE -> layout.parts.remove(s.element());
        }
        canvas.select(-1);
        changed();
    }

    // ---------------------------------------------------------------------------------------------- preview

    /**
     * A change of the structure: the list, the areas and the preview are made again.
     */
    private void changed() {
        dirty = true;
        rebuildList();
        placements = List.of();
        rebuildAreas();
        previewTimer.restart();
    }

    /**
     * A change of a property: only the areas and the preview.
     */
    private void changedSoft() {
        dirty = true;
        list.repaint();
        rebuildAreas();
        previewTimer.restart();
    }

    private record Rendered(BufferedImage image, List<Placement> placements, List<Problem> problems) {
    }

    /**
     * A busy cursor and disabled controls instead of a text in the tool bar: a text changes the size of the bar and
     * moves its controls.
     */
    private void setRendering(boolean on) {
        Component field = sampleItems.getEditor().getComponent(0);
        if (on && field.isFocusOwner())
            spinnerHadFocus = true;
        setCursor(Cursor.getPredefinedCursor(on ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
        sample.setEnabled(!on);
        page1.setEnabled(!on);
        following.setEnabled(!on);
        sampleItems.setEnabled(!on && sample.isSelected());
        if (!on && spinnerHadFocus) {
            spinnerHadFocus = false;
            field.requestFocusInWindow();
        }
    }

    private boolean spinnerHadFocus;

    /**
     * Renders the shown page in the background: with the sample invoice, or the template page alone.
     */
    private void renderPreview() {
        previewTimer.stop();
        final int gen = ++generation;
        final byte[] json = layout.toJson(null);
        final boolean following = next;
        final boolean withSample = sample.isSelected();
        final int count = (Integer) sampleItems.getValue();
        final Locale lang = language;
        setRendering(true);
        new SwingWorker<Rendered, Void>() {
            @Override
            protected Rendered doInBackground() throws Exception {
                Layout copy = Layout.fromJson(json, null);
                List<Problem> found = new ArrayList<>(TemplateCheck.check(template, copy));
                if (copy.region(true) == null && !copy.parts.isEmpty())
                    found.add(new Problem(Problem.Severity.INFO, I18n.t("layoutEditor.body.derived"), null));
                // The preview needs a following page even if the user hasn't chosen its source yet.
                if (copy.continuation == null && templatePages < 2)
                    copy.continuation = Layout.Continuation.PAGE1;
                if (!withSample)
                    return new Rendered(templatePage(copy, following), List.of(), found);
                InvoiceContent content = InvoiceRenderer.content(
                        SampleInvoice.create(following ? Math.max(count, ITEMS_FOLLOWING_PAGES) : count), lang);
                TemplateEngine.Result r = TemplateEngine.render(template, copy, content, lang,
                        PdfStandardProfile.PDFA_3U);
                found.addAll(r.problems());
                if (r.pdf() == null)
                    return new Rendered(templatePage(copy, following), List.of(), found);
                try (PDDocument doc = Loader.loadPDF(r.pdf())) {
                    int page = following ? Math.min(1, doc.getNumberOfPages() - 1) : 0;
                    BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(page, (float) DPI);
                    List<Placement> onPage = r.placements().stream().filter(p -> p.page() == page + 1).toList();
                    return new Rendered(image, onPage, found);
                }
            }

            @Override
            protected void done() {
                if (gen != generation)
                    return;
                setRendering(false);
                Rendered r;
                try {
                    r = get();
                } catch (Exception e) {
                    Log.exception(e);
                    problems.clear();
                    problems.addElement(new Problem(Problem.Severity.ERROR,
                            I18n.t("layoutEditor.previewFailed", String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage())), null));
                    return;
                }
                canvas.setImage(r.image(), DPI / 72.0);
                pageWidth = r.image().getWidth() / (DPI / 72.0);
                placements = r.placements();
                rebuildAreas();
                problems.clear();
                for (Problem p : r.problems())
                    problems.addElement(p);
            }
        }.execute();
    }

    /**
     * The page of the template that the shown page is made from, without the invoice.
     */
    private BufferedImage templatePage(Layout copy, boolean following) throws IOException {
        if (!following || copy.continuation == Layout.Continuation.PAGE1 || copy.continuation == null)
            return render(template, 0);
        return switch (copy.continuation) {
            case TEMPLATE -> render(template, Math.min(1, templatePages - 1));
            case PDF -> copy.continuationFile != null && Files.isReadable(copy.continuationFile)
                    ? render(Files.readAllBytes(copy.continuationFile), 0) : blank();
            default -> blank();
        };
    }

    private BufferedImage render(byte[] pdf, int page) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFRenderer(doc).renderImageWithDPI(page, (float) DPI);
        }
    }

    private BufferedImage blank() throws IOException {
        BufferedImage first = render(template, 0);
        BufferedImage image = new BufferedImage(first.getWidth(), first.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.dispose();
        return image;
    }

    // ---------------------------------------------------------------------------------------------- save

    private void save() {
        try (PDDocument doc = Loader.loadPDF(template)) {
            var box = doc.getPage(0).getCropBox();
            int rotation = ((doc.getPage(0).getRotation() % 360) + 360) % 360;
            double w = Rect.toMm(box.getWidth()), h = Rect.toMm(box.getHeight());
            layout.pageCount = doc.getNumberOfPages();
            layout.pageWidth = Math.round((rotation == 90 || rotation == 270 ? h : w) * 10) / 10.0;
            layout.pageHeight = Math.round((rotation == 90 || rotation == 270 ? w : h) * 10) / 10.0;
            // Pictures no element uses any more are not stored.
            layout.images.keySet().removeIf(name -> layout.fields.stream().noneMatch(e -> name.equals(e.image))
                    && layout.parts.stream().noneMatch(e -> name.equals(e.image)));
            Path file = LayoutFiles.save(layout, templatePath);
            dirty = false;
            saved = true;
            JOptionPane.showMessageDialog(this, I18n.t("layoutEditor.saved", file), I18n.t("layoutEditor.name"),
                    JOptionPane.INFORMATION_MESSAGE);
            dispose();
        } catch (IOException e) {
            Log.exception(e);
            JOptionPane.showMessageDialog(this, I18n.t("layoutEditor.saveFailed", e.getMessage()),
                    I18n.t("layoutEditor.name"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void cancel() {
        if (dirty && JOptionPane.showConfirmDialog(this, I18n.t("layoutEditor.discard"), I18n.t("layoutEditor.name"),
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION)
            return;
        previewTimer.stop();
        dispose();
    }
}
