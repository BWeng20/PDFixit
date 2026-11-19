package com.bw.pdfixit.ui.invoice;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.PdfStandard;
import com.bw.pdfixit.Preferences;
import com.bw.pdfixit.SafeFile;
import com.bw.pdfixit.invoice.EInvoice;
import com.bw.pdfixit.invoice.InvoiceData;
import com.bw.pdfixit.model.DocumentProxy;
import com.bw.pdfixit.toast.ToastType;
import com.bw.pdfixit.toast.Toaster;
import com.bw.pdfixit.ui.BadgedBox;
import com.bw.pdfixit.ui.BoxBorder;
import com.bw.pdfixit.ui.UI;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The e-invoice (ZUGFeRD/Factur-X) of the document as a panel, e.g. in a tab, in two boxes: the invoice in the document
 * (its automatic check and the actions on it) and the editor to create or change the invoice, with a mark if it has
 * changes and the buttons to apply or discard them below.<br>
 * The panel follows the document, see {@link #documentChanged}. The editor is created when the panel is shown.
 * If the user has changes in the editor, they are kept if the document changes, they are never discarded silently.
 */
public final class InvoiceEditorPanel extends JPanel {

    /**
     * What the editor does for the current document.
     */
    public enum Mode {
        /**
         * There is no document.
         */
        NONE,
        /**
         * The document has no invoice, the editor creates one.
         */
        CREATE,
        /**
         * The editor shows the invoice of the document.
         */
        EDIT
    }

    private DocumentProxy proxy;
    private final Toaster toaster;
    private final JPanel editorHolder = new JPanel(new BorderLayout());
    private final JPanel headerHolder = new JPanel(new BorderLayout());
    /**
     * The box with the status of the invoice in the document. It holds the help badge when there is one.
     */
    private final JPanel documentBox = new JPanel(new BorderLayout());
    private final JPanel documentHolder = new JPanel(new BorderLayout());
    /**
     * The completeness line and the buttons to apply or discard the changes, see {@link #saveBar()}.
     */
    private final JPanel saveBar = new JPanel(new BorderLayout());
    /**
     * Tells if the editor holds a complete invoice, right above the buttons that put it into the document (in the
     * {@link #saveBar()}). What is missing, or worth a look, is listed in the editor, beside the sums.
     */
    private final JLabel completeLabel = new JLabel(" ");
    /**
     * The mark at the right corner of the editor box: the invoice of the editor has changes that are not in the document.
     */
    private final Chip modifiedLabel = new Chip(I18n.t("invoice.editor.modified"));
    /**
     * The menu with the actions on the invoice in the document: show it, save its XML, remove it.
     */
    private JButton attachedButton;
    /**
     * The user edited an invoice that the document doesn't have (a new one, or another one than the document has), so
     * it is meant for the document: it is put into the document when the file is written, or by the Apply button.
     * An invoice that was only loaded or kept (e.g. after removing the invoice of the document) is not.
     */
    private boolean draftWanted;
    /**
     * The number of edits of the editor that were seen, see {@link InvoiceEditor#editCount()}.
     */
    private int seenEdits;
    /**
     * Puts the invoice of the editor into the document: "Apply to document", "Add to document" or "Replace invoice".
     */
    private final JButton applyButton = new JButton();
    /**
     * The invoice needs a PDF/A-3 document and the document is not one: the editor is locked until it is converted.
     */
    private boolean needsConversion;
    private Runnable convertAction;
    private final JButton convertButton = new JButton(I18n.t("invoice.convert"));
    private final JButton discardButton = new JButton(I18n.t("invoice.discard"));
    private final JPanel toolRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));

    /**
     * How the invoice of the editor relates to the invoice in the document.
     */
    private enum State {
        /**
         * The editor shows the invoice of the document, unchanged.
         */
        MATCHES,
        /**
         * The editor shows the invoice of the document, with changes.
         */
        MODIFIED,
        /**
         * The document has no invoice.
         */
        NEW,
        /**
         * The editor holds another invoice than the document has.
         */
        OTHER
    }

    private Consumer<Mode> modeListener;
    private InvoiceEditor editor;
    /**
     * If the content of the editor is the invoice of the document (and not a new one).
     */
    private boolean existing;
    private boolean working;
    private boolean hasDocument;
    private boolean documentHasInvoice;
    /**
     * If the editor doesn't show the current document: it must be created again when the panel is shown.
     */
    private boolean stale = true;
    private boolean shown;

    /**
     * @param toaster Shows the results of the actions on the document.
     */
    public InvoiceEditorPanel(Toaster toaster) {
        super(new BorderLayout());
        this.toaster = toaster;

        createToolRow();
        applyButton.setText(I18n.t("invoice.apply.modified"));
        applyButton.setToolTipText(I18n.t("invoice.apply.tip"));
        applyButton.setEnabled(false);
        applyButton.addActionListener(e -> applyNow());
        convertButton.setToolTipText(I18n.t("invoice.convert.tip"));
        convertButton.setVisible(false);
        convertButton.addActionListener(e -> {
            if (convertAction != null)
                convertAction.run();
        });
        discardButton.setToolTipText(I18n.t("invoice.discard.tip"));
        discardButton.setEnabled(false);
        discardButton.addActionListener(e -> discardChanges());
        modifiedLabel.setVisible(false);

        // Box 1: the invoice in the document. What the automatic check says (see InvoiceActions), and what can be done
        // with the invoice of the document.
        // In one row: the status line (and its details) take the room, the buttons are right.
        JPanel documentTools = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        documentTools.add(convertButton);
        documentTools.add(attachedButton);
        JPanel documentContent = new JPanel(new BorderLayout(6, 0));
        documentContent.setBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5));
        documentContent.add(headerHolder, BorderLayout.CENTER);
        documentContent.add(documentTools, BorderLayout.EAST);
        documentBox.setBorder(BoxBorder.create(I18n.t("invoice.box.document")));
        documentBox.add(documentContent, BorderLayout.CENTER);
        documentHolder.add(documentBox, BorderLayout.CENTER);

        // Box 2: the editor, with the actions on its content on top. The list of what is missing or worth a look is in
        // the editor, beside the sums.
        JPanel editorContent = new JPanel(new BorderLayout(0, 8));
        editorContent.setBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5));
        editorContent.add(toolRow, BorderLayout.NORTH);
        // Without a document the editor is shown too, greyed out: it shows what the tab does.
        editorContent.add(editorHolder, BorderLayout.CENTER);

        // The bar with the buttons that put the invoice into the document or throw the changes away, and the line
        // about the invoice right above them: it tells if the invoice can be put into the document. It is not in the
        // panel, the window puts it below the tab and keeps it in sight while the tab scrolls (see saveBar()).
        JPanel saveRow = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        saveRow.add(discardButton);
        saveRow.add(applyButton);
        JPanel save = new JPanel(new BorderLayout(0, 6));
        save.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        save.add(completeLabel, BorderLayout.NORTH);
        save.add(saveRow, BorderLayout.CENTER);
        saveBar.add(new JSeparator(), BorderLayout.NORTH);
        saveBar.add(save, BorderLayout.CENTER);
        JPanel editorBox = new JPanel(new BorderLayout());
        editorBox.setBorder(BoxBorder.create(I18n.t("invoice.box.editor")));
        editorBox.add(editorContent, BorderLayout.CENTER);

        JPanel boxes = new JPanel(new BorderLayout(0, 5));
        boxes.add(documentHolder, BorderLayout.NORTH);
        boxes.add(new BadgedBox(editorBox, modifiedLabel, true), BorderLayout.CENTER);
        add(boxes, BorderLayout.CENTER);
    }

    /**
     * The bar with the buttons to apply or discard the changes of the editor and the line that tells if the invoice is
     * complete. The window puts it below the tab, it sticks to the bottom of the view while the end of the tab is
     * scrolled out of view.
     */
    public JComponent saveBar() {
        return saveBar;
    }

    /**
     * Puts a badge (the help button) behind the title of the box with the invoice in the document.
     */
    public void setHelpBadge(JComponent badge) {
        documentHolder.removeAll();
        documentHolder.add(new BadgedBox(documentBox, badge), BorderLayout.CENTER);
        documentHolder.revalidate();
    }

    /**
     * @param listener Called if the mode changes, in the event thread. It is also called with the current mode.
     */
    public void setModeListener(Consumer<Mode> listener) {
        this.modeListener = listener;
        fireMode();
    }

    /**
     * Sets the status line of the box with the invoice in the document, e.g. the result of the check.
     */
    public void setHeader(JComponent header) {
        headerHolder.removeAll();
        headerHolder.add(header, BorderLayout.CENTER);
        headerHolder.revalidate();
    }

    /**
     * @return True if the editor holds an invoice that the document doesn't have (changes of the invoice of the
     * document, a new invoice, or one that was kept when the invoice was removed) and that was not stored in a file.
     * It would be lost if the editor is replaced. If the changes were undone, the editor shows the invoice of the
     * document again: there is nothing to lose then.
     */
    public boolean isDirty() {
        if (editor == null)
            return false;
        editor.stopEditing();
        return editor.isDirty() && state() != State.MATCHES;
    }

    /**
     * @return True if the editor shows the invoice of the document with changes. Such an invoice is not yet in the
     * document: the check of the invoice of the document doesn't see the changes.
     */
    public boolean isModified() {
        return editor != null && state() == State.MODIFIED;
    }

    public Mode mode() {
        if (!hasDocument)
            return Mode.NONE;
        boolean edit = editor == null || stale ? documentHasInvoice : existing;
        return edit ? Mode.EDIT : Mode.CREATE;
    }

    /**
     * The document was loaded, changed or closed. Call it in the event thread.
     */
    public void documentChanged(DocumentProxy proxy) {
        documentChanged(proxy, false);
    }

    /**
     * The document was loaded, changed or closed. Call it in the event thread.
     * @param loaded          If the document was just loaded or closed. The editor shows the state of the document
     *                        then, changes in the editor for the document before are dropped. Otherwise they are kept.
     */
    public void documentChanged(DocumentProxy proxy, boolean loaded) {
        this.proxy =proxy;
        PDDocument document = (proxy != null)? proxy().getDocument() : null;
        this.hasDocument = document != null;
        this.documentHasInvoice = hasDocument && EInvoice.hasInvoice(document);
        this.needsConversion = EInvoice.needsConversion(document);

        if (loaded && editor != null) {
            // Not the editor of the document before: it is created again, now or when the panel is shown.
            editor = null;
            existing = false;
            editorHolder.removeAll();
            editorHolder.revalidate();
            editorHolder.repaint();
            draftWanted = false;
        }
        if (editor != null && editor.isDirty()) {
            // The changes of the user are kept, but they are no invoice of the document (yet).
            if (existing) {
                existing = false;
            }
        } else {
            stale = true;
            if (shown)
                rebuild();
        }
        fireMode();
        updateStatus();
    }

    /**
     * Tells the panel if it is visible. The editor is created on demand.
     */
    public void setShown(boolean shown) {
        this.shown = shown;
        if (shown && (stale || editor == null))
            rebuild();
    }

    // ---------------------------------------------------------------- layout

    /**
     * The actions on the invoice in the editor are buttons (in the tool row of the editor). The actions on the invoice
     * of the document are in the popup menu of an own button in the box of the document.
     */
    private void createToolRow() {
        toolRow.add(button(I18n.t("invoiceWindow.new"), I18n.t("invoiceWindow.new.tip"), e -> newInvoice()));

        // The XML file: it is read into the editor or written from the editor, the document is not touched.
        JPopupMenu xml = new JPopupMenu();
        xml.add(item(I18n.t("invoice.xml.import"), I18n.t("editor.loadXml.tip"), e -> loadXml()));
        xml.add(item(I18n.t("invoice.xml.export"), I18n.t("editor.saveXml.tip"), e -> saveXml()));
        toolRow.add(popupButton(I18n.t("invoice.xml.menu"), I18n.t("invoice.xml.menu.tip"), xml));

        // The invoice in the document.
        JMenuItem show = item(I18n.t("menu.invoice.show"), null, e -> showDocumentInvoice());
        JMenuItem saveAttached = item(I18n.t("invoiceWindow.saveAttached"), I18n.t("invoiceWindow.saveAttached.tip"), e -> saveAttachedXml());
        JMenuItem remove = item(I18n.t("ui.invoice.remove"), I18n.t("invoiceWindow.remove.tip"), e -> removeInvoice());
        JPopupMenu document = new JPopupMenu();
        document.add(show);
        document.add(saveAttached);
        document.addSeparator();
        document.add(remove);
        document.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                boolean hasInvoice = hasInvoice();
                show.setEnabled(hasInvoice);
                saveAttached.setEnabled(hasInvoice);
                remove.setEnabled(hasInvoice);
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
        attachedButton = popupButton(I18n.t("invoiceWindow.menu.document"), I18n.t("invoice.tab.documentActions.tip"), document);
    }

    private static JButton popupButton(String text, String toolTip, JPopupMenu popup) {
        JButton button = button(text + " \u25BE", toolTip, null);
        button.addActionListener(e -> popup.show(button, 0, button.getHeight()));
        return button;
    }

    private static JButton button(String text, String toolTip, java.awt.event.ActionListener action) {
        JButton button = new JButton(text);
        button.setToolTipText(toolTip);
        if (action != null)
            button.addActionListener(action);
        return button;
    }

    private static JMenuItem item(String text, String toolTip, java.awt.event.ActionListener action) {
        JMenuItem item = new JMenuItem(text);
        item.setToolTipText(toolTip);
        item.addActionListener(action);
        return item;
    }

    /**
     * Creates the editor for the invoice of the document, or for a new invoice if the document has none or its
     * invoice can't be read.
     */
    private void rebuild() {
        stale = false;
        if (!hasDocument || proxy() == null) {
            // Nothing to edit: an empty invoice, greyed out.
            setEditor(InvoiceEditor.newInvoice(), false);
            return;
        }
        InvoiceData data = null;
        try {
            byte[] xml = EInvoice.extractXml(proxy().getDocument()).orElse(null);
            if (xml != null) {
                try {
                    data = InvoiceData.fromXml(xml);
                } catch (Exception e) {
                    Log.exception(e);
                    JOptionPane.showMessageDialog(this,
                            I18n.t("editor.cannotRead", escape(String.valueOf(e.getMessage()))),
                            title(), JOptionPane.WARNING_MESSAGE);
                }
            }
        } catch (IOException e) {
            showError(I18n.t("invoice.readFailed"), e);
        }
        setEditor(data == null ? InvoiceEditor.newInvoice() : data, data != null);
    }

    /**
     * Shows an editor for the invoice.
     *
     * @param existing If the invoice is the one of the document.
     */
    private void setEditor(InvoiceData data, boolean existing) {
        editor = new InvoiceEditor(data);
        draftWanted = false;
        seenEdits = editor.editCount();
        editor.setChangeListener(this::updateStatus);
        editorHolder.removeAll();
        editorHolder.add(editor, BorderLayout.CENTER);
        editorHolder.revalidate();
        editorHolder.repaint();
        lockEditorIfNeeded();
        setExisting(existing);
    }

    private void setExisting(boolean existing) {
        this.existing = existing;
        fireMode();
        updateStatus();
    }

    private State state() {
        if (existing)
            return editor != null && editor.isModified() ? State.MODIFIED : State.MATCHES;
        return documentHasInvoice ? State.OTHER : State.NEW;
    }

    /**
     * Shows if the invoice of the editor is complete and if it has changes that are not in the document, and enables
     * what can be done about it.
     */
    private void updateStatus() {
        if (editor == null)
            return;
        updateToolRow();
        convertButton.setVisible(needsConversion);
        convertButton.setEnabled(hasDocument && needsConversion && !working);
        if (locked()) {
            // No document, or the editor is locked until the document is converted: nothing to apply or discard.
            completeLabel.setIcon(null);
            completeLabel.setText(" ");
            modifiedLabel.setVisible(false);
            applyButton.setText(I18n.t("invoice.apply.modified"));
            applyButton.setEnabled(false);
            discardButton.setEnabled(false);
            return;
        }
        State state = state();
        boolean offer = state == State.NEW || state == State.OTHER;
        // An invoice that the document doesn't have is meant for the document if the user edited it.
        if (offer && editor.editCount() != seenEdits)
            draftWanted = true;
        seenEdits = editor.editCount();
        // Pending: put into the document when the file is written. Differs: the editor holds an invoice that the
        // document doesn't have, it would be lost if the editor is replaced, and it can be discarded. An invoice that
        // was kept when the invoice was removed from the document differs, but is not pending: the user took it out.
        boolean pending = state == State.MODIFIED || (offer && draftWanted);
        boolean differs = pending || (offer && editor.isDirty());

        // Complete means: nothing is missing that the invoice needs. Hints are notes, they don't prevent the invoice.
        boolean complete = editor.problems().isEmpty();
        if (!complete) {
            completeLabel.setIcon(InvoiceEditor.smallWarning());
            completeLabel.setText(I18n.t("invoice.editor.incomplete"));
        } else if (!editor.hints().isEmpty()) {
            completeLabel.setIcon(InvoiceEditor.smallInfo());
            completeLabel.setText(I18n.t("invoice.editor.completeHints", editor.hints().size()));
        } else {
            completeLabel.setIcon(UI.getIcon("svg/status-ok"));
            completeLabel.setText(I18n.t("invoice.editor.complete"));
        }
        modifiedLabel.setVisible(differs);
        modifiedLabel.setToolTipText(I18n.t(switch (state) {
            case NEW -> draftWanted ? "invoice.state.newEdited.tip" : "invoice.state.kept.tip";
            case OTHER -> draftWanted ? "invoice.state.otherEdited.tip" : "invoice.state.kept.tip";
            default -> "invoice.state.modified.tip";
        }));
        applyButton.setText(I18n.t(switch (state) {
            case NEW -> "invoice.apply.add";
            case OTHER -> "invoice.apply.replace";
            default -> "invoice.apply.modified";
        }));
        // A complete invoice that the document doesn't have can be put into the document, also if it was not edited
        // (e.g. it was kept when the invoice was removed from the document). Only a pending invoice is put into it
        // when the file is written.
        applyButton.setEnabled((pending || (offer && complete)) && !working);
        discardButton.setEnabled(differs && !working);
    }

    /**
     * A small rounded mark with a text, e.g. "modified".
     */
    private static final class Chip extends JLabel {
        Chip(String text) {
            super(text);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(1, 8, 1, 8));
            setFont(getFont().deriveFont(Font.BOLD, getFont().getSize2D() * 0.9f));
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                Color accent = UIManager.getColor("Component.warning.focusedBorderColor");
                g2.setColor(accent == null ? new Color(0xD9962B) : accent);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            } finally {
                g2.dispose();
            }
            // Dark text on the amber, whatever the look and feel.
            setForeground(new Color(0x1E1E1E));
            super.paintComponent(g);
        }
    }

    /**
     * The actions need a document and are locked while the document changes.
     */
    private void updateToolRow() {
        for (java.awt.Component c : toolRow.getComponents())
            c.setEnabled(!locked() && !working);
        // The invoice of a document that is not PDF/A-3 can be shown, saved or removed, too.
        attachedButton.setEnabled(hasDocument && !working);
    }

    /**
     * @return True if the editor can't be used: there is no document, or it is not PDF/A-3.
     */
    private boolean locked() {
        return !hasDocument || needsConversion;
    }

    private void lockEditorIfNeeded() {
        if (!locked())
            return;
        setTreeEnabled(editor, false);
        // The missing data of an empty invoice is no problem to show here.
        editor.setWorking(I18n.t(hasDocument ? "invoice.state.convert.short" : "invoice.state.noDocument"));
    }

    /**
     * @param action Converts the document to PDF/A-3, e.g. by opening the dialog of the standard. It is called by the
     *               button of the locked editor.
     */
    public void setConvertAction(Runnable action) {
        this.convertAction = action;
    }

    /**
     * Enables or disables a component and everything in it.
     */
    private static void setTreeEnabled(java.awt.Component c, boolean enabled) {
        c.setEnabled(enabled);
        if (!enabled && c instanceof javax.swing.JTable table) {
            // A disabled table looks like an enabled one otherwise.
            table.setBackground(UIManager.getColor("Panel.background"));
            table.setForeground(UIManager.getColor("Label.disabledForeground"));
        }
        if (c instanceof java.awt.Container container)
            for (java.awt.Component child : container.getComponents())
                setTreeEnabled(child, enabled);
    }

    private void fireMode() {
        if (modeListener != null)
            modeListener.accept(mode());
    }

    private DocumentProxy proxy() {
        return proxy;
    }

    private boolean hasInvoice() {
        return proxy != null && proxy.getDocument() != null && EInvoice.hasInvoice(proxy.getDocument());
    }

    private String title() {
        return I18n.t("invoiceWindow.title");
    }

    /**
     * @return True if no action is possible: the editor is not there or the document is changing.
     */
    private boolean busy() {
        return working || editor == null || proxy() == null;
    }

    // ---------------------------------------------------------------- invoice of the editor

    /**
     * Asks if the changes of the editor may be discarded.
     *
     * @return True if there are no changes or they may be discarded.
     */
    private boolean confirmDiscard() {
        editor.stopEditing();
        return !isDirty() || JOptionPane.showConfirmDialog(this, I18n.t("party.discard"), title(),
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private void newInvoice() {
        if (!busy() && confirmDiscard())
            setEditor(InvoiceEditor.newInvoice(), false);
    }

    /**
     * Replaces the content by an invoice XML file. Nothing is changed if the file is no valid invoice.
     */
    private void loadXml() {
        if (busy())
            return;
        editor.stopEditing();
        if (isDirty() && JOptionPane.showConfirmDialog(this, I18n.t("editor.replaceQuestion"), title(),
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION)
            return;
        File file = chooseXml(false, I18n.t("editor.loadTitle"), null);
        if (file == null)
            return;
        try {
            editor.replaceXml(Files.readAllBytes(file.toPath()));
        } catch (Exception e) {
            showError(I18n.t("editor.noValidInvoice"), e);
        }
    }

    /**
     * Stores the invoice of the editor as XML file, the document is not changed.
     */
    private void saveXml() {
        if (busy() || !confirmComplete("saved"))
            return;
        InvoiceData data = editor.getData();
        String name = data.number == null || data.number.isBlank() ? "factur-x" : data.number.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        File file = chooseXml(true, I18n.t("editor.saveXml"), name + ".xml");
        if (file == null || (file.exists() && !UI.askOverwrite(this, file.toPath())))
            return;
        try {
            byte[] xml = data.toXml();
            SafeFile.write(file.toPath(), os -> os.write(xml));
            editor.rememberPreset();
            editor.setDirty(false);
        } catch (Exception e) {
            showError(I18n.t("editor.saveFailed"), e);
        }
    }

    /**
     * Shows the problems if there are any.
     *
     * @return True if the data is complete.
     */
    private boolean confirmComplete(String action) {
        editor.stopEditing();
        List<String> p = editor.problems();
        if (p.isEmpty())
            return true;
        JOptionPane.showMessageDialog(this,
                I18n.t("editor.incomplete", I18n.t("editor.action." + action), String.join("<br>", p.stream().map(s -> "• " + escape(s)).toList())),
                title(), JOptionPane.WARNING_MESSAGE);
        return false;
    }

    // ---------------------------------------------------------------- invoice of the document

    /**
     * @return True if the invoice of the editor is written into the document when the file is written: the editor has
     * changes of the invoice of the document, or an invoice that the user edited to add it or to replace the invoice
     * of the document with it (see {@link #draftWanted}).
     */
    public boolean hasPending() {
        if (locked() || editor == null)
            return false;
        State state = state();
        return state == State.MODIFIED || ((state == State.NEW || state == State.OTHER) && draftWanted);
    }

    /**
     * Writes the invoice of the editor into the document if it is {@link #hasPending() pending}. Call it before the
     * document is written or split, in the event thread. The panel waits for the document.
     *
     * @return False if the operation shall be aborted: the invoice is incomplete and the user wants to fix it, or it
     * can't be attached.
     */
    public boolean prepareForSave() {
        if (working || !hasPending())
            return true;
        editor.stopEditing();
        if (!hasPending())
            return true;
        List<String> problems = editor.problems();
        if (!problems.isEmpty()) {
            String list = String.join("<br>", problems.stream().map(s -> "\u2022 " + escape(s)).toList());
            return JOptionPane.showConfirmDialog(this, I18n.t("invoice.save.incomplete", list), title(),
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
        }
        final boolean update = existing || hasInvoice();
        final InvoiceEditor attached = editor;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            proxy().attachInvoice(attached.getData().toXml());
        } catch (Exception e) {
            showError(I18n.t("editor.attachFailed"), e);
            return false;
        } finally {
            setCursor(null);
        }
        invoiceAttached(attached, update);
        return true;
    }

    /**
     * Writes the invoice of the editor into the document now (the Apply button). The invoice is checked, it is not
     * written if it is not valid. An existing invoice is replaced.
     */
    public void applyNow() {
        if (busy() || !confirmComplete("attached"))
            return;
        final byte[] xml;
        try {
            xml = editor.getData().toXml();
        } catch (Exception e) {
            showError(I18n.t("editor.createFailed"), e);
            return;
        }
        boolean update = existing || hasInvoice();
        final InvoiceEditor attached = editor;
        runOnDocument(() -> {
            proxy().attachInvoice(xml);
            return null;
        }, I18n.t("editor.attachFailed"), r -> invoiceAttached(attached, update));
    }

    private void invoiceAttached(InvoiceEditor attached, boolean update) {
        // The document change may have created a new editor for the same invoice.
        InvoiceEditor current = editor == null ? attached : editor;
        current.rememberPreset();
        current.markAttached();
        documentHasInvoice = true;
        draftWanted = false;
        seenEdits = current.editCount();
        setExisting(true);
        toast(update ? I18n.t("toast.invoice.updated") : I18n.t("toast.invoice.attached"));
    }

    /**
     * The "Discard" button: shows the invoice of the document again, after a question.
     */
    private void discardChanges() {
        if (busy())
            return;
        editor.stopEditing();
        if (JOptionPane.showConfirmDialog(this, I18n.t("party.discard"), title(),
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION)
            return;
        if (!documentHasInvoice) {
            // Nothing to show again: a new invoice.
            setEditor(InvoiceEditor.newInvoice(), false);
            return;
        }
        Optional<byte[]> xml = documentXml();
        if (xml.isEmpty())
            return;
        try {
            setEditor(InvoiceData.fromXml(xml.get()), true);
        } catch (Exception e) {
            showError(I18n.t("editor.noValidInvoice"), e);
        }
    }

    private void showDocumentInvoice() {
        documentXml().ifPresent(xml -> InvoiceDialog.showDialog(this, xml));
    }

    /**
     * Stores the invoice XML of the document as it is.
     */
    private void saveAttachedXml() {
        Optional<byte[]> xml = documentXml();
        if (xml.isEmpty())
            return;
        File file = chooseXml(true, I18n.t("invoiceWindow.saveAttached"), "factur-x.xml");
        if (file == null || (file.exists() && !UI.askOverwrite(this, file.toPath())))
            return;
        try {
            SafeFile.write(file.toPath(), os -> os.write(xml.get()));
            toast(I18n.t("toast.invoice.stored"), file.getName());
        } catch (Exception e) {
            showError(I18n.t("invoice.storeFailed"), e);
        }
    }

    /**
     * Removes the invoice from the document. The editor keeps the content, it can be attached again.
     */
    private void removeInvoice() {
        if (busy())
            return;
        // The change of the document must not replace the editor: the content counts as changed.
        boolean wasDirty = editor.isDirty();
        if (existing)
            editor.setDirty(true);
        try {
            if (proxy().removeInvoice()) {
                toast(I18n.t("toast.invoice.removed"));
                documentHasInvoice = false;
                if (existing)
                    setExisting(false);
                else
                    updateStatus();
            } else {
                editor.setDirty(wasDirty);
            }
        } catch (IOException e) {
            editor.setDirty(wasDirty);
            showError(I18n.t("ui.invoice.removeFailed"), e);
        }
    }

    /**
     * The invoice XML of the document, empty (with a message) if there is none or it can't be read.
     */
    private Optional<byte[]> documentXml() {
        try {
            Optional<byte[]> xml = EInvoice.extractXml(proxy().getDocument());
            if (xml.isEmpty())
                JOptionPane.showMessageDialog(this, I18n.t("toast.noInvoice"), title(), JOptionPane.INFORMATION_MESSAGE);
            return xml;
        } catch (IOException e) {
            showError(I18n.t("invoice.readFailed"), e);
            return Optional.empty();
        }
    }

    /**
     * Changes the document in the background, the panel is locked meanwhile.
     *
     * @param work   The change, its result is given to the done action.
     * @param failed Message if the change fails.
     * @param done   Called in the event thread after the change succeeded.
     */
    private <T> void runOnDocument(Callable<T> work, String failed, Consumer<T> done) {
        setWorking(true);
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                setWorking(false);
                try {
                    done.accept(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    showError(failed, e.getCause() == null ? e : e.getCause());
                }
            }
        }.execute();
    }

    // ---------------------------------------------------------------- helpers

    private void setWorking(boolean working) {
        this.working = working;
        setCursor(working ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : null);
        if (editor != null)
            editor.setWorking(working ? I18n.t("editor.attaching") : null);
        updateStatus();
    }

    private File chooseXml(boolean save, String title, String name) {
        JFileChooser chooser = new JFileChooser();
        // The folder of the last XML file, before the first one the folder of the last PDF.
        String lastDir = UI.getPref(Preferences.USER_PREF_LAST_XML_DIR, UI.getPref(Preferences.USER_PREF_LAST_PDF_DIR, null));
        if (lastDir != null)
            chooser.setCurrentDirectory(new File(lastDir));
        chooser.setFileFilter(new FileNameExtensionFilter("XML", "xml"));
        chooser.setMultiSelectionEnabled(false);
        chooser.setDialogTitle(title);
        if (name != null)
            chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), name));
        int result = save ? chooser.showSaveDialog(this) : chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION)
            return null;
        File file = chooser.getSelectedFile();
        File dir = file.getParentFile();
        if (dir != null)
            Preferences.getInstance().set(Preferences.USER_PREF_LAST_XML_DIR, dir.getAbsolutePath());
        return file;
    }

    private void toast(String format, Object... args) {
        if (this.toaster != null)
            this.toaster.toast(ToastType.SUCCESS, 5000, format, args);
    }

    private void showError(String what, Throwable e) {
        Log.exception(e instanceof Exception ex ? ex : new Exception(e));
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        JTextArea area = new JTextArea(message, Math.min(16, message.split("\n").length + 1), 70);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("<html><b>" + escape(what) + "</b></html>"), BorderLayout.NORTH);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
        JOptionPane.showMessageDialog(this, panel, title(), JOptionPane.ERROR_MESSAGE);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
