package com.bw.pdfixit.ui;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.Log;
import com.bw.pdfixit.Preferences;
import com.bw.pdfixit.model.Page;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Panel to show the images inside a page.
 */
public final class PageImageViewer extends JPanel {

    private final java.awt.List resourceList = new java.awt.List();
    private final Map<String, Page.ImageResource> imageMap = new LinkedHashMap<>();
    private final ImageWidget image = new ImageWidget();
    private boolean showCombined = false;
    private Page page;
    private BufferedImage combinedImage;
    private final JCheckBox combined = new JCheckBox(I18n.t("images.combined"));

    private final JButton saveAll = new JButton(I18n.t("images.saveAll"));
    private final JButton saveOne = new JButton(I18n.t("images.save"));


    public PageImageViewer() {
        super(new BorderLayout());

        combined.setToolTipText(
                I18n.t("images.combined.tip"));

        JPanel left = new JPanel(new BorderLayout());
        left.add(resourceList, BorderLayout.CENTER);


        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING));
        buttons.add(saveOne);
        buttons.add(saveAll);
        buttons.add(combined);
        left.add(buttons, BorderLayout.SOUTH);

        combined.addItemListener(e -> setShowCombined(combined.isSelected()));

        JSplitPane splitter = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitter.setLeftComponent(left);
        splitter.setRightComponent(image);


        image.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                BufferedImage i = image.getImage();
                if (i != null) {
                    double scalex = ((double) image.getWidth() / i.getWidth());
                    double scaley = ((double) image.getHeight() / i.getHeight());
                    image.setScale(Math.min(scalex, scaley));
                }
            }
        });
        image.setPreferredSize(new Dimension(400, 500));

        add(splitter, BorderLayout.CENTER);

        resourceList.addItemListener(e -> {
            if (!combined.isSelected()) {
                showImageByName(resourceList.getSelectedItem());
            }
        });

        saveAll.addActionListener(e -> {
            JFileChooser chooser = UI.getImageChooser();
            chooser.setFileFilter(null);
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int result = chooser.showSaveDialog(this);
            try {
                if (result == JFileChooser.APPROVE_OPTION) {
                    File selectedFolder = chooser.getSelectedFile();
                    if (selectedFolder.isDirectory()) {
                        Preferences.getInstance().set(Preferences.USER_PREF_LAST_IMAGE_DIR, selectedFolder.getAbsolutePath());
                        for (var v : imageMap.values()) {
                            saveImage(new File(selectedFolder, v.name + ".png").toString(), v.image);
                        }
                        if (combinedImage == null) {
                            combinedImage = getCombined();
                        }
                        if (combinedImage != null)
                            saveImage(new File(selectedFolder, "combined.png").toString(), combinedImage);
                    }
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), I18n.t("images.storeFailed"), JOptionPane.ERROR_MESSAGE);
            }
        });

        saveOne.addActionListener(e -> {
            BufferedImage i = image.getImage();
            if (i == null)
                return;
            String name = image.getImageName();
            if (name == null)
                name = "image";

            JFileChooser chooser = UI.getImageChooser();
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setSelectedFile(new File(name + ".png"));

            int result = chooser.showSaveDialog(this);
            try {
                if (result == JFileChooser.APPROVE_OPTION) {
                    File selectedFile = chooser.getSelectedFile().getAbsoluteFile();
                    if (!selectedFile.isDirectory()) {
                        Preferences.getInstance().set(Preferences.USER_PREF_LAST_IMAGE_DIR, selectedFile.getParent());
                        saveImage(selectedFile.toString(), i);
                    }
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), I18n.t("images.storeFailed"), JOptionPane.ERROR_MESSAGE);
            }

        });
    }

    private void saveImage(String name, BufferedImage image) throws IOException {

        String ext;
        int extIndex = name.lastIndexOf(".");
        if (extIndex < 0) {
            name = name + ".png";
            ext = "png";
        } else {
            ext = name.substring(extIndex + 1).toLowerCase(Locale.CANADA);
        }
        File selectedFile = new File(name);
        if (selectedFile.exists()) {
            if (!UI.askOverwrite(this, selectedFile.toPath())) {
                return;
            }
        }
        ImageIO.write(image, ext, selectedFile);
        Log.info("Written %s", selectedFile);
    }


    private void setImages(List<Page.ImageResource> images) {
        resourceList.removeAll();
        imageMap.clear();
        for (var image : images) {
            imageMap.put(image.name, image);
        }
        for (var name : imageMap.keySet()) {
            resourceList.add(name);
        }
        showImageByName(null);
        combinedImage = null;
        setShowCombined(false);
        saveAll.setEnabled(!images.isEmpty());
    }

    /**
     * Shows an image by its resource name.
     *
     * @param imageName The name. Can be null.
     */
    private void showImageByName(String imageName) {
        if (imageName != null) {
            saveOne.setEnabled(true);
            image.setAlternativeText(imageName);
            Page.ImageResource i = imageMap.get(imageName);
            if (i != null) {
                if (i.image != null) {
                    showImage(i.image);
                    image.setImageName(i.name);
                } else if (i.error != null) {
                    image.setAlternativeText(i.error);
                    image.setImage(null);
                } else {
                    image.setAlternativeText(I18n.t("images.unsupported"));
                    image.setImage(null);
                }
                return;
            }
        }
        showImage(null);
    }

    private void showImage(BufferedImage bimage) {
        if (bimage == null) {
            saveOne.setEnabled(false);
            image.setAlternativeText(imageMap.isEmpty() ? I18n.t("images.none") : I18n.t("images.select"));
        } else {
            saveOne.setEnabled(true);
            double scalex = ((double) image.getWidth() / bimage.getWidth());
            double scaley = ((double) image.getHeight() / bimage.getHeight());
            image.setScale(Math.min(scalex, scaley));
        }
        image.setImage(bimage);

    }

    private void setShowCombined(boolean showCombined) {
        if (showCombined != this.showCombined) {
            this.showCombined = showCombined;
            this.combined.setSelected(showCombined);
            resourceList.setEnabled(!showCombined);
            if (showCombined) {
                if (combinedImage == null) {
                    combinedImage = getCombined();
                }
                if (combinedImage == null)
                    image.setAlternativeText(I18n.t("images.failed"));
                showImage(combinedImage);
                image.setImageName("combined");
            } else {
                showImageByName(resourceList.getSelectedItem());
            }
        }
    }

    public BufferedImage getCombined() {

        BufferedImage combined;
        Graphics2D combinedG;

        double minX = Integer.MAX_VALUE;
        double minY = Integer.MAX_VALUE;
        double maxX = Integer.MIN_VALUE;
        double maxY = Integer.MIN_VALUE;

        double scaleXMin = Integer.MAX_VALUE;
        double scaleYMin = Integer.MAX_VALUE;

        for (var v : imageMap.values()) {

            if (scaleXMin > v.scaleX)
                scaleXMin = v.scaleX;
            if (scaleYMin > v.scaleY)
                scaleYMin = v.scaleY;

            double x = v.x;
            double y = v.y;
            if (x < minX)
                minX = x;
            if (y < minY)
                minY = y;
            double v2 = x + (v.image.getWidth() * v.scaleX);
            if (v2 > maxX)
                maxX = v2;
            v2 = y + (v.image.getHeight() * v.scaleY);
            if (v2 > maxY)
                maxY = v2;
        }

        if (scaleXMin < 0.1 || scaleYMin < 0.1)
            return null;


        double w = (maxX - minX) / scaleXMin;
        double h = (maxY - minY) / scaleYMin;

        combined = new BufferedImage((int) w, (int) h, BufferedImage.TYPE_INT_ARGB);
        combinedG = combined.createGraphics();

        combinedG.setRenderingHints(Map.of(
                RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON
        ));

        for (var v : imageMap.values()) {
            combinedG.drawImage(v.image,
                    AffineTransform.getTranslateInstance(((v.x - minX) / v.scaleX), ((v.y - minY) / v.scaleY)), null);
        }
        combinedG.dispose();

        return combined;
    }

    /**
     * Sets the page to show images from.
     *
     * @param page The page. Must not be null.
     */
    public void setPage(Page page) {
        this.page = page;

        setImages(page.getImages());
    }
}
