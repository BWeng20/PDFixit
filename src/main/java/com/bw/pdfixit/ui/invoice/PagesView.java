package com.bw.pdfixit.ui.invoice;

import com.bw.pdfixit.template.Element;
import com.bw.pdfixit.template.Layout;
import com.bw.pdfixit.template.Rect;
import com.bw.pdfixit.template.Region;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The pages of a PDF one below the other, to put in a scroll pane. For a template the regions of a layout are drawn on
 * the pages. Rendering is slow: {@link #render} is meant for a background thread, {@link #setPages} for the event
 * thread.
 */
final class PagesView extends JPanel {

    private static final float DPI = 70;
    private static final int MAX_PAGES = 8;
    private static final Color FIELD = new Color(0x4A7FC1);

    /**
     * The rendered pages.
     *
     * @param images The images of the first pages.
     * @param pages  The number of pages of the document.
     */
    record Rendered(List<BufferedImage> images, int pages) {
    }

    PagesView() {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
    }

    /**
     * Renders the first pages of a PDF.
     */
    static Rendered render(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            List<BufferedImage> images = new ArrayList<>();
            for (int i = 0; i < Math.min(doc.getNumberOfPages(), MAX_PAGES); ++i)
                images.add(renderer.renderImageWithDPI(i, DPI));
            return new Rendered(images, doc.getNumberOfPages());
        }
    }

    /**
     * Shows pages.
     *
     * @param layout The layout whose regions are drawn: the first page gets the regions of the first page, the second
     *               page those of the following pages. Null for none.
     */
    void setPages(Rendered rendered, Layout layout) {
        removeAll();
        for (int i = 0; i < rendered.images().size(); ++i) {
            PageImage page = new PageImage(rendered.images().get(i), layout == null ? List.of() : regions(layout, i));
            page.setAlignmentX(LEFT_ALIGNMENT);
            add(page);
            add(javax.swing.Box.createVerticalStrut(8));
        }
        revalidate();
        repaint();
    }

    /**
     * A text instead of pages.
     */
    void setMessage(String text) {
        removeAll();
        add(new JLabel(text));
        revalidate();
        repaint();
    }

    private static List<Rect> regions(Layout layout, int pageIndex) {
        List<Rect> list = new ArrayList<>();
        boolean next = pageIndex > 0;
        for (Element e : layout.fields)
            if (e.isFixed() && e.next == next)
                list.add(e.rect);
        for (Region r : layout.regions)
            if (r.next() == next)
                list.add(r.rect());
        return list;
    }

    /**
     * One page; the regions are in mm.
     */
    private static final class PageImage extends JComponent {
        private final BufferedImage image;
        private final List<Rect> regions;

        PageImage(BufferedImage image, List<Rect> regions) {
            this.image = image;
            this.regions = regions;
            setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));
            setMaximumSize(new Dimension(image.getWidth(), image.getHeight()));
            setBorder(BorderFactory.createLineBorder(Color.GRAY));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.drawImage(image, 0, 0, null);
            double scale = DPI / 25.4;
            for (Rect r : regions) {
                int x = (int) Math.round(r.x() * scale);
                int y = (int) Math.round(r.y() * scale);
                int w = (int) Math.round(r.width() * scale);
                int h = (int) Math.round(r.height() * scale);
                g2.setComposite(AlphaComposite.SrcOver.derive(0.15f));
                g2.setColor(FIELD);
                g2.fillRect(x, y, w, h);
                g2.setComposite(AlphaComposite.SrcOver);
                g2.drawRect(x, y, w, h);
            }
            g2.dispose();
        }
    }
}
