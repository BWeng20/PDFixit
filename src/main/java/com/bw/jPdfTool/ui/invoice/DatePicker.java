package com.bw.jPdfTool.ui.invoice;

import com.bw.jPdfTool.I18n;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A calendar to pick a day: shows one month, with buttons to go to the previous or next month or year.
 * The week starts on the first day of the week of the locale.
 */
class DatePicker extends JPanel {
    private static final int WEEKS = 6;

    private final DateTimeFormatter titleFormat;
    private final Consumer<LocalDate> onPick;
    private final JLabel title = new JLabel("", SwingConstants.CENTER);
    private final DayButton[] days = new DayButton[WEEKS * 7];
    private final DayOfWeek firstDay;
    private YearMonth shown;
    private final LocalDate selected;

    /**
     * @param selected The marked day, null if none. Its month is shown first, else the current month.
     * @param onPick   Called with the day the user clicked.
     */
    DatePicker(Locale locale, LocalDate selected, Consumer<LocalDate> onPick) {
        super(new BorderLayout(0, 4));
        this.onPick = onPick;
        this.selected = selected;
        titleFormat = DateTimeFormatter.ofPattern("LLLL yyyy", locale);
        firstDay = WeekFields.of(locale).getFirstDayOfWeek();
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel previous = new JPanel(new GridLayout(1, 2, 2, 0));
        previous.setOpaque(false);
        previous.add(navigation("«", I18n.t("date.prevYear"), -12));
        previous.add(navigation("‹", I18n.t("date.prevMonth"), -1));
        JPanel next = new JPanel(new GridLayout(1, 2, 2, 0));
        next.setOpaque(false);
        next.add(navigation("›", I18n.t("date.nextMonth"), 1));
        next.add(navigation("»", I18n.t("date.nextYear"), 12));
        JPanel header = new JPanel(new BorderLayout(4, 0));
        header.setOpaque(false);
        header.add(previous, BorderLayout.WEST);
        header.add(title, BorderLayout.CENTER);
        header.add(next, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(WEEKS + 1, 7, 1, 1));
        grid.setOpaque(false);
        Color weak = UIManager.getColor("Label.disabledForeground");
        for (int i = 0; i < 7; ++i) {
            JLabel l = new JLabel(firstDay.plus(i).getDisplayName(TextStyle.SHORT_STANDALONE, locale), SwingConstants.CENTER);
            l.setForeground(weak);
            grid.add(l);
        }
        for (int i = 0; i < days.length; ++i) {
            days[i] = new DayButton();
            grid.add(days[i]);
        }
        add(grid, BorderLayout.CENTER);

        JButton today = new JButton(I18n.t("date.today"));
        today.putClientProperty("JButton.buttonType", "toolBarButton");
        today.addActionListener(e -> onPick.accept(LocalDate.now()));
        add(today, BorderLayout.SOUTH);

        showMonth(YearMonth.from(selected == null ? LocalDate.now() : selected));
    }

    private JButton navigation(String text, String toolTip, int months) {
        JButton b = new JButton(text);
        b.setToolTipText(toolTip);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setMargin(new Insets(2, 6, 2, 6));
        b.addActionListener(e -> showMonth(shown.plusMonths(months)));
        return b;
    }

    /**
     * Shows the month, with the days of the neighbour months to fill the weeks.
     */
    private void showMonth(YearMonth month) {
        shown = month;
        title.setText(month.atDay(1).format(titleFormat));
        LocalDate day = month.atDay(1).with(TemporalAdjusters.previousOrSame(firstDay));
        LocalDate today = LocalDate.now();
        for (DayButton b : days) {
            b.set(day, YearMonth.from(day).equals(month), day.equals(selected), day.equals(today));
            day = day.plusDays(1);
        }
        repaint();
    }

    private final class DayButton extends JButton {
        private LocalDate date;
        private boolean marked;
        private boolean today;

        DayButton() {
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setMargin(new Insets(2, 2, 2, 2));
            setPreferredSize(new Dimension(32, 26));
            addActionListener(e -> onPick.accept(date));
        }

        void set(LocalDate date, boolean inMonth, boolean marked, boolean today) {
            this.date = date;
            this.marked = marked;
            this.today = today;
            setText(Integer.toString(date.getDayOfMonth()));
            setForeground(UIManager.getColor(marked ? "List.selectionForeground" : inMonth ? "Label.foreground" : "Label.disabledForeground"));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color accent = UIManager.getColor("Component.accentColor");
                if (accent == null) {
                    accent = UIManager.getColor("List.selectionBackground");
                }
                if (marked) {
                    g2.setColor(accent);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                } else if (getModel().isRollover()) {
                    g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 60));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                }
                if (today && !marked) {
                    g2.setColor(accent);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                }
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }
}
