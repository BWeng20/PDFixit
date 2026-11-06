package com.bw.pdfixit.ui.template;

import com.bw.pdfixit.ui.AreaCanvas;
import com.bw.pdfixit.ui.ViewArea;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The page of the layout editor: the template page (with the preview of the sample invoice) and the regions of the
 * layout. Each kind of region has its own look, and the name of its element is written at its top.
 */
class LayoutCanvas extends AreaCanvas {

    /**
     * What a region of the canvas shows.
     */
    enum Kind {
        /**
         * The body region: the flowing parts are laid out there.
         */
        BODY(new Color(0x2f6fd0), true),
        /**
         * The rectangle of a fixed element.
         */
        FIELD(new Color(0x2e9a4a), true),
        /**
         * The own rectangle of a moved label of a fixed field.
         */
        LABEL_RECT(new Color(0xd9822b), true),
        /**
         * A label at its default place, as the preview drew it; moving it gives it its own rectangle.
         */
        LABEL_PLACED(new Color(0xd9822b), false),
        /**
         * The label of a flowing part, as the preview drew it; moving it changes its offset and position.
         */
        FLOW_LABEL(new Color(0xd9822b), false),
        /**
         * The value of a flowing part, as the preview drew it: it can be selected and removed.
         */
        FLOW_VALUE(new Color(0x7a7a7a), false);

        final Color color;
        final boolean resizable;

        Kind(Color color, boolean resizable) {
            this.color = color;
            this.resizable = resizable;
        }
    }

    private final List<Kind> kinds = new ArrayList<>();
    private final List<String> names = new ArrayList<>();

    LayoutCanvas() {
        setRotationEnabled(false);
        // Enables the tool tips, see getToolTipText(MouseEvent).
        setToolTipText("");
    }

    /**
     * The name of the region under the mouse: the names are written only at the body regions and the selected region,
     * so the small regions of a block (e.g. the information block) don't hide each other.
     */
    @Override
    public String getToolTipText(MouseEvent e) {
        int i = areaAt(e.getPoint());
        return i >= 0 && i < names.size() ? names.get(i) : null;
    }

    /**
     * Sets the regions with their kinds and names (parallel lists).
     */
    void setRegions(List<ViewArea> areas, List<Kind> kinds, List<String> names) {
        this.kinds.clear();
        this.kinds.addAll(kinds);
        this.names.clear();
        this.names.addAll(names);
        setAreas(areas);
    }

    @Override
    protected boolean isResizable(int index) {
        return index < kinds.size() && kinds.get(index).resizable;
    }

    @Override
    protected void paintArea(Graphics2D g2, int index, ViewArea frame, boolean selected) {
        Kind kind = index < kinds.size() ? kinds.get(index) : Kind.FIELD;
        Shape shape = frame.shape();
        Rectangle2D r = frame.rect();
        g2.setColor(kind.color);
        if (kind != Kind.FLOW_VALUE) {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, kind == Kind.BODY ? 0.06f : 0.14f));
            g2.fill(shape);
            g2.setComposite(AlphaComposite.SrcOver);
        }
        float[] dash = switch (kind) {
            case BODY -> new float[]{6f, 4f};
            case FLOW_VALUE, LABEL_PLACED, FLOW_LABEL -> new float[]{2f, 2f};
            default -> null;
        };
        g2.setStroke(selected ? new BasicStroke(2f)
                : new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f));
        g2.setColor(selected ? Color.RED : kind.color);
        g2.draw(shape);
        // The name above the region (inside if there is no room above), for the regions the user places.
        String name = index < names.size() ? names.get(index) : null;
        if (name != null && (kind == Kind.BODY || selected)) {
            Font font = getFont().deriveFont(Font.PLAIN, Math.max(9f, getFont().getSize2D() - 2));
            g2.setFont(font);
            int ascent = g2.getFontMetrics().getAscent();
            float y = (float) (r.getMinY() > ascent + 2 ? r.getMinY() - 2 : r.getMinY() + ascent);
            g2.setColor(selected ? Color.RED : kind.color.darker());
            g2.drawString(name, (float) r.getMinX() + 1, y);
        }
    }
}
