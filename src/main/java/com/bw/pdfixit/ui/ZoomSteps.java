package com.bw.pdfixit.ui;

/**
 * The zoom steps of the views of pages (the redaction dialog, the preview of the pages) in percent. 100 % is one pixel
 * per point of the page (72 dpi).
 */
final class ZoomSteps {

    static final int[] STEPS = {50, 75, 100, 150, 200, 300, 400};

    private ZoomSteps() {
    }

    static int smallest() {
        return STEPS[0];
    }

    static int largest() {
        return STEPS[STEPS.length - 1];
    }

    /**
     * The next zoom step. A zoom is a step, a fitted page is between two steps: the step comes after a small distance.
     *
     * @param zoom    The zoom in percent, 0 if the page is fitted to the window.
     * @param current What is shown now in percent: the zoom, or what the fitted page is.
     * @param max     The biggest step that is offered.
     * @param bigger  True for the next bigger, false for the next smaller step.
     * @return The step, or the zoom if there is no such step.
     */
    static int next(int zoom, int current, int max, boolean bigger) {
        int result = zoom;
        for (int step : STEPS) {
            if (step > max)
                break;
            if (bigger && step > current + 1)
                return step;
            if (!bigger && step < current - 1)
                result = step;
        }
        return result;
    }
}
