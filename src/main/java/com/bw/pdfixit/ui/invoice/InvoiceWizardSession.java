package com.bw.pdfixit.ui.invoice;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.invoice.InvoiceData;
import com.bw.pdfixit.invoice.Role;
import com.bw.pdfixit.template.LayoutFiles;
import com.bw.pdfixit.ui.UI;
import com.bw.pdfixit.ui.wizard.WizardDialog;
import com.bw.pdfixit.ui.wizard.WizardStep;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One run of the e-invoice wizard: the invoice with its fields, the choices of the user (what to do, the template and
 * its layout) and the steps. The data steps (seller, buyer, items, payment) are made here from the parts of the
 * invoice editor, the others are classes of their own.<br>
 * The problems of the invoice are calculated after a change (the invoice is processed completely, so a moment after
 * typing) and kept per step: the dialog asks the steps for their problems all the time.
 */
final class InvoiceWizardSession {

    /**
     * What the user wants to do.
     */
    enum Mode {
        /**
         * A new invoice on a template.
         */
        NEW,
        /**
         * The invoice is attached to an existing PDF, whose pages stay as they are.
         */
        ADD,
        /**
         * A new invoice on a template, starting with the data of an XML file.
         */
        XML
    }

    static final int GOAL = 0;
    static final int SELLER = 1;
    static final int BUYER = 2;
    static final int ITEMS = 3;
    static final int PAYMENT = 4;
    static final int TEMPLATE = 5;
    static final int REVIEW = 6;

    /**
     * The keys of the messages of {@link InvoiceData#check()} that belong to a step.
     */
    private static final String[] SELLER_KEYS = {"check.seller.name", "check.seller.city", "check.seller.zip",
            "check.seller.country", "check.sellerTax", "check.xr.contact"};
    private static final String[] BUYER_KEYS = {"check.buyer.name", "check.buyer.city", "check.buyer.zip",
            "check.buyer.country", "check.xr.ref", "check.xr.buyerEmail"};
    private static final String[] ITEMS_KEYS = {"check.number", "check.issueDate.unreadable", "check.issueDate.missing",
            "check.currency", "check.period", "check.noAmounts", "check.noItems", "check.amount.net",
            "check.amount.vat", "check.amount.vatNegative", "check.item.name", "check.item.quantity",
            "check.item.price", "check.item.vat", "check.item.vatNegative", "check.item.unit"};
    private static final String[] PAYMENT_KEYS = {"check.dueOrTerms"};

    final UI ui;
    final InvoiceData data = InvoiceEditor.newInvoice();
    final InvoiceFields fields = new InvoiceFields(data, this::changed);
    final ItemsPanel items = new ItemsPanel(fields);
    Mode mode = Mode.NEW;
    /**
     * The PDF the invoice is attached to, for {@link Mode#ADD}.
     */
    Path existingPdf;
    /**
     * The template and the layout found for it, null before a template was chosen.
     */
    Path template;
    LayoutFiles.Found found;

    private final List<WizardStep> steps = new ArrayList<>();
    private WizardDialog dialog;

    private final JLabel netValue = new JLabel(" ", SwingConstants.RIGHT);
    private final JLabel vatValue = new JLabel(" ", SwingConstants.RIGHT);
    private final JLabel grossValue = new JLabel(" ", SwingConstants.RIGHT);
    private final JLabel bankLine = new JLabel();
    private final Timer updateTimer = new Timer(200, e -> refresh());

    /**
     * The problems of the data per step, and those that belong to no step.
     */
    private final List<List<String>> stepProblems = new ArrayList<>();
    private List<String> otherProblems = List.of();

    InvoiceWizardSession(UI ui) {
        this.ui = ui;
        updateTimer.setRepeats(false);
        for (int i = 0; i <= REVIEW; ++i)
            stepProblems.add(List.of());

        GoalStep goal = new GoalStep(this);
        steps.add(goal);
        steps.add(new DataStep(I18n.t("ui.invoice.role.seller"), SELLER, sellerPanel()));
        steps.add(new DataStep(I18n.t("ui.invoice.role.buyer"), BUYER, buyerPanel()));
        steps.add(new DataStep(I18n.t("wizard.items"), ITEMS, itemsPanel()));
        steps.add(new DataStep(I18n.t("editor.tab.payment"), PAYMENT, paymentPanel()));
        steps.add(new TemplateStep(this));
        steps.add(new ReviewStep(this));
        // Fill the fields from the data.
        fields.reload();
        items.reload();
        items.reloaded();
        refresh();
    }

    /**
     * Runs the wizard.
     */
    void run(Window owner) {
        ReviewStep review = (ReviewStep) steps.get(REVIEW);
        dialog = new WizardDialog(owner, I18n.t("wizard.invoice.title"), steps) {
            @Override
            protected boolean finish() {
                boolean done = review.finish();
                if (done)
                    InvoiceEditor.rememberPreset(fields);
                return done;
            }
        };
        review.setDialog(dialog);
        dialog.setHelp("help.wizard.invoice.text");
        dialog.run();
    }

    WizardDialog dialog() {
        return dialog;
    }

    String stepTitle(int index) {
        return steps.get(index).title();
    }

    // ---------------------------------------------------------------- the data steps

    private JComponent sellerPanel() {
        return withHint(I18n.t("wizard.seller.hint"), fields.party(Role.SELLER));
    }

    private JComponent buyerPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(fields.party(Role.BUYER), BorderLayout.CENTER);
        FormPanel reference = new FormPanel(true);
        reference.row(I18n.t("editor.buyerReference"), fields.buyerReference());
        panel.add(reference, BorderLayout.SOUTH);
        return withHint(I18n.t("wizard.buyer.hint"), panel);
    }

    private JComponent itemsPanel() {
        FormPanel form = new FormPanel(false);
        form.pairRow(I18n.t("editor.number"), fields.number(), I18n.t("editor.currency"), fields.currency(), true);
        // Date fields keep their natural width: two of them in one row.
        form.compactRow(I18n.t("invoice.issueDate"), twoDates(fields.issueDate(), I18n.t("editor.deliveryDate"),
                fields.deliveryDate()));
        form.compactRow(I18n.t("editor.serviceFrom"), twoDates(fields.periodStart(), I18n.t("editor.serviceTo"),
                fields.periodEnd()));
        form.row(I18n.t("editor.note"), fields.note());
        form.row(I18n.t("editor.exemption"), fields.vatExemptionReason());

        JPanel totals = new JPanel(new FlowLayout(FlowLayout.TRAILING, 14, 0));
        totals.add(caption(I18n.t("invoice.netAmount")));
        totals.add(netValue);
        totals.add(caption(I18n.t("invoice.vat")));
        totals.add(vatValue);
        JLabel gross = caption(I18n.t("editor.total"));
        gross.setFont(gross.getFont().deriveFont(Font.BOLD));
        grossValue.setFont(grossValue.getFont().deriveFont(Font.BOLD));
        totals.add(gross);
        totals.add(grossValue);

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(form, BorderLayout.NORTH);
        panel.add(items, BorderLayout.CENTER);
        panel.add(totals, BorderLayout.SOUTH);
        return panel;
    }

    private static JComponent twoDates(JComponent first, String caption, JComponent second) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEADING, 10, 0));
        row.add(first);
        row.add(new JLabel(caption));
        row.add(second);
        return row;
    }

    private JComponent paymentPanel() {
        JPanel bank = new JPanel(new FlowLayout(FlowLayout.LEADING, 10, 0));
        bank.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        bank.add(bankLine);
        JButton edit = new JButton(I18n.t("wizard.payment.editSeller"));
        edit.addActionListener(e -> dialog.goTo(SELLER));
        bank.add(edit);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new PaymentPanel(fields), BorderLayout.CENTER);
        panel.add(bank, BorderLayout.SOUTH);
        return withHint(I18n.t("wizard.payment.hint"), panel);
    }

    private static JComponent withHint(String hint, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        JLabel label = new JLabel(hint);
        label.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        label.setForeground(UIManager.getColor("Label.disabledForeground"));
        panel.add(label, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private static JLabel caption(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(UIManager.getColor("Label.disabledForeground"));
        return l;
    }

    /**
     * A step with the form of the invoice data; it is complete if the data of its own step is.
     */
    private final class DataStep implements WizardStep {
        private final String title;
        private final int index;
        private final JComponent component;

        DataStep(String title, int index, JComponent component) {
            this.title = title;
            this.index = index;
            this.component = component;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public JComponent component() {
            return component;
        }

        @Override
        public List<String> problems() {
            return stepProblems.get(index);
        }

        @Override
        public void onEnter() {
            items.stopEditing();
            refresh();
            if (index == PAYMENT) {
                String iban = data.seller.iban == null || data.seller.iban.isBlank() ? null
                        : InvoiceDialog.formatIban(data.seller.iban);
                bankLine.setText(iban == null ? I18n.t("wizard.payment.noIban") : I18n.t("wizard.payment.iban", iban));
            }
        }
    }

    // ---------------------------------------------------------------- problems

    /**
     * Called after the user changed a value.
     */
    private void changed() {
        updateTimer.restart();
    }

    /**
     * Calculates the sums and the problems of the invoice, and adapts the fields to the profile.
     */
    void refresh() {
        updateTimer.stop();
        String totalsError = null;
        String currency = data.currency == null ? null : data.currency.trim().toUpperCase(Locale.ROOT);
        try {
            InvoiceData.Totals t = data.totals();
            Locale locale = fields.locale;
            netValue.setText(InvoiceDialog.formatAmount(t.net().toPlainString(), currency, locale));
            vatValue.setText(InvoiceDialog.formatAmount(t.vat().toPlainString(), currency, locale));
            grossValue.setText(InvoiceDialog.formatAmount(t.gross().toPlainString(), currency, locale));
        } catch (Exception e) {
            Log.error("Failed to calculate the invoice sums: %s", e);
            totalsError = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
            netValue.setText("–");
            vatValue.setText("–");
            grossValue.setText("–");
        }
        // After the sums: a profile without items fills the amounts from the items.
        fields.updateForProfile();
        items.updateForProfile();

        for (int i = 0; i < stepProblems.size(); ++i)
            stepProblems.set(i, new ArrayList<>());
        List<String> other = new ArrayList<>();
        String dueCaption = I18n.t("invoice.dueDate");
        for (String p : fields.invalidInput())
            stepProblems.get(p.contains(dueCaption) ? PAYMENT : ITEMS).add(p);
        if (totalsError != null)
            stepProblems.get(ITEMS).add(I18n.t("editor.sumsError", totalsError));
        for (String p : data.check()) {
            int step = stepOf(p);
            if (step < 0)
                other.add(p);
            else
                stepProblems.get(step).add(p);
        }
        otherProblems = other;
    }

    /**
     * All problems of the data, for the review: the input that can't be used first.
     */
    List<String> dataProblems() {
        List<String> all = new ArrayList<>();
        for (int i = SELLER; i <= PAYMENT; ++i)
            all.addAll(stepProblems.get(i));
        all.addAll(otherProblems);
        return all;
    }

    /**
     * The step where a problem is fixed, -1 if it belongs to none.
     */
    int stepOfProblem(String message) {
        for (int i = SELLER; i <= PAYMENT; ++i)
            if (stepProblems.get(i).contains(message))
                return i;
        return -1;
    }

    private static int stepOf(String message) {
        if (matchesAny(message, SELLER_KEYS))
            return SELLER;
        if (matchesAny(message, BUYER_KEYS))
            return BUYER;
        if (matchesAny(message, ITEMS_KEYS))
            return ITEMS;
        if (matchesAny(message, PAYMENT_KEYS))
            return PAYMENT;
        return -1;
    }

    private static boolean matchesAny(String message, String[] keys) {
        for (String key : keys) {
            // The text with a marker for each placeholder: the message must start and end like it.
            String[] parts = I18n.t(key, "\u0000").split("\u0000", -1);
            if (parts.length == 1 ? message.equals(parts[0])
                    : message.length() >= parts[0].length() + parts[parts.length - 1].length()
                    && message.startsWith(parts[0]) && message.endsWith(parts[parts.length - 1]))
                return true;
        }
        return false;
    }
}
