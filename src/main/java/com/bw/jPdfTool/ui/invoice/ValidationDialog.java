package com.bw.jPdfTool.ui.invoice;

import com.bw.jPdfTool.I18n;
import com.bw.jPdfTool.ui.CollapsibleTextPanel;
import com.bw.jPdfTool.invoice.EInvoice;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;

/**
 * Shows the result of an e-invoice validation: the verdict in bold with an indicator icon,
 * the details (messages and the complete report) in an expandable text area below.
 */
public class ValidationDialog extends JDialog {

    private final CollapsibleTextPanel details;

    private ValidationDialog(Window owner, EInvoice.Validation validation) {
        super(owner, I18n.t("validation.title"), Dialog.ModalityType.APPLICATION_MODAL);
        details = new CollapsibleTextPanel(detailText(validation), I18n.t("details.show"), I18n.t("details.hide"), false);
        setContentPane(createContent(validation));
        pack();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    /**
     * Shows the dialog (modal).
     */
    public static void showDialog(Component parent, EInvoice.Validation validation) {
        Window owner = parent instanceof Window w ? w : javax.swing.SwingUtilities.getWindowAncestor(parent);
        ValidationDialog dialog = new ValidationDialog(owner, validation);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /**
     * Counts errors and warnings of the validation messages.
     *
     * @return [errors, warnings]
     */
    static int[] count(EInvoice.Validation validation) {
        int errors = 0;
        int warnings = 0;
        for (String m : validation.messages()) {
            if (m.regionMatches(true, 0, "warning", 0, 7))
                ++warnings;
            else
                ++errors;
        }
        return new int[]{errors, warnings};
    }

    /**
     * Text of the second line, e.g. "2 errors, 1 warning".
     */
    static String summary(EInvoice.Validation validation) {
        int[] c = count(validation);
        if (c[0] == 0 && c[1] == 0)
            return validation.valid() ? I18n.t("validation.none") : I18n.t("validation.seeDetails");
        return I18n.t("validation.counts", c[0], c[1]);
    }

    private JPanel createContent(EInvoice.Validation validation) {
        JPanel main = new JPanel(new BorderLayout(0, 10));
        main.setBorder(BorderFactory.createEmptyBorder(12, 12, 10, 12));

        // Verdict: indicator icon left of the bold result.
        JLabel result = new JLabel(validation.valid()
                ? I18n.t("validation.valid")
                : I18n.t("validation.invalid"));
        // Standard icons of the look and feel, as in message dialogs. Swing has no "OK" icon, information is the closest.
        result.setIcon(UIManager.getIcon(validation.valid() ? "OptionPane.informationIcon" : "OptionPane.errorIcon"));
        result.setIconTextGap(10);
        Font font = result.getFont();
        result.setFont(font.deriveFont(Font.BOLD, font.getSize2D() * 1.3f));

        JLabel summary = new JLabel(summary(validation));
        // Align with the text of the result, not with the icon.
        summary.setBorder(BorderFactory.createEmptyBorder(0,
                result.getIcon() == null ? 0 : result.getIcon().getIconWidth() + result.getIconTextGap(), 0, 0));

        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        result.setAlignmentX(LEFT_ALIGNMENT);
        summary.setAlignmentX(LEFT_ALIGNMENT);
        head.add(result);
        head.add(Box.createVerticalStrut(4));
        head.add(summary);

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.add(head, BorderLayout.NORTH);
        center.add(details, BorderLayout.CENTER);

        JButton close = new JButton(I18n.t("about.close"));
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
        buttons.add(close);
        getRootPane().setDefaultButton(close);

        main.add(center, BorderLayout.CENTER);
        main.add(buttons, BorderLayout.SOUTH);
        return main;
    }

    private static String detailText(EInvoice.Validation validation) {
        StringBuilder sb = new StringBuilder();
        if (!validation.messages().isEmpty()) {
            validation.messages().forEach(m -> sb.append(m).append('\n'));
            sb.append('\n');
        }
        sb.append(I18n.t("validation.report")).append(validation.report());
        return sb.toString();
    }
}
