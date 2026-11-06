package com.bw.pdfixit.ui.invoice;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.invoice.InvoiceData;
import com.bw.pdfixit.invoice.Party;
import com.bw.pdfixit.invoice.Role;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Component;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The fields of one invoice, bound to its {@link InvoiceData}: each method creates one field (e.g. {@link #number()}),
 * which shows the value of the data and writes changes back. The panels of the {@link InvoiceEditor} arrange them in
 * tabs; other forms (the steps of a wizard) can arrange them differently.<br>
 * The fields are tracked together: they are filled again after the data was changed elsewhere ({@link #reload()}),
 * enabled or disabled for the profile of the invoice ({@link #updateForProfile()}), and marked while their value
 * differs from the one at {@link #captureOriginals()}.
 */
final class InvoiceFields {

    private static final Map<String, String> DOCUMENT_TYPES = new LinkedHashMap<>();
    private static final Map<String, String> PROFILES = new LinkedHashMap<>();

    static {
        DOCUMENT_TYPES.put(InvoiceData.TYPE_INVOICE, "invoice.type.380");
        DOCUMENT_TYPES.put(InvoiceData.TYPE_CREDIT_NOTE, "invoice.type.381");
        // All profiles, from the smallest to the largest. It is up to the user to know which ones are legal.
        PROFILES.put(InvoiceData.PROFILE_MINIMUM, "editor.profile.minimum");
        PROFILES.put(InvoiceData.PROFILE_BASIC_WL, "editor.profile.basicwl");
        PROFILES.put(InvoiceData.PROFILE_BASIC, "editor.profile.basic");
        PROFILES.put(InvoiceData.PROFILE_EN16931, "editor.profile.en16931");
        PROFILES.put(InvoiceData.PROFILE_EXTENDED, "editor.profile.extended");
        PROFILES.put(InvoiceData.PROFILE_XRECHNUNG, "editor.profile.xrechnung");
    }

    private final InvoiceData data;
    final Locale locale = Locale.getDefault();
    /**
     * Called after the user changed a value.
     */
    private final Runnable onChange;

    /**
     * Fields with input that can't be parsed, with the reason.
     */
    private final Map<JComponent, String> invalid = new LinkedHashMap<>();
    /**
     * Fill the fields again from the data, see {@link #reload()}.
     */
    private final List<Runnable> reloaders = new ArrayList<>();
    private final List<PartyPanel> partyPanels = new ArrayList<>();
    private final List<PresetChooser> presetChoosers = new ArrayList<>();
    /**
     * Enable or disable the fields the profile of the invoice has or not, see {@link #restrict}.
     */
    private final List<Runnable> profileUpdaters = new ArrayList<>();
    private boolean reloading;
    private PresetChooser sellerPresets;

    /**
     * A field and how to read its value, to mark it if the value differs from the one when the editor was opened.
     */
    private static final class Tracked {
        final JComponent field;
        final Supplier<?> current;
        Object original;

        Tracked(JComponent field, Supplier<?> current) {
            this.field = field;
            this.current = current;
        }

        void mark() {
            boolean modified = !Outline.same(current.get(), original);
            if (field instanceof DateField d)
                d.setModified(modified);
            else
                Outline.apply(field, false, modified);
        }
    }

    private final List<Tracked> tracked = new ArrayList<>();
    private boolean originalsKnown;

    /**
     * @param data     The invoice.
     * @param onChange Called after the user changed a value of a field.
     */
    InvoiceFields(InvoiceData data, Runnable onChange) {
        this.data = data;
        this.onChange = onChange;
    }

    InvoiceData data() {
        return data;
    }

    /**
     * Tells the owner that the user changed a value.
     */
    void changed() {
        onChange.run();
    }

    /**
     * Why the input of fields can't be used, e.g. a date that can't be parsed.
     */
    Collection<String> invalidInput() {
        return invalid.values();
    }

    // ---------------------------------------------------------------- the fields

    /**
     * The type of the document (invoice, credit note, BT-3).
     */
    JComboBox<String> documentType() {
        // Type codes; another code of an existing invoice is added as own entry.
        JComboBox<String> type = codeCombo(DOCUMENT_TYPES, () -> data.documentCode, v -> data.documentCode = v,
                code -> I18n.t("editor.type.other", code));
        TermHelp.apply(type, "BT-3");
        return type;
    }

    /**
     * The profile (guideline, BT-24).
     */
    JComboBox<String> profile() {
        JComboBox<String> profile = codeCombo(PROFILES, () -> data.profile, v -> data.profile = v,
                InvoiceFields::otherProfileName);
        profile.setToolTipText(I18n.t("editor.profile.tip"));
        // The help of the specification follows the own tool tip; restrict() keeps it.
        TermHelp.apply(profile, "BT-24");
        return profile;
    }

    /**
     * The invoice number (BT-1). It has at most 20 characters (it is the reference of the payment).
     */
    JTextField number() {
        return TermHelp.apply(text(() -> data.number, 16, v -> data.number = v), "BT-1");
    }

    JTextField currency() {
        return TermHelp.apply(text(() -> data.currency, 4, v -> data.currency = v), "BT-5");
    }

    DateField issueDate() {
        return TermHelp.apply(date(() -> data.issueDate, v -> data.issueDate = v, I18n.t("invoice.issueDate")), "BT-2");
    }

    DateField deliveryDate() {
        return restrict(TermHelp.apply(date(() -> data.deliveryDate, v -> data.deliveryDate = v,
                I18n.t("editor.deliveryDate")), "BT-72"), InvoiceData.Field.DELIVERY_DATE);
    }

    DateField periodStart() {
        return restrict(TermHelp.apply(date(() -> data.periodStart, v -> data.periodStart = v,
                I18n.t("editor.periodStart")), "BT-73"), InvoiceData.Field.PERIOD);
    }

    DateField periodEnd() {
        return restrict(TermHelp.apply(date(() -> data.periodEnd, v -> data.periodEnd = v,
                I18n.t("editor.periodEnd")), "BT-74"), InvoiceData.Field.PERIOD);
    }

    /**
     * The reference of the buyer (BT-10), the Leitweg-ID with XRechnung.
     */
    JTextField buyerReference() {
        JTextField reference = text(() -> data.buyerReference, 14, v -> data.buyerReference = v);
        reference.setToolTipText(I18n.t("editor.reference.tip"));
        return TermHelp.apply(reference, "BT-10");
    }

    JTextField note() {
        return restrict(TermHelp.apply(text(() -> data.note, 14, v -> data.note = v), "BT-22"), InvoiceData.Field.NOTE);
    }

    JTextField vatExemptionReason() {
        JTextField exemption = text(() -> data.vatExemptionReason, 14, v -> data.vatExemptionReason = v);
        exemption.setToolTipText(I18n.t("editor.exemption.tip"));
        return TermHelp.apply(exemption, "BT-120");
    }

    DateField dueDate() {
        return restrict(TermHelp.apply(date(() -> data.dueDate, v -> data.dueDate = v, I18n.t("invoice.dueDate")),
                "BT-9"), InvoiceData.Field.PAYMENT);
    }

    JTextField paymentTerms() {
        return restrict(TermHelp.apply(text(() -> data.paymentTerms, 14, v -> data.paymentTerms = v), "BT-20"),
                InvoiceData.Field.PAYMENT);
    }

    /**
     * Seller or buyer data with the preset chooser in the first row.
     * Choosing a preset copies its values into the invoice.
     */
    PartyPanel party(Role role) {
        Party party = role == Role.SELLER ? data.seller : data.buyer;
        PartyPanel[] panel = new PartyPanel[1];
        PresetChooser chooser = new PresetChooser(role, party, () -> {
            panel[0].reload();
            // A buyer may bring payment terms for the fields of the invoice that are still empty.
            if (role == Role.BUYER && data.applyBuyerPaymentDefaults())
                reload();
            changed();
        }, role == Role.BUYER ? data::takePaymentConditions : null);
        panel[0] = new PartyPanel(role == Role.SELLER, I18n.t("editor.preset"), chooser);
        panel[0].setParty(party);
        panel[0].setBaseline(party.copy());
        panel[0].setOnChange(() -> {
            chooser.refreshButton();
            changed();
        });
        partyPanels.add(panel[0]);
        presetChoosers.add(chooser);
        profileUpdaters.add(() -> panel[0].setSupported(data::supports));
        if (role == Role.SELLER)
            sellerPresets = chooser;
        return panel[0];
    }

    /**
     * The key of the seller preset that is selected, null if none.
     */
    String selectedSellerPreset() {
        return sellerPresets == null ? null : sellerPresets.getSelectedKey();
    }

    /**
     * Name of a profile that is not in the list of the editor but is known, e.g. "Basic" for the id of another version of
     * the standard ("urn:cen.eu:en16931:2017#compliant#urn:zugferd.de:2p1:basic"). Unknown guideline ids are shown as they are.
     */
    static String otherProfileName(String guidelineId) {
        String id = guidelineId.trim();
        // The last part of the id: "urn:factur-x.eu:1p0:minimum" is "minimum".
        String part = id.substring(Math.max(id.lastIndexOf(':'), id.lastIndexOf('#')) + 1);
        String name = InvoiceDialog.profileName(part);
        // An unknown part is returned as it is.
        return name.equals(part) ? I18n.t("editor.profile.other", guidelineId) : name;
    }

    // ---------------------------------------------------------------- tracking

    /**
     * Shows the data again in all fields, e.g. after it was changed in the tab "All fields".
     */
    void reload() {
        reloading = true;
        try {
            reloaders.forEach(Runnable::run);
            partyPanels.forEach(PartyPanel::reload);
        } finally {
            reloading = false;
        }
    }

    /**
     * Selects in the preset choosers the presets that fit seller and buyer. Used after the invoice was replaced (an
     * imported XML, a change in the tab "All fields"); editing the fields of a party never changes the selection.
     */
    void selectMatchingPresets() {
        presetChoosers.forEach(PresetChooser::selectMatching);
    }

    /**
     * Enables the fields the profile of the invoice has and disables the others.
     */
    void updateForProfile() {
        profileUpdaters.forEach(Runnable::run);
    }

    /**
     * Remembers the values shown now as the original ones. Later changes are marked.
     */
    void captureOriginals() {
        tracked.forEach(t -> t.original = t.current.get());
        originalsKnown = true;
        // The fields of seller and buyer compare with their own baseline.
        partyPanels.forEach(PartyPanel::captureBaseline);
    }

    /**
     * Marks the fields whose value differs from the original one.
     */
    void markModified() {
        if (!originalsKnown)
            return;
        tracked.forEach(Tracked::mark);
    }

    // ---------------------------------------------------------------- building blocks

    /**
     * Combo box for a code with readable names. A code of the invoice that is not in the list
     * (e.g. another profile) is added, so it is kept unless the user chooses another one.
     *
     * @param unknownName Name for a code that is not in the list.
     */
    private JComboBox<String> codeCombo(Map<String, String> names, Supplier<String> getter, Consumer<String> setter,
                                        java.util.function.Function<String, String> unknownName) {
        JComboBox<String> combo = track(new JComboBox<>(), getter);
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                String code = (String) value;
                String text = code == null ? "" : names.containsKey(code) ? I18n.t(names.get(code)) : unknownName.apply(code);
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
            }
        });
        reloaders.add(() -> {
            String code = getter.get();
            combo.removeAllItems();
            names.keySet().forEach(combo::addItem);
            if (code != null && !names.containsKey(code))
                combo.addItem(code);
            combo.setSelectedItem(code == null ? names.keySet().iterator().next() : code);
        });
        combo.addActionListener(e -> {
            if (reloading || combo.getSelectedItem() == null)
                return;
            setter.accept((String) combo.getSelectedItem());
            changed();
        });
        return combo;
    }

    /**
     * Disables the field if the profile of the invoice doesn't have it, see {@link InvoiceData#supports}.
     *
     * @return The field.
     */
    private <T extends JComponent> T restrict(T field, InvoiceData.Field group) {
        String toolTip = field.getToolTipText();
        profileUpdaters.add(() -> {
            boolean ok = data.supports(group);
            field.setEnabled(ok);
            field.setToolTipText(ok ? toolTip : I18n.t("party.notInProfile"));
        });
        return field;
    }

    /**
     * Marks the field while its value differs from the one when the editor was opened.
     */
    private <T extends JComponent> T track(T field, Supplier<?> current) {
        tracked.add(new Tracked(field, current));
        return field;
    }

    private JTextField text(Supplier<String> getter, int columns, Consumer<String> setter) {
        JTextField f = track(new JTextField(columns), getter);
        reloaders.add(() -> {
            String v = getter.get();
            f.setText(v == null ? "" : v);
            f.setCaretPosition(0);
        });
        onChange(f, () -> {
            if (reloading)
                return;
            setter.accept(f.getText());
            changed();
        });
        return f;
    }

    private DateField date(Supplier<LocalDate> getter, Consumer<LocalDate> setter, String caption) {
        DateField f = track(new DateField(locale), getter);
        reloaders.add(() -> {
            f.setDate(getter.get());
            invalid.remove(f);
        });
        f.addChangeListener(() -> {
            if (reloading) {
                return;
            }
            if (f.isInputValid()) {
                invalid.remove(f);
            } else {
                invalid.put(f, I18n.t("editor.invalidDate", caption, DateField.pattern(locale)));
            }
            setter.accept(f.getDate());
            changed();
        });
        return f;
    }

    private static void onChange(JTextField f, Runnable r) {
        f.getDocument().addDocumentListener(new DocumentListener() {
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
}
