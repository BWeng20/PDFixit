package com.bw.jPdfTool.ui.invoice;

import com.bw.jPdfTool.I18n;
import com.bw.jPdfTool.ui.CollapsibleTextPanel;
import com.bw.jPdfTool.ui.UI;

import com.bw.jPdfTool.invoice.EInvoice;
import com.bw.jPdfTool.invoice.InvoiceData;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shows the main data of an e-invoice in a readable form, the XML in an expandable text area below.
 */
public class InvoiceDialog extends JDialog {

    private static final Map<String, String> PROFILES = Map.of(
            "MINIMUM", "Minimum",
            "BASICWL", "Basic WL",
            "BASIC WL", "Basic WL",
            "BASIC", "Basic",
            "COMFORT", "EN 16931 (Comfort)",
            "EN16931", "EN 16931 (Comfort)",
            "EXTENDED", "Extended",
            "XRECHNUNG", "XRechnung");

    private static final Color AMOUNT_RULE = new Color(128, 128, 128, 90);

    private final Locale locale = Locale.getDefault();

    private InvoiceDialog(Window owner, byte[] xml, EInvoice.Summary summary, String error) {
        super(owner, I18n.t("common.invoice"), Dialog.ModalityType.APPLICATION_MODAL);
        JPanel main = new JPanel(new BorderLayout(0, 12));
        main.setBorder(BorderFactory.createEmptyBorder(14, 14, 10, 14));

        JPanel center = new JPanel(new BorderLayout(0, 12));
        // The data block follows the width of the dialog.
        center.add(summary == null ? createError(error) : createSummary(summary), BorderLayout.NORTH);
        // Without readable data, the XML is all there is: show it directly.
        center.add(new CollapsibleTextPanel(InvoiceData.prettyXml(xml), I18n.t("xml.show"), I18n.t("xml.hide"), summary == null),
                BorderLayout.CENTER);

        JButton close = new JButton(I18n.t("about.close"));
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
        buttons.add(close);
        getRootPane().setDefaultButton(close);

        main.add(center, BorderLayout.CENTER);
        main.add(buttons, BorderLayout.SOUTH);
        setContentPane(main);
        pack();
        // Values shrink with "…", but keep the dialog usable.
        setMinimumSize(new Dimension(280, 0));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    /**
     * Shows the dialog (modal).
     *
     * @param xml The invoice XML.
     */
    public static void showDialog(Component parent, byte[] xml) {
        EInvoice.Summary summary = null;
        String error = null;
        try {
            summary = EInvoice.summarize(xml);
            if (!hasData(summary)) {
                // Mustang doesn't fail on XML that is no invoice, it simply finds nothing.
                summary = null;
                error = I18n.t("invoice.noData");
            }
        } catch (Exception e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        Window owner = parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        InvoiceDialog dialog = new InvoiceDialog(owner, xml, summary, error);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /**
     * Checks if the summary contains anything that identifies an invoice.
     */
    static boolean hasData(EInvoice.Summary s) {
        return s != null && (s.id() != null || s.seller() != null || s.buyer() != null || s.amount() != null);
    }

    private JComponent createError(String error) {
        JLabel label = new JLabel(I18n.t("invoice.cannotRead", escape(error)));
        label.setIcon(UIManager.getIcon("OptionPane.warningIcon"));
        label.setIconTextGap(10);
        return label;
    }

    private JComponent createSummary(EInvoice.Summary s) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.anchor = GridBagConstraints.WEST;
        gc.gridy = 0;

        // Head: spans both columns and follows the width.
        gc.gridx = 0;
        gc.gridwidth = 2;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(0, 0, 0, 0);
        panel.add(createHead(s), gc);
        gc.gridwidth = 1;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;

        // Parties and dates.
        gc.gridy++;
        gc.insets = new Insets(14, 0, 0, 0);
        panel.add(spacer(), gc);
        addRow(panel, gc, I18n.t("invoice.from"), s.seller());
        addRow(panel, gc, I18n.t("invoice.to"), s.buyer());
        addRow(panel, gc, I18n.t("invoice.issueDate"), formatDate(s.issueDate(), locale));
        addRow(panel, gc, I18n.t("invoice.dueDate"), formatDate(s.dueDate(), locale));
        addRow(panel, gc, I18n.t("invoice.reference"), s.reference());

        // Amounts, right aligned at the right edge of the value column.
        boolean hasNet = s.netAmount() != null;
        boolean hasTax = s.taxAmount() != null;
        if (hasNet || hasTax || s.amount() != null) {
            gc.gridy++;
            gc.insets = new Insets(10, 0, 4, 0);
            panel.add(spacer(), gc);
            addAmountRow(panel, gc, I18n.t("invoice.netAmount"), formatAmount(s.netAmount(), s.currency(), locale), false);
            addAmountRow(panel, gc, I18n.t("invoice.vat"), formatAmount(s.taxAmount(), s.currency(), locale), false);
            if (hasNet || hasTax) {
                gc.gridy++;
                gc.gridx = 1;
                gc.fill = GridBagConstraints.HORIZONTAL;
                gc.insets = new Insets(3, 0, 3, 0);
                JSeparator rule = new JSeparator();
                rule.setForeground(AMOUNT_RULE);
                panel.add(rule, gc);
                gc.fill = GridBagConstraints.NONE;
            }
            addAmountRow(panel, gc, I18n.t("invoice.amountDue"), formatAmount(s.amount(), s.currency(), locale), true);
        }

        // Payment.
        gc.gridy++;
        gc.insets = new Insets(8, 0, 0, 0);
        panel.add(spacer(), gc);
        addRow(panel, gc, "IBAN", formatIban(s.iban()));
        addRow(panel, gc, I18n.t("invoice.paymentTerms"), s.paymentTerms());
        return panel;
    }

    /**
     * Icon on the left. Right of it the document type and number at the top of the icon,
     * the profile (type of the e-invoice) on the bottom line of the icon.
     */
    private static JComponent createHead(EInvoice.Summary s) {
        JPanel head = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();

        // The icon spans both rows, so the rows together are as high as the icon.
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridheight = 2;
        gc.anchor = GridBagConstraints.NORTHWEST;
        gc.insets = new Insets(0, 0, 0, 10);
        head.add(new JLabel(UI.getIcon("invoice")), gc);
        gc.gridheight = 1;

        JLabel title = new JLabel(documentType(s.documentCode()) + (s.id() == null ? "" : " " + s.id()),
                SwingConstants.LEADING);
        Font font = title.getFont();
        title.setFont(font.deriveFont(Font.BOLD, font.getSize2D() * 1.4f));
        gc.gridx = 1;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(0, 0, 0, 0);
        head.add(title, gc);

        // Second row gets the remaining height of the icon: the profile sits on its bottom line.
        gc.gridy = 1;
        gc.weighty = 1;
        gc.anchor = GridBagConstraints.SOUTHWEST;
        if (s.profile() != null) {
            JLabel profile = new JLabel(I18n.t("invoice.profileLine", profileName(s.profile())),
                    SwingConstants.LEADING);
            profile.setForeground(UIManager.getColor("Label.disabledForeground"));
            head.add(profile, gc);
        } else {
            head.add(spacer(), gc);
        }
        return head;
    }

    private static JComponent spacer() {
        return new JLabel();
    }

    private static JLabel caption(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(UIManager.getColor("Label.disabledForeground"));
        return l;
    }

    private static void addRow(JPanel panel, GridBagConstraints gc, String caption, String value) {
        if (value == null)
            return;
        gc.gridy++;
        gc.gridx = 0;
        gc.anchor = GridBagConstraints.NORTHWEST;
        gc.insets = new Insets(3, 0, 3, 18);
        panel.add(caption(caption), gc);
        gc.gridx = 1;
        gc.insets = new Insets(3, 0, 3, 0);
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel(value, SwingConstants.LEADING), gc);
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        gc.anchor = GridBagConstraints.WEST;
    }

    private static void addAmountRow(JPanel panel, GridBagConstraints gc, String caption, String value, boolean total) {
        if (value == null)
            return;
        gc.gridy++;
        gc.gridx = 0;
        gc.insets = new Insets(2, 0, 2, 18);
        JLabel c = caption(caption);
        panel.add(c, gc);
        gc.gridx = 1;
        gc.insets = new Insets(2, 0, 2, 0);
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        JLabel v = new JLabel(value, SwingConstants.RIGHT);
        if (total) {
            Font f = v.getFont();
            v.setFont(f.deriveFont(Font.BOLD, f.getSize2D() * 1.2f));
            c.setForeground(UIManager.getColor("Label.foreground"));
            c.setFont(c.getFont().deriveFont(Font.BOLD));
        }
        panel.add(v, gc);
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        gc.anchor = GridBagConstraints.WEST;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>");
    }

    /**
     * Name of the document type (UNTDID 1001 code), e.g. "380" is "Invoice".
     */
    static String documentType(String code) {
        if (code == null)
            return I18n.t("invoice.type.380");
        String key = "invoice.type." + code.trim();
        return I18n.has(key) ? I18n.t(key) : I18n.t("invoice.type.unknown", code);
    }

    /**
     * Readable profile name, e.g. "COMFORT" is "EN 16931 (Comfort)".
     */
    static String profileName(String profile) {
        if (profile == null)
            return null;
        String name = PROFILES.get(profile.trim().toUpperCase(Locale.ROOT));
        return name == null ? profile : name;
    }

    /**
     * Formats a date of the invoice ("yyyyMMdd", format 102) for the locale.
     *
     * @return The formatted date, the original text if it has another format, null if missing.
     */
    static String formatDate(String date, Locale locale) {
        if (date == null)
            return null;
        try {
            LocalDate d = LocalDate.parse(date.trim(), DateTimeFormatter.BASIC_ISO_DATE);
            return d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale));
        } catch (Exception e) {
            return date;
        }
    }

    /**
     * Formats an amount with its currency for the locale, e.g. "119.00" and "EUR" is "119,00 €" in Germany.
     *
     * @return The formatted amount, a simple combination if it can't be parsed, null if missing.
     */
    static String formatAmount(String amount, String currency, Locale locale) {
        if (amount == null)
            return null;
        try {
            BigDecimal value = new BigDecimal(amount.trim());
            if (currency == null)
                return NumberFormat.getNumberInstance(locale).format(value);
            NumberFormat nf = NumberFormat.getCurrencyInstance(locale);
            Currency c = Currency.getInstance(currency.trim());
            nf.setCurrency(c);
            nf.setMinimumFractionDigits(Math.max(c.getDefaultFractionDigits(), 0));
            nf.setMaximumFractionDigits(Math.max(Math.max(c.getDefaultFractionDigits(), 0), value.scale()));
            return nf.format(value);
        } catch (Exception e) {
            return currency == null ? amount : amount + " " + currency;
        }
    }

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern IBAN_BLOCK = Pattern.compile("(.{4})(?!$)");

    /**
     * Groups an IBAN in blocks of four characters.
     */
    static String formatIban(String iban) {
        if (iban == null)
            return null;
        String compact = WHITESPACE.matcher(iban).replaceAll("");
        return IBAN_BLOCK.matcher(compact).replaceAll("$1 ");
    }
}
