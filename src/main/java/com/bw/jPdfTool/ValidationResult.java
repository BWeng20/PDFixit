package com.bw.jPdfTool;

import java.util.List;
import java.util.Objects;

/**
 * The result of a PDF/A verification, see {@link PdfaValidator}: a state and the findings.
 */
public final class ValidationResult {

    /**
     * The outcome of a verification.
     */
    public enum State {
        /**
         * The document conforms to the PDF/A level it declares.
         */
        CONFORM,
        /**
         * The document violates rules of the level it declares, see {@link #getFindings()}.
         */
        NOT_CONFORM,
        /**
         * The document declares no PDF/A, so it was not verified.
         */
        NOT_DECLARED,
        /**
         * The verification itself failed, see {@link #getError()}.
         */
        FAILED
    }

    /**
     * One violated rule.
     *
     * @param rule    The rule, e.g. "ISO 19005-1:2005 6.7.3-8".
     * @param message The description of the rule.
     * @param count   How often it was violated.
     */
    public record Finding(String rule, String message, int count) {

        /**
         * @return The finding as one line: "rule: message (2x)", the count is only given if it is more than one.
         */
        @Override
        public String toString() {
            return rule + ": " + message + (count > 1 ? " (" + count + "x)" : "");
        }
    }

    public static String notDeclaredText() {
        return I18n.t("verify.notDeclared");
    }

    private static final ValidationResult CONFORM = new ValidationResult(State.CONFORM, List.of(), null);
    private static final ValidationResult NOT_DECLARED = new ValidationResult(State.NOT_DECLARED, List.of(), null);

    private final State state;
    private final List<Finding> findings;
    private final String error;

    private ValidationResult(State state, List<Finding> findings, String error) {
        this.state = state;
        this.findings = findings;
        this.error = error;
    }

    public static ValidationResult conform() {
        return CONFORM;
    }

    public static ValidationResult notDeclared() {
        return NOT_DECLARED;
    }

    /**
     * @param findings The violated rules, at least one.
     */
    public static ValidationResult notConform(List<Finding> findings) {
        if (findings.isEmpty())
            throw new IllegalArgumentException("A result that is not conform needs findings.");
        return new ValidationResult(State.NOT_CONFORM, List.copyOf(findings), null);
    }

    /**
     * @param error Why the verification failed.
     */
    public static ValidationResult failed(String error) {
        return new ValidationResult(State.FAILED, List.of(), error == null ? "unknown error" : error);
    }

    public State getState() {
        return state;
    }

    /**
     * @return The violated rules, empty unless the state is {@link State#NOT_CONFORM}.
     */
    public List<Finding> getFindings() {
        return findings;
    }

    /**
     * @return Why the verification failed, null unless the state is {@link State#FAILED}.
     */
    public String getError() {
        return error;
    }

    public boolean isConform() {
        return state == State.CONFORM;
    }

    /**
     * @return One line per finding, or the line that describes the state if there are none.
     */
    public List<String> toLines() {
        return switch (state) {
            case CONFORM -> List.of();
            case NOT_DECLARED -> List.of(notDeclaredText());
            case FAILED -> List.of(I18n.t("verify.failed", error));
            case NOT_CONFORM -> findings.stream().map(Finding::toString).toList();
        };
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ValidationResult r && state == r.state && findings.equals(r.findings)
                && Objects.equals(error, r.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, findings, error);
    }

    @Override
    public String toString() {
        return state + (findings.isEmpty() ? "" : " " + findings) + (error == null ? "" : " " + error);
    }
}
