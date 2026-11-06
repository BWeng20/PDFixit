package com.bw.pdfixit.ui.invoice;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.invoice.BusinessTerms;

import javax.swing.JComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * The help texts of the business terms ({@link BusinessTerms.Help}) as tool tip or as page for the help area.
 */
final class TermHelp {

    /**
     * Codes shown in the help area. Longer lists are cut.
     */
    static final int MAX_CODES = 60;
    private static final int TOOLTIP_WIDTH = 380;

    private TermHelp() {
    }

    static List<BusinessTerms.Help> of(String... ids) {
        List<BusinessTerms.Help> result = new ArrayList<>();
        for (String id : ids) {
            BusinessTerms.Help h = BusinessTerms.help(id);
            if (h != null)
                result.add(h);
        }
        return result;
    }

    /**
     * Sets the tool tip of a field of the editor: its own tool tip followed by the help of the business terms.
     *
     * @return The field.
     */
    static <T extends JComponent> T apply(T field, String... ids) {
        field.setToolTipText(combine(field.getToolTipText(), ids));
        return field;
    }

    /**
     * Shows the help of the business terms as tool tips of the column headers.
     *
     * @param idsPerColumn The ids for each column, null or empty for a column without help.
     */
    static void applyToHeader(javax.swing.JTable table, String[]... idsPerColumn) {
        String[] tips = new String[idsPerColumn.length];
        for (int i = 0; i < tips.length; i++)
            tips[i] = idsPerColumn[i] == null ? null : tooltip(of(idsPerColumn[i]));
        javax.swing.table.JTableHeader header = new javax.swing.table.JTableHeader(table.getColumnModel()) {
            @Override
            public String getToolTipText(java.awt.event.MouseEvent e) {
                int column = columnAtPoint(e.getPoint());
                int model = column < 0 ? -1 : table.convertColumnIndexToModel(column);
                return model >= 0 && model < tips.length ? tips[model] : null;
            }
        };
        header.setReorderingAllowed(false);
        table.setTableHeader(header);
        // Tool tips are only shown for registered components.
        javax.swing.ToolTipManager.sharedInstance().registerComponent(header);
    }

    /**
     * A tool tip of the editor followed by the help of the business terms.
     *
     * @param toolTip The own tool tip, may be null or HTML.
     * @return The tool tip, the own one if there is no help.
     */
    static String combine(String toolTip, String... ids) {
        List<BusinessTerms.Help> help = of(ids);
        if (help.isEmpty())
            return toolTip;
        StringBuilder sb = new StringBuilder();
        if (toolTip != null && !toolTip.isBlank()) {
            sb.append(toolTip.replaceAll("(?i)</?html>", ""));
            sb.append("<hr>");
        }
        // Tells the hint of the program from the text of the specification.
        sb.append("<font color='").append(grey()).append("'>").append(escape(I18n.t("help.fromSpec")))
                .append("</font><br>");
        appendShort(sb, help, false);
        return wrap(sb);
    }

    /**
     * The tool tip of a value of the XML: names, use, occurrence and format.
     *
     * @return The tool tip, null if there is no help.
     */
    static String tooltip(List<BusinessTerms.Help> help) {
        if (help.isEmpty())
            return null;
        StringBuilder sb = new StringBuilder();
        appendShort(sb, help, true);
        return wrap(sb);
    }

    private static String wrap(CharSequence body) {
        return "<html><table width='" + TOOLTIP_WIDTH + "' cellpadding='0' cellspacing='0'><tr><td>" + body
                + "</td></tr></table></html>";
    }

    private static void appendShort(StringBuilder sb, List<BusinessTerms.Help> help, boolean withFormat) {
        boolean first = true;
        for (BusinessTerms.Help h : help) {
            if (!first)
                sb.append("<br><br>");
            first = false;
            sb.append("<b>").append(escape(title(h))).append("</b>");
            if (h.use() != null)
                sb.append("<br>").append(escape(h.use())).append(english(h.useEnglish()));
            String occurrence = occurrence(h);
            if (occurrence != null)
                sb.append("<br><i>").append(escape(occurrence)).append("</i>");
            if (withFormat && format(h) != null)
                sb.append("<br>").append(escape(I18n.t("help.label.format"))).append(": ").append(escape(format(h)));
        }
    }

    /**
     * The full help for the help area: use, note, occurrence, format, codes and rules.
     *
     * @return HTML for a JEditorPane.
     */
    static String page(List<BusinessTerms.Help> help) {
        StringBuilder sb = new StringBuilder("<html><body>");
        if (help.isEmpty())
            sb.append("<p>").append(escape(I18n.t("help.none"))).append("</p>");
        for (BusinessTerms.Help h : help) {
            sb.append("<p><b>").append(escape(title(h))).append("</b></p>");
            if (h.use() != null)
                sb.append("<p>").append(escape(h.use())).append(english(h.useEnglish())).append("</p>");
            if (h.note() != null)
                sb.append("<p>").append(escape(h.note())).append(english(h.noteEnglish())).append("</p>");
            sb.append("<table cellpadding='1' cellspacing='0'>");
            row(sb, I18n.t("help.label.occurrence"), occurrence(h));
            row(sb, I18n.t("help.label.format"), format(h));
            sb.append("</table>");
            for (BusinessTerms.CodeList list : h.codeLists())
                appendCodes(sb, list);
            if (!h.rules().isEmpty()) {
                sb.append("<p><i>").append(escape(I18n.t("help.label.rules"))).append("</i><br>");
                for (String rule : h.rules())
                    sb.append(escape(rule)).append("<br>");
                sb.append("</p>");
            }
        }
        if (!help.isEmpty()) {
            // Required by the permission of CEN and DIN for derivative use of the standard.
            sb.append("<p><font color='").append(grey()).append("'>").append(escape(I18n.t("help.source")))
                    .append("</font></p>");
        }
        return sb.append("</body></html>").toString();
    }

    private static void appendCodes(StringBuilder sb, BusinessTerms.CodeList list) {
        sb.append("<p><i>").append(escape(I18n.t("help.label.codes"))).append(": ").append(escape(list.name()))
                .append("</i>");
        if (list.codes().isEmpty()) {
            sb.append("<br>").append(escape(I18n.t("help.codes.long", list.count()))).append("</p>");
            return;
        }
        sb.append("</p><table cellpadding='1' cellspacing='0'>");
        int n = Math.min(MAX_CODES, list.codes().size());
        for (int i = 0; i < n; i++) {
            String[] c = list.codes().get(i);
            sb.append("<tr><td valign='top'><code>").append(escape(c[0])).append("</code>&nbsp;&nbsp;</td><td>")
                    .append(escape(c[1])).append("</td></tr>");
        }
        sb.append("</table>");
        if (list.codes().size() > n)
            sb.append("<p>").append(escape(I18n.t("help.codes.more", list.codes().size() - n))).append("</p>");
        if (!"en".equals(I18n.getLocale().getLanguage()))
            sb.append("<p><i>").append(escape(I18n.t("help.codes.english"))).append("</i></p>");
    }

    private static void row(StringBuilder sb, String caption, String value) {
        if (value == null || value.isBlank())
            return;
        sb.append("<tr><td valign='top'><i>").append(escape(caption)).append(":</i>&nbsp;&nbsp;</td><td>")
                .append(escape(value)).append("</td></tr>");
    }

    static String title(BusinessTerms.Help h) {
        return h.name() == null ? h.id() : h.id() + " " + h.name();
    }

    /**
     * E.g. "Mandatory (1..1)", null if the cardinality is unknown.
     */
    static String occurrence(BusinessTerms.Help h) {
        if (h.cardinality() == null)
            return null;
        String s = I18n.t(h.isMandatory() ? "help.card.mandatory" : "help.card.optional", h.cardinality());
        return h.isRepeatable() ? s + I18n.t("help.card.repeatable") : s;
    }

    /**
     * How the value is written in the XML, null if the type is unknown.
     */
    static String format(BusinessTerms.Help h) {
        String key = "help.format." + h.type();
        return h.type() == null || !I18n.has(key) ? null : I18n.t(key);
    }

    /**
     * The color of disabled text of the look and feel, readable in light and dark themes.
     */
    private static String grey() {
        java.awt.Color c = javax.swing.UIManager.getColor("Label.disabledForeground");
        return c == null ? "#808080" : String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String english(boolean english) {
        return english ? " <i>" + escape(I18n.t("help.english")) + "</i>" : "";
    }

    static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
