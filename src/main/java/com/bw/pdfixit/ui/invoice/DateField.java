package com.bw.pdfixit.ui.invoice;

import com.bw.pdfixit.I18n;
import com.bw.pdfixit.ui.UI;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Edits a date in three fields for day, month and year. The fields are in the order of the date format of the
 * locale and separated like it, e.g. "dd.MM.yyyy" in Germany or "M/d/yyyy" in the US.<br>
 * Typing is restricted to digits in the range of the field. A field is left automatically when no further digit
 * is possible, or when the separator is typed. The cursor keys leave a field at its border.<br>
 * A button next to the fields opens a calendar to pick the date, another one clears the date.
 */
public class DateField extends JPanel {

    private enum Part {
        DAY(2, "dd"), MONTH(2, "MM"), YEAR(4, "yyyy");

        final int length;
        final String placeholder;

        Part(int length, String placeholder) {
            this.length = length;
            this.placeholder = placeholder;
        }
    }

    private enum State {EMPTY, VALID, INVALID}

    private final Locale locale;
    private final DateTimeFormatter pasteFormat;
    private final JButton pickerButton = new JButton(UI.getIcon("calendar"));
    private final JButton clearButton = new JButton(UI.getIcon("close"));
    private final List<PartField> fields = new ArrayList<>();
    private final List<JLabel> separators = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();
    /**
     * True while the fields are set by the program: no checks, no moving, no events.
     */
    private boolean updating;
    private boolean modified;

    public DateField(Locale locale) {
        super(new GridBagLayout());
        setOpaque(false);
        this.locale = locale;
        pasteFormat = parseFormatter(locale);
        build(pattern(locale));
        if (fields.size() != 3) {
            removeAll();
            fields.clear();
            separators.clear();
            column = 0;
            build("yyyy-MM-dd");
        }
        pickerButton.setToolTipText(I18n.t("date.pick"));
        pickerButton.putClientProperty("JButton.buttonType", "toolBarButton");
        pickerButton.addActionListener(e -> openPicker());
        pickerButton.setMargin(new Insets(2, 2, 2, 2));
        put(pickerButton, 0, 3, 0);
        clearButton.setToolTipText(I18n.t("date.clear"));
        clearButton.putClientProperty("JButton.buttonType", "toolBarButton");
        clearButton.addActionListener(e -> {
            setDate(null);
            fields.get(0).requestFocusInWindow();
        });
        clearButton.setMargin(new Insets(2, 2, 2, 2));
        clearButton.setEnabled(false);
        put(clearButton, 0, 0, 0);
    }

    /**
     * Shows the calendar below the button. The picked day is set and closes the calendar.
     */
    private void openPicker() {
        JPopupMenu popup = new JPopupMenu();
        popup.add(new DatePicker(locale, getDate(), date -> {
            popup.setVisible(false);
            setDate(date);
            fields.get(0).requestFocusInWindow();
        }));
        popup.show(pickerButton, 0, pickerButton.getHeight());
    }

    // ---------------------------------------------------------------- component

    /**
     * Creates the fields and separators for the pattern.
     */
    private void build(String pattern) {
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); ++i) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                int end = pattern.indexOf('\'', i + 1);
                if (end < 0) {
                    end = pattern.length();
                }
                literal.append(pattern, i + 1, end);
                i = end;
                continue;
            }
            Part part = partOf(c);
            if (part == null) {
                literal.append(c);
                continue;
            }
            while (i + 1 < pattern.length() && pattern.charAt(i + 1) == c) {
                ++i;
            }
            addSeparator(literal);
            PartField f = new PartField(part);
            fields.add(f);
            // The fields share the free width of the panel, the year gets more than day and month.
            put(f, part.length, 0, 0);
        }
        addSeparator(literal);
    }

    /**
     * Next grid column, see {@link #put}.
     */
    private int column;

    /**
     * Adds the component right of the previous ones. A weight above 0 lets it grow with the panel.
     */
    private void put(Component c, double weight, int left, int right) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = column++;
        gc.weightx = weight;
        gc.fill = weight > 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(0, left, 0, right);
        add(c, gc);
    }

    private void addSeparator(StringBuilder literal) {
        if (literal.isEmpty()) {
            return;
        }
        JLabel l = new JLabel(literal.toString(), SwingConstants.CENTER);
        separators.add(l);
        put(l, 0, 3, 3);
        literal.setLength(0);
    }

    private static Part partOf(char c) {
        return switch (c) {
            case 'd' -> Part.DAY;
            case 'M', 'L' -> Part.MONTH;
            case 'y', 'u' -> Part.YEAR;
            default -> null;
        };
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        fields.forEach(f -> f.setEnabled(enabled));
        separators.forEach(s -> s.setEnabled(enabled));
        pickerButton.setEnabled(enabled);
        updateClearButton();
    }

    /**
     * The clear button is only useful for an enabled field with content.
     */
    private void updateClearButton() {
        clearButton.setEnabled(isEnabled() && !isEmpty());
    }

    @Override
    public void setToolTipText(String text) {
        super.setToolTipText(text);
        fields.forEach(f -> f.setToolTipText(text));
        separators.forEach(s -> s.setToolTipText(text));
    }

    // ---------------------------------------------------------------- value

    /**
     * Sets the date, null clears the fields. Notifies the listeners once.
     */
    public void setDate(LocalDate date) {
        updating = true;
        try {
            for (PartField f : fields) {
                f.setText(date == null ? "" : switch (f.part) {
                    case DAY -> String.format("%02d", date.getDayOfMonth());
                    case MONTH -> String.format("%02d", date.getMonthValue());
                    case YEAR -> String.format("%04d", date.getYear());
                });
            }
        } finally {
            updating = false;
        }
        changed();
    }

    /**
     * @return The date, null if the fields are empty or don't contain a valid date.
     */
    public LocalDate getDate() {
        int day = number(Part.DAY);
        int month = number(Part.MONTH);
        int year = number(Part.YEAR);
        if (day < 1 || month < 1 || year < 1000) {
            return null;
        }
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /**
     * @return True if all fields are empty.
     */
    public boolean isEmpty() {
        return state() == State.EMPTY;
    }

    /**
     * @return False if the fields are filled partly or don't contain a valid date, e.g. 31.02.
     */
    public boolean isInputValid() {
        return state() != State.INVALID;
    }

    /**
     * Called when the content changed, by the user or by {@link #setDate}.
     */
    public void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    private PartField field(Part part) {
        for (PartField f : fields) {
            if (f.part == part) {
                return f;
            }
        }
        throw new IllegalStateException(part.name());
    }

    /**
     * @return The number in the field, -1 if empty.
     */
    private int number(Part part) {
        String t = field(part).getText();
        return t.isEmpty() ? -1 : Integer.parseInt(t);
    }

    private State state() {
        if (fields.stream().allMatch(f -> f.getText().isEmpty())) {
            return State.EMPTY;
        }
        return getDate() == null ? State.INVALID : State.VALID;
    }

    private void changed() {
        updateClearButton();
        showError();
        listeners.forEach(Runnable::run);
    }

    /**
     * Marks the fields if the input is wrong. Not while the user is typing, the input is likely incomplete.
     */
    private void showError() {
        boolean typing = fields.stream().anyMatch(JComponent::isFocusOwner);
        boolean error = state() == State.INVALID && !typing;
        fields.forEach(f -> Outline.apply(f, error, modified));
    }

    /**
     * Marks the fields if the date differs from the original. An error is shown before that.
     */
    public void setModified(boolean modified) {
        this.modified = modified;
        showError();
    }

    /**
     * @return The highest day the month allows, depending on the month and year if they are given.
     */
    private int maxDay() {
        int month = number(Part.MONTH);
        if (month < 1 || month > 12) {
            return 31;
        }
        int year = field(Part.YEAR).getText().length() == Part.YEAR.length ? number(Part.YEAR) : -1;
        return year > 0 ? YearMonth.of(year, month).lengthOfMonth() : Month.of(month).maxLength();
    }

    private int maxValue(Part part) {
        return switch (part) {
            case DAY -> maxDay();
            case MONTH -> 12;
            case YEAR -> 9999;
        };
    }

    /**
     * Checks the text a field would have after typing.
     */
    private boolean acceptable(Part part, String text) {
        if (text.length() > part.length) {
            return false;
        }
        if (part == Part.YEAR) {
            return text.charAt(0) != '0';
        }
        // A single 0 is the start of 01 to 09.
        int value = Integer.parseInt(text);
        return (value >= 1 || text.equals("0")) && value <= maxValue(part);
    }

    /**
     * @return True if no digit can follow the text.
     */
    private boolean complete(Part part, String text) {
        return text.length() == part.length || Integer.parseInt(text) * 10 > maxValue(part);
    }

    // ---------------------------------------------------------------- date format

    /**
     * Date pattern of the locale with a four-digit year, e.g. "dd.MM.yyyy" in Germany.
     */
    static String pattern(Locale locale) {
        return DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null,
                IsoChronology.INSTANCE, locale).replaceAll("y+", "yyyy");
    }

    /**
     * Formatter for the pattern of the locale, where day and month may have one or two digits.
     */
    static DateTimeFormatter parseFormatter(Locale locale) {
        String pattern = pattern(locale).replaceAll("d+", "d").replaceAll("M+", "M");
        return DateTimeFormatter.ofPattern(pattern, locale);
    }

    /**
     * Parses a date in the format of the locale or as ISO date ("2024-10-01" or "20241001").
     *
     * @return The date or null if it can't be parsed.
     */
    static LocalDate parseDate(String text, DateTimeFormatter localFormat) {
        String t = text.trim();
        for (DateTimeFormatter f : new DateTimeFormatter[]{localFormat, DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.BASIC_ISO_DATE}) {
            try {
                return LocalDate.parse(t, f);
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- input

    private final class PartField extends JTextField {
        final Part part;
        /**
         * Caret to set when the field gets the focus (0 or -1 for the end), null to select all.
         */
        private Integer pendingCaret;

        PartField(Part part) {
            super(part.length);
            this.part = part;
            setHorizontalAlignment(SwingConstants.CENTER);
            putClientProperty("JTextField.placeholderText", part.placeholder);
            ((AbstractDocument) getDocument()).setDocumentFilter(new Filter());
            getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    edited();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    edited();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    edited();
                }
            });
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    SwingUtilities.invokeLater(() -> {
                        if (pendingCaret == null) {
                            selectAll();
                        } else {
                            setCaretPosition(pendingCaret < 0 ? getText().length() : 0);
                            pendingCaret = null;
                        }
                    });
                }

                @Override
                public void focusLost(FocusEvent e) {
                    pad();
                    SwingUtilities.invokeLater(DateField.this::showError);
                }
            });
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getModifiersEx() != 0 || getSelectionStart() != getSelectionEnd()) {
                        return;
                    }
                    int key = e.getKeyCode();
                    if ((key == KeyEvent.VK_BACK_SPACE && getText().isEmpty()) || (key == KeyEvent.VK_LEFT && getCaretPosition() == 0)) {
                        move(-1);
                        e.consume();
                    } else if (key == KeyEvent.VK_RIGHT && getCaretPosition() == getText().length()) {
                        move(1);
                        e.consume();
                    }
                }
            });
        }

        private void edited() {
            if (!updating) {
                changed();
            }
        }

        /**
         * Adds the leading zero of day and month.
         */
        private void pad() {
            if (part != Part.YEAR && getText().length() == 1) {
                updating = true;
                try {
                    setText("0" + getText());
                } finally {
                    updating = false;
                }
            }
        }

        /**
         * Moves the focus to the next or previous field, if there is one. The caret is at the border to the field
         * the focus came from.
         */
        void move(int direction) {
            int i = fields.indexOf(this) + direction;
            if (i >= 0 && i < fields.size()) {
                fields.get(i).pendingCaret = direction < 0 ? -1 : 0;
                fields.get(i).requestFocusInWindow();
            }
        }

        /**
         * Moves to the next field with the content selected.
         */
        private void advance() {
            int i = fields.indexOf(this) + 1;
            if (i < fields.size()) {
                fields.get(i).pendingCaret = null;
                fields.get(i).requestFocusInWindow();
            }
        }

        /**
         * Pastes a complete date into all fields.
         */
        @Override
        public void paste() {
            try {
                String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
                LocalDate date = parseDate(text, pasteFormat);
                if (date != null) {
                    setDate(date);
                    return;
                }
            } catch (Exception ignored) {
                // no text: paste the usual way
            }
            super.paste();
        }

        private final class Filter extends DocumentFilter {
            @Override
            public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
                replace(fb, offset, 0, string, attr);
            }

            @Override
            public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
                if (updating || text == null || text.isEmpty()) {
                    super.replace(fb, offset, length, text, attrs);
                    return;
                }
                String old = fb.getDocument().getText(0, fb.getDocument().getLength());
                if (text.chars().allMatch(c -> c >= '0' && c <= '9')) {
                    String result = old.substring(0, offset) + text + old.substring(offset + length);
                    if (!acceptable(part, result)) {
                        return;
                    }
                    super.replace(fb, offset, length, text, attrs);
                    if (offset + text.length() == result.length() && complete(part, result)) {
                        advance();
                    }
                } else if (text.length() == 1 && !Character.isLetter(text.charAt(0)) && old.length() > length) {
                    // The separator ends the input of the field.
                    advance();
                }
            }
        }
    }
}
