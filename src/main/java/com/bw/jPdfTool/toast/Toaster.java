package com.bw.jPdfTool.toast;

import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import java.awt.Point;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages Toast messages.
 */
public class Toaster {

    private JLayeredPane layeredPane;
    private final List<Toast> bread = new ArrayList<>();

    public static int HORIZONTAL_GAP = 5;
    public static int VERTICAL_GAP = 5;

    /**
     * Milliseconds a toast should be shown on screen.
     * If user is hovering with the mouse over a toast, it is not automatically closed.
     */
    public static int closeDelayMS = 5000;


    /**
     * Initialize a new instance. Each frame should have its own instance.
     * The owning frame must call {@link Toaster#attachFrame(JFrame)}.
     */
    public Toaster() {
    }

    /**
     * Attacks the toaster to a frame. The toasts will be shown on the layered-pane of the frame.<br>
     * This can be done only once for each Toaster instance.
     *
     * @param frame The frame to attach to.
     */
    public void attachFrame(JFrame frame) {
        if (layeredPane != null)
            throw new IllegalStateException("Toaster is already attached to a frame");
        layeredPane = frame.getLayeredPane();
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                repositionPanels();
            }
        });
    }

    /**
     * Shows a toast.
     *
     * @param type       The type of toast.
     * @param durationMS The time (in milliseconds) to stay on screen.
     * @param message    The message (should be html). Will be used with "format" if arguments are not empty.
     * @param arguments  The arguments for the "format" call.
     */
    public Toast toast(ToastType type, int durationMS, String message, Object... arguments) {
        if (layeredPane != null) {
            final Toast[] toasts = new Toast[1];

            if (arguments.length > 0)
                message = String.format(message, arguments);

            toasts[0] = new Toast(type, durationMS, message, () -> removeNotification(toasts[0]), 300);
            // A new component sits at (0,0) until the fade-in runs, which showed the toast in the upper left corner.
            toasts[0].setLocation(layeredPane.getWidth(), layeredPane.getHeight());
            layeredPane.add(toasts[0], JLayeredPane.POPUP_LAYER);
            bread.add(0, toasts[0]);
            SwingUtilities.invokeLater(() -> {
                animateFadeIn(toasts[0]);
                layeredPane.repaint();
            });

            return toasts[0];
        } else {
            return null;
        }
    }


    /**
     * Shows an informative toast.
     *
     * @param message   The message (should be html). Will be used with "format" if arguments are not empty.
     * @param arguments The arguments for the "format" call.
     */
    public Toast toast(String message, Object... arguments) {
        return toast(ToastType.INFO, Toaster.closeDelayMS, message, arguments);
    }


    private void animateFadeIn(Toast panel) {
        panel.setLocation(layeredPane.getWidth(), layeredPane.getHeight());
        repositionPanels();
    }

    /**
     * Removes all toasts and stops their timers, e.g. before the owning window is disposed.
     */
    public void clear() {
        if (layeredPane == null)
            return;
        for (Toast t : bread) {
            layeredPane.remove(t);
            t.cleanUp();
        }
        bread.clear();
        layeredPane.repaint();
    }

    private void removeNotification(Toast toast) {
        layeredPane.remove(toast);
        bread.remove(toast);
        toast.cleanUp();
        repositionPanels();
        layeredPane.repaint();
    }

    private void animateMove(Toast toast, int x, int y) {
        Point c = toast.getLocation();
        if (c.x == x && c.y == y)
            return;

        toast.animateTo(500, x, y);
    }

    private void repositionPanels() {
        int w = layeredPane.getWidth();
        int y = layeredPane.getHeight();
        for (Toast panel : bread) {
            y -= panel.getHeight() + VERTICAL_GAP;
            animateMove(panel, w - HORIZONTAL_GAP - panel.getWidth(), y);
        }
    }
}