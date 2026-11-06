package com.bw.pdfixit.ui;

import javax.swing.JScrollPane;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;

/**
 * A scroll pane that is not narrower than its view needs, so a {@link javax.swing.JSplitPane} can't move its divider
 * over it. (A plain scroll pane has a tiny minimum size, because it can scroll.) The view scrolls only
 * vertically. The window must not be smaller than the minimum, see {@link UI#updateMinimumWindowSize()}.<br>
 * The width is computed from the current preferred size of the view, so it follows font changes.
 */
final class ProtectedScrollPane extends JScrollPane {

    ProtectedScrollPane(Component view) {
        // Only vertical: the width is protected (see getMinimumSize), there is nothing to scroll sideways.
        super(view, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER);
    }

    @Override
    public Dimension getMinimumSize() {
        Dimension d = super.getMinimumSize();
        Component view = getViewport().getView();
        if (view == null)
            return d;
        Insets in = getInsets();
        int width = view.getPreferredSize().width + in.left + in.right;
        // The vertical scroll bar takes room beside the view, if it is there.
        if (getVerticalScrollBar().isVisible())
            width += getVerticalScrollBar().getPreferredSize().width;
        return new Dimension(Math.max(d.width, width), d.height);
    }
}
