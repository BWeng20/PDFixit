package com.bw.pdfixit.ui.wizard;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.ui.HelpDialog;
import com.bw.pdfixit.ui.RoundSvgButton;
import com.bw.pdfixit.ui.UI;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

/**
 * A modal dialog that leads through the steps of a wizard: the list of the steps on the left (it shows where the user
 * is), the current step on the right, and below it the problem line and the buttons. Next and Finish are only enabled
 * if the step has no problems.
 */
public class WizardDialog extends JDialog {

    private final List<WizardStep> steps;
    private final CardLayout cards = new CardLayout();
    private final JPanel stepPanel = new JPanel(cards);
    private final JLabel[] stepLabels;
    private final JLabel problemLabel = new JLabel(" ");
    private final JButton back = new JButton(I18n.t("wizard.back"));
    private final JButton next = new JButton(I18n.t("wizard.next"));
    private final JButton finish = new JButton(I18n.t("wizard.finish"));
    private final JButton cancel = new JButton(I18n.t("wizard.cancel"));
    private final RoundSvgButton help = RoundSvgButton.createHelp();
    private String helpKey;
    /**
     * Asks the step for its problems while the dialog is shown: the step does not tell when something changed.
     */
    private final Timer poll = new Timer(300, e -> updateButtons());
    private int current = -1;
    private boolean finished;

    /**
     * @param owner The window the dialog belongs to.
     * @param title The title of the dialog, the name of the wizard.
     * @param steps At least one step, in the order they are shown.
     */
    public WizardDialog(Window owner, String title, List<WizardStep> steps) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.steps = steps;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                dispose();
            }
        });

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 16));
        stepLabels = new JLabel[steps.size()];
        for (int i = 0; i < steps.size(); ++i) {
            stepLabels[i] = new JLabel((i + 1) + ". " + steps.get(i).title());
            stepLabels[i].setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
            list.add(stepLabels[i]);
            stepPanel.add(steps.get(i).component(), String.valueOf(i));
        }
        list.add(Box.createVerticalGlue());
        stepPanel.setBorder(BorderFactory.createEmptyBorder(12, 4, 8, 12));

        // Help button like in the redaction dialog, only if the wizard has a help text (see setHelp).
        help.setToolTipText(I18n.t("common.help"));
        help.setVisible(false);
        help.addActionListener(e -> HelpDialog.show(this, helpKey, true));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        buttons.add(back);
        buttons.add(next);
        buttons.add(finish);
        buttons.add(cancel);
        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
        bottom.add(problemLabel, BorderLayout.NORTH);
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.add(help, BorderLayout.WEST);
        row.add(buttons, BorderLayout.CENTER);
        bottom.add(row, BorderLayout.CENTER);

        back.addActionListener(e -> goTo(neighbour(-1)));
        next.addActionListener(e -> goTo(neighbour(1)));
        finish.addActionListener(e -> {
            if (finish()) {
                finished = true;
                dispose();
            }
        });
        cancel.addActionListener(e -> dispose());

        add(list, BorderLayout.WEST);
        add(stepPanel, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(next);
        goTo(0);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Called when the user presses Finish, on the last step. The wizard overrides it to do its work (e.g. to generate
     * the PDF and hand it over).
     *
     * @return False to keep the dialog open, e.g. if the work failed or the user declined.
     */
    protected boolean finish() {
        return true;
    }

    /**
     * Shows the dialog and waits until it is closed.
     *
     * @return True if the user finished the wizard, false if he cancelled it.
     */
    public boolean run() {
        poll.start();
        setVisible(true);
        return finished;
    }

    @Override
    public void dispose() {
        poll.stop();
        super.dispose();
    }

    /**
     * The index of the next step in the direction, past the skipped ones; -1 if there is none.
     */
    private int neighbour(int direction) {
        for (int i = current + direction; i >= 0 && i < steps.size(); i += direction)
            if (!steps.get(i).skipped())
                return i;
        return -1;
    }

    /**
     * Shows a step, e.g. the one a problem of the last step refers to.
     */
    public void goTo(int index) {
        if (index < 0 || index >= steps.size())
            return;
        current = index;
        steps.get(index).onEnter();
        cards.show(stepPanel, String.valueOf(index));
        updateButtons();
    }

    /**
     * Shows a help button that opens the help text of a message key.
     */
    public void setHelp(String textKey) {
        helpKey = textKey;
        help.setVisible(textKey != null);
    }

    private void updateButtons() {
        Font normal = UIManager.getFont("Label.font");
        for (int i = 0; i < stepLabels.length; ++i) {
            stepLabels[i].setFont(i == current ? normal.deriveFont(Font.BOLD) : normal);
            stepLabels[i].setEnabled(i <= current && !steps.get(i).skipped());
        }
        List<String> problems = steps.get(current).problems();
        boolean ok = problems.isEmpty();
        if (ok) {
            problemLabel.setIcon(UI.getIcon("svg/status-ok"));
            problemLabel.setText(I18n.t("wizard.stepComplete"));
        } else {
            problemLabel.setIcon(UI.getIcon("svg/status-error"));
            problemLabel.setText(problems.get(0));
        }
        boolean last = current == steps.size() - 1;
        back.setEnabled(neighbour(-1) >= 0);
        next.setVisible(!last);
        next.setEnabled(ok);
        finish.setVisible(last);
        finish.setEnabled(ok);
        getRootPane().setDefaultButton(last ? finish : next);
    }
}
