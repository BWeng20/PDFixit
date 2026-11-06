package com.bw.pdfixit.ui.wizard;

import com.bw.pdfixit.ui.UI;

/**
 * A task that starts from a goal. A wizard is a plain class that builds its {@link WizardStep}s, owns their model and
 * shows them in a {@link WizardDialog}. The ones the user can choose are listed in {@link Wizards}.
 */
public interface Wizard {

    /**
     * The name, shown on the start page and in the menu.
     */
    String title();

    /**
     * One sentence about what the wizard does, shown on the start page.
     */
    String description();

    /**
     * The class path resource of the symbol, an SVG, e.g. "svg/card-layout".
     */
    String icon();

    /**
     * Runs the wizard in a modal dialog. A generated PDF is handed to the window with
     * {@link UI#openGenerated(byte[])}.
     */
    void start(UI ui);
}
