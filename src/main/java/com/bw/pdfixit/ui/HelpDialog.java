package com.bw.pdfixit.ui;

import com.bw.pdfixit.I18n;

import javax.swing.JEditorPane;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.Component;
import java.awt.Dimension;

/**
 * Shows a help text (HTML) in a message dialog, optionally in justified alignment ("Blocksatz").
 */
public final class HelpDialog {

    private static final int JUSTIFIED_WIDTH_IN_EM = 48;

    /**
     * The body text is a little larger than the font, like {@code <font size='+1'>} in the help texts.
     */
    private static final double BODY_SCALE = 1.15;

    private HelpDialog() {
    }

    /**
     * Shows the help text of a message key.
     *
     * @param justified True to justify the text. Swing justifies only lines that are wrapped by itself, so the line
     *                  breaks of the text are turned into spaces; a blank line ({@code <br><br>}) ends a paragraph and starts the next one.
     */
    public static void show(Component parent, String textKey, boolean justified) {
        String title = I18n.t("common.help");
        String text = I18n.t(textKey);
        if (!justified) {
            JOptionPane.showMessageDialog(parent, text, title, JOptionPane.INFORMATION_MESSAGE);
        } else {
            JEditorPane pane = new JEditorPane();
            pane.setEditable(false);
            pane.setOpaque(false);
            pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            pane.setFont(UIManager.getFont("Label.font"));
            pane.setForeground(UIManager.getColor("Label.foreground"));
            // The size of the body text: a font tag would end with its paragraph. An own style sheet keeps the default
            // one, which is shared by all editor panes, unchanged.
            HTMLEditorKit kit = new HTMLEditorKit();
            StyleSheet styles = new StyleSheet();
            styles.addStyleSheet(kit.getStyleSheet());
            styles.addRule("body, p, li { font-size: " + Math.round(pane.getFont().getSize() * BODY_SCALE) + "px; }");
            kit.setStyleSheet(styles);
            pane.setEditorKit(kit);
            pane.setText(toParagraphs(text));
            SimpleAttributeSet justify = new SimpleAttributeSet();
            StyleConstants.setAlignment(justify, StyleConstants.ALIGN_JUSTIFIED);
            StyledDocument doc = (StyledDocument) pane.getDocument();
            justify(doc, doc.getDefaultRootElement(), justify);
            // The height depends on the width: fix the width, then ask for the height.
            int width = pane.getFontMetrics(pane.getFont()).getHeight() * JUSTIFIED_WIDTH_IN_EM;
            pane.setSize(new Dimension(width, Short.MAX_VALUE));
            pane.setPreferredSize(new Dimension(width, pane.getPreferredSize().height));
            JOptionPane.showMessageDialog(parent, pane, title, JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * Makes real paragraphs of the text. A blank line ({@code <br><br>}) ends a paragraph, a line break is a space.
     * Lists are no part of a paragraph, otherwise Swing lays them out wrongly. The font tag of the body text is
     * removed, see {@link #BODY_SCALE}.
     */
    private static String toParagraphs(String text) {
        return text.replace("<font size='+1'>", "").replace("</font></html>", "</html>")
                .replace("<br><br>", "</p><p>").replace("<br>", " ")
                .replaceAll("(?i)(<[uo]l>)", "</p>$1").replaceAll("(?i)(</[uo]l>)", "$1<p>");
    }

    /**
     * Justifies the text, but not the items of lists: they inherit the alignment of the paragraph that contains the
     * list, and Swing lays out justified list items wrongly. The items stay left aligned.
     */
    private static void justify(StyledDocument doc, Element element, SimpleAttributeSet justify) {
        if (element.getParentElement() == null)
            doc.setParagraphAttributes(0, doc.getLength(), justify, false);
        if (element.getName().equals("li")) {
            SimpleAttributeSet left = new SimpleAttributeSet();
            StyleConstants.setAlignment(left, StyleConstants.ALIGN_LEFT);
            doc.setParagraphAttributes(element.getStartOffset(), element.getEndOffset() - element.getStartOffset(),
                    left, false);
            return;
        }
        for (int i = 0; i < element.getElementCount(); i++)
            justify(doc, element.getElement(i), justify);
    }
}
