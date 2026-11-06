package com.bw.pdfixit.template;

/**
 * A finding of the template check or of the engine.
 *
 * @param element The name of the element of the layout it refers to (see {@link Element#name()}), null if it refers
 *                to the template or the layout as a whole.
 * @param page    The page of the invoice it refers to (1-based), 0 if it refers to no page.
 */
public record Problem(Severity severity, String message, String element, int page) {

    public enum Severity {
        /**
         * The invoice can't be generated.
         */
        ERROR,
        WARNING,
        INFO
    }

    public Problem(Severity severity, String message, String element) {
        this(severity, message, element, 0);
    }

    @Override
    public String toString() {
        return severity + ": " + message;
    }
}
