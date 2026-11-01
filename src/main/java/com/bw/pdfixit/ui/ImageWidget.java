package com.bw.pdfixit.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * Widget to show an image. Scales automatically to current size.
 */
public class ImageWidget extends JComponent {

    private final Border selectedBorder;
    private final Border notSelectedBorder;
    private BufferedImage image;
    private String imageName;
    private boolean selected = false;
    private RenderingHints renderingHints = new RenderingHints(null);


    public void setAlternativeText(String alternativeText) {
        if (!Objects.equals(this.alternativeText, alternativeText)) {
            this.alternativeText = alternativeText;
            repaint();
        }
    }

    private String alternativeText;
    private double scale = 0;

    public void setScale(double scale) {
        if (this.scale != scale) {
            this.scale = scale;
            repaint();
        }
    }

    public double getScale() {
        return scale;
    }


    public ImageWidget() {
        Color c = UIManager.getColor("Button.foreground");
        selectedBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(c, 2), BorderFactory.createEmptyBorder(2, 2, 2, 2));
        notSelectedBorder = BorderFactory.createEmptyBorder(4, 4, 4, 4);
        setBorder(notSelectedBorder);
        updateUI();
    }

    public BufferedImage getImage() {
        return image;
    }

    public String getImageName() {
        return imageName;
    }

    public void setImageName(String image) {
        setToolTipText(image == null ? "" : image);
        imageName = image;
    }

    public void setImage(BufferedImage image) {
        if (image != this.image) {
            this.image = image;
            this.imageName = null;
            repaint();
        }
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        if (this.selected != selected) {
            this.selected = selected;
            setBorder(selected ? selectedBorder : notSelectedBorder);
        }

    }

    @Override
    public void updateUI() {
        Font f = UIManager.getFont("Panel.font");
        setFont(f);
        FontMetrics fm = getFontMetrics(f);
        Dimension d = new Dimension(fm.charWidth('W') * 10, fm.getHeight() * 6);
        setMinimumSize(d);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2d = (Graphics2D) g;
        g2d.setPaint(getBackground());

        Insets i = getBorder().getBorderInsets(this);

        int w = getWidth() - i.left - i.right;
        int h = getHeight() - i.top - i.bottom;
        g.fillRect(0, 0, w, h);


        if (image == null) {
            if (alternativeText != null) {
                FontMetrics fm = g2d.getFontMetrics();
                var bounds = fm.getStringBounds(alternativeText, g2d);
                g2d.setPaint(getForeground());
                g2d.drawString(alternativeText, (int) (0.5 + (w - bounds.getWidth()) / 2), (h - fm.getAscent()) / 2);
            }

        } else {
            int imgWidth = image.getWidth();
            int imgHeight = image.getHeight();

            g2d.setRenderingHints(renderingHints);

            int drawWidth = (int) (imgWidth * scale);
            int drawHeight = (int) (imgHeight * scale);

            int x = i.left + (w - drawWidth) / 2;
            int y = i.top + (h - drawHeight) / 2;

            if (g2d.hitClip(x, y, drawWidth, drawHeight))
                g2d.drawImage(image, x, y, drawWidth, drawHeight, null);
        }
    }

    public void setRenderingHints(RenderingHints hints) {
        renderingHints = hints;
    }
}
