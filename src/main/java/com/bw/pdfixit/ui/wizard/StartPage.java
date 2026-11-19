package com.bw.pdfixit.ui.wizard;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.ui.UI;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The page the main window shows while no document is open: a card with an SVG symbol for each wizard and for the
 * layout editor, and a hint how to open a PDF.
 */
public class StartPage extends JPanel {

    private static final int CARD_GAP_H = 16;
    private static final int CARD_GAP_V = 16;

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

        // The cards wrap into as many columns as the width allows; what does not fit in height scrolls.
        JPanel row = new JPanel(new GridLayout(1, cards.size(), CARD_GAP_H, CARD_GAP_V));
        row.setOpaque(false);
        for (Card card : cards)
            row.add(createCard(card));
        row.setAlignmentX(CENTER_ALIGNMENT);
        center.add(row);

        // Centered in the window, whatever its size.
        JPanel holder = new JPanel(new java.awt.GridBagLayout());
        holder.setOpaque(false);
        holder.add(center);
        JScrollPane scroll = new JScrollPane(holder, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getHorizontalScrollBar().setUnitIncrement(16);
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateColumns(row, scroll.getViewport().getWidth());
            }
        });
        add(scroll, BorderLayout.CENTER);
    }

    /**
     * Sets the number of card columns so that the cards fit into the given width.
     */
    private static void updateColumns(JPanel row, int width) {
        int count = row.getComponentCount();
        if (count == 0)
            return;
        int cardWidth = 0;
        for (Component c : row.getComponents())
            cardWidth = Math.max(cardWidth, c.getPreferredSize().width);
        int columns = Math.max(1, Math.min(count, (width + CARD_GAP_H) / (cardWidth + CARD_GAP_H)));
        GridLayout layout = (GridLayout) row.getLayout();
        if (layout.getColumns() != columns) {
            layout.setColumns(columns);
            layout.setRows((count + columns - 1) / columns);
            row.revalidate();
        }
    }

    private static JPanel createCard(Card card) {
        JPanel panel = new JPanel(new BorderLayout(0, 6)) {
            @Override
            public void updateUI() {
                super.updateUI();
                // The border colour belongs to the look and feel, so it is set again after each switch.
                setBorder(cardBorder());
            }
        };
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

            // The colours are looked up on each event, so they follow a switch of the look and feel.
            @Override
            public void mouseEntered(MouseEvent e) {
                Color hover = UIManager.getColor("Button.hoverBackground");
                panel.setBackground(hover != null ? hover : UIManager.getColor("Panel.background"));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                panel.setBackground(UIManager.getColor("Panel.background"));
            }
        });
        return panel;
    }

    private static Border cardBorder() {
        Color line = UIManager.getColor("Component.borderColor");
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(line != null ? line : Color.GRAY),
                BorderFactory.createEmptyBorder(14, 14, 14, 14));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
