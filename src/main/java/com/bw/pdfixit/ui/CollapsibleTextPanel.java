package com.bw.pdfixit.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Window;

/**
 * A read-only, scrollable text that can be expanded and collapsed by a button with an arrow.
 * The window is resized (packed) on each change.
 */
public class CollapsibleTextPanel extends JPanel {

    private final JButton toggle = new JButton();
    private final JScrollPane scrollPane;
    private final String showText;
    private final String hideText;

    /**
     * @param text     The text, shown in a monospaced font.
     * @param showText Label of the button if collapsed.
     * @param hideText Label of the button if expanded.
     * @param expanded Initial state.
     */
    public CollapsibleTextPanel(String text, String showText, String hideText, boolean expanded) {
        super(new BorderLayout(0, 4));
        this.showText = showText;
        this.hideText = hideText;

        JTextArea area = new JTextArea(text, 20, 90);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        area.setCaretPosition(0);
        scrollPane = new JScrollPane(area);

        toggle.setHorizontalAlignment(SwingConstants.LEADING);
        toggle.setBorderPainted(false);
        toggle.setContentAreaFilled(false);
        toggle.setFocusPainted(false);
        toggle.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        toggle.addActionListener(e -> setExpanded(!isExpanded()));

        add(toggle, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        updateState(expanded);
    }

    public boolean isExpanded() {
        return scrollPane.isVisible();
    }

    /**
     * Expands or collapses the text and adapts the height of the window.
     * A width the user has set is kept, the window only gets wider if the content needs it.
     */
    public void setExpanded(boolean expanded) {
        updateState(expanded);
        Window w = SwingUtilities.getWindowAncestor(this);
        if (w != null) {
            int width = w.getWidth();
            w.pack();
            if (width > w.getWidth())
                w.setSize(width, w.getHeight());
        }
    }

    private void updateState(boolean expanded) {
        scrollPane.setVisible(expanded);
        toggle.setText(expanded ? hideText : showText);
        toggle.setIcon(UIManager.getIcon(expanded ? "Tree.expandedIcon" : "Tree.collapsedIcon"));
    }
}
