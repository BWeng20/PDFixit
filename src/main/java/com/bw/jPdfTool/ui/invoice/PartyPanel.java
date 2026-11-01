package com.bw.jPdfTool.ui.invoice;

import com.bw.jPdfTool.I18n;
import com.bw.jPdfTool.invoice.InvoiceData;
import com.bw.jPdfTool.invoice.Party;

import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Edits the data of a seller or buyer. Changes are written directly into the party.<br>
 * Used by the invoice editor and the party settings.
 */
class PartyPanel extends FormPanel {

    /**
     * @param group Field group the profile of the invoice must support, null if every profile has the field.
     */
    private record Binding(JTextField field, InvoiceData.Field group, String toolTip,
                           Function<Party, String> getter, BiConsumer<Party, String> setter) {
    }

    private final List<Binding> bindings = new ArrayList<>();
    private final JTextField nameField;
    private JTextField labelField;
    private Party party;
    private Party baseline;
    private Runnable onChange;
    private boolean updating;
    private Predicate<InvoiceData.Field> supported = f -> true;

    /**
     * @param seller        True for a seller: with tax number, contact and bank account.
     * @param headerCaption Caption of the header row.
     * @param header        Optional component shown in the first row, e.g. the preset chooser of the editor.
     */
    PartyPanel(boolean seller, String headerCaption, JComponent header) {
        this(seller, headerCaption, header, false);
    }

    /**
     * @param withLabel True to show the label of a preset, which tells apart versions of the same party.
     */
    PartyPanel(boolean seller, String headerCaption, JComponent header, boolean withLabel) {
        if (header != null)
            row(headerCaption, header);
        if (withLabel) {
            labelField = field(null, 14, I18n.t("party.label.tip"),
                    p -> p.label, (p, v) -> p.label = v);
            row(I18n.t("party.label"), labelField);
        }
        InvoiceData.Field address = seller ? InvoiceData.Field.SELLER_ADDRESS : InvoiceData.Field.BUYER_DETAILS;
        InvoiceData.Field details = seller ? null : InvoiceData.Field.BUYER_DETAILS;
        InvoiceData.Field email = seller ? InvoiceData.Field.SELLER_EMAIL : InvoiceData.Field.BUYER_DETAILS;

        nameField = field(null, 14, null, p -> p.name, (p, v) -> p.name = v);
        row(I18n.t("party.name"), nameField);
        row(I18n.t("party.street"), field(address, 14, null, p -> p.street, (p, v) -> p.street = v));
        row(I18n.t("party.postcode"), field(address, 6, null, p -> p.zip, (p, v) -> p.zip = v),
                I18n.t("party.city"), field(address, 10, null, p -> p.city, (p, v) -> p.city = v));
        row(I18n.t("party.country"), field(details, 3, I18n.t("party.country.tip"), p -> p.country, (p, v) -> p.country = v),
                I18n.t("party.vatId"), field(details, 10, null, p -> p.vatId, (p, v) -> p.vatId = v));
        if (seller) {
            row(I18n.t("party.taxNumber"), field(null, 10, I18n.t("party.taxNumber.tip"),
                            p -> p.taxId, (p, v) -> p.taxId = v),
                    I18n.t("party.email"), field(email, 10, null, p -> p.email, (p, v) -> p.email = v));
            row(I18n.t("party.contact"), field(InvoiceData.Field.SELLER_CONTACT, 10, null, p -> p.contactName, (p, v) -> p.contactName = v),
                    I18n.t("party.phone"), field(InvoiceData.Field.SELLER_CONTACT, 10, null, p -> p.phone, (p, v) -> p.phone = v));
            section(I18n.t("party.bankAccount"));
            row("IBAN", field(InvoiceData.Field.BANK_ACCOUNT, 14, null,
                            p -> p.iban == null ? null : InvoiceDialog.formatIban(p.iban), (p, v) -> p.iban = v),
                    I18n.t("party.bic"), field(InvoiceData.Field.ACCOUNT_DETAILS, 8, null, p -> p.bic, (p, v) -> p.bic = v));
            row(I18n.t("party.accountHolder"), field(InvoiceData.Field.ACCOUNT_DETAILS, 14, null,
                    p -> p.accountHolder, (p, v) -> p.accountHolder = v));
        } else {
            row(I18n.t("party.email"), field(email, 14, null, p -> p.email, (p, v) -> p.email = v));
        }
        if (withLabel) {
            section(I18n.t("party.paymentAsBuyer"));
            row(I18n.t("party.terms"), field(null, 14, I18n.t("party.terms.tip"),
                    p -> p.paymentTerms, (p, v) -> p.paymentTerms = v));
            row(I18n.t("party.daysUntilDue"), field(null, 6, I18n.t("party.daysUntilDue.tip"),
                    p -> p.paymentDays == null ? null : String.valueOf(p.paymentDays), (p, v) -> p.paymentDays = parseDays(v)));
        }
        setParty(null);
    }

    /**
     * @return The number of days, null if the text is no number from 0 to 9999.
     */
    private static Integer parseDays(String text) {
        String t = text == null ? "" : text.trim();
        return t.matches("\\d{1,4}") ? Integer.valueOf(t) : null;
    }

    /**
     * Called after each change by the user.
     */
    void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    JTextField getNameField() {
        return nameField;
    }

    /**
     * The label field, null if the panel was created without one.
     */
    JTextField getLabelField() {
        return labelField;
    }

    /**
     * Disables the fields the profile of the invoice doesn't have. Their values are kept, but not written.
     */
    void setSupported(Predicate<InvoiceData.Field> supported) {
        this.supported = supported;
        updateEnabled();
    }

    /**
     * Shows and edits this party. Null clears and disables the fields.
     */
    void setParty(Party party) {
        this.party = party;
        reload();
    }

    /**
     * The values to compare with: fields that differ get marked. Null marks nothing.
     */
    void setBaseline(Party baseline) {
        this.baseline = baseline;
        bindings.forEach(this::mark);
    }

    private void mark(Binding b) {
        boolean modified = party != null && baseline != null
                && !Outline.same(b.getter.apply(party), b.getter.apply(baseline));
        Outline.apply(b.field, false, modified);
    }

    /**
     * Shows the values of the party again, e.g. after it was changed from outside.
     */
    void reload() {
        updating = true;
        try {
            for (Binding b : bindings) {
                String v = party == null ? null : b.getter.apply(party);
                b.field.setText(v == null ? "" : v);
                b.field.setCaretPosition(0);
            }
        } finally {
            updating = false;
        }
        bindings.forEach(this::mark);
        updateEnabled();
    }

    private void updateEnabled() {
        for (Binding b : bindings) {
            boolean ok = b.group == null || supported.test(b.group);
            b.field.setEnabled(party != null && ok);
            b.field.setToolTipText(ok ? b.toolTip : I18n.t("party.notInProfile"));
        }
    }

    private JTextField field(InvoiceData.Field group, int columns, String toolTip, Function<Party, String> getter,
                             BiConsumer<Party, String> setter) {
        JTextField f = new JTextField(columns);
        Binding b = new Binding(f, group, toolTip, getter, setter);
        bindings.add(b);
        f.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                changed(b);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changed(b);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changed(b);
            }
        });
        return f;
    }

    private void changed(Binding b) {
        if (updating || party == null)
            return;
        String v = b.field.getText();
        b.setter.accept(party, v.isBlank() ? null : v);
        mark(b);
        if (onChange != null)
            onChange.run();
    }
}
