package com.bw.pdfixit.ui.wizard;

import javax.swing.JComponent;
import java.util.List;

/**
 * One page of a wizard. The wizard that builds its steps owns the model they work on.
 */
public interface WizardStep {

    /**
     * The name of the step in the list of steps.
     */
    String title();

    /**
     * What the step shows. The same component is returned each time.
     */
    JComponent component();

    /**
     * What prevents the user from going on, as texts for the user. Empty if the step is complete. Asked from time to
     * time while the step is shown, so it must be cheap.
     */
    List<String> problems();

    /**
     * Called each time the step is shown, to fill it from what the steps before have changed.
     */
    void onEnter();

    /**
     * If the step is left out for now, e.g. because of an earlier choice. Next and Back go past it.
     */
    default boolean skipped() {
        return false;
    }
}
