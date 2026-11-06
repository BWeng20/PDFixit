package com.bw.pdfixit.ui.wizard;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.ui.UI;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The page the main window shows while no document is open: a card with an SVG symbol for each wizard and for the
 * layout editor, and a hint how to open a PDF.
 */
public class StartPage extends JPanel {

    /**
     * A card of the page.
     *
     * @param title       The name.
     * @param description One sentence about what it does.
     * @param icon        The class path resource of the symbol, e.g. "svg/card-layout".
     * @param action      What happens when the user clicks the card.
     */
    public record Card(String title, String description, String icon, Runnable action) {
    }

    public StartPage(java.util.List<Card> cards) {
        super(new BorderLayout());
        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new javax.swing.BoxLayout(center, javax.swing.BoxLayout.Y_AXIS));

        JLabel hint = new JLabel(I18n.t("start.hint"), SwingConstants.CENTER);
        hint.setAlignmentX(CENTER_ALIGNMENT);
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC));
        JLabel head = new JLabel(I18n.t("start.wizards"), SwingConstants.CENTER);
        head.setAlignmentX(CENTER_ALIGNMENT);
        head.setFont(head.getFont().deriveFont(Font.BOLD, head.getFont().getSize2D() * 1.3f));
        center.add(hint);
        center.add(javax.swing.Box.createVerticalStrut(24));
        center.add(head);
        center.add(javax.swing.Box.createVerticalStrut(12));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 8));
        row.setOpaque(false);
        for (Card card : cards)
            row.add(createCard(card));
        row.setAlignmentX(CENTER_ALIGNMENT);
        center.add(row);

        // Centered in the window, whatever its size.
        JPanel holder = new JPanel(new java.awt.GridBagLayout());
        holder.setOpaque(false);
        holder.add(center);
        add(holder, BorderLayout.CENTER);
    }

    private static JPanel createCard(Card card) {
        final Color normal = UIManager.getColor("Panel.background");
        final Color hover = UIManager.getColor("Button.hoverBackground") != null
                ? UIManager.getColor("Button.hoverBackground") : normal;
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") != null
                        ? UIManager.getColor("Component.borderColor") : Color.GRAY),
                BorderFactory.createEmptyBorder(14, 14, 14, 14)));
        Icon icon = UI.getIcon(card.icon());
        JLabel symbol = new JLabel(icon, SwingConstants.CENTER);
        JLabel title = new JLabel(card.title(), SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        // The text wraps inside the card.
        JLabel text = new JLabel("<html><div style='text-align:center;width:" + 190 + "px'>"
                + escape(card.description()) + "</div></html>", SwingConstants.CENTER);
        JPanel texts = new JPanel(new BorderLayout(0, 4));
        texts.setOpaque(false);
        texts.add(title, BorderLayout.NORTH);
        texts.add(text, BorderLayout.CENTER);
        panel.add(symbol, BorderLayout.NORTH);
        panel.add(texts, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(panel.getPreferredSize().width + 20, panel.getPreferredSize().height));
        panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        panel.setToolTipText(card.description());
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                card.action().run();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                panel.setBackground(hover);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                panel.setBackground(normal);
            }
        });
        return panel;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
