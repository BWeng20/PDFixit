package com.bw.pdfixit;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Log {

    public static final boolean INFO = true;

    /**
     * Debug output, off by default. Enabled by the CLI option "--debug" or the system property "pdfixit.debug".
     */
    private static volatile boolean debug;

    /**
     * Libraries that log via java.util.logging (e.g. the PDF/A validator veraPDF used by Mustang).
     * Strong references are needed, otherwise the JDK may drop the loggers together with their level.
     */
    private static final List<Logger> LIBRARY_LOGGERS = List.of(
            Logger.getLogger("org.verapdf"),
            Logger.getLogger("org.mustangproject"));

    static {
        setDebug(Boolean.getBoolean("pdfixit.debug"));
    }

    private Log() {
    }

    /**
     * Makes sure the levels of the library loggers are set. Call it before using such a library.
     * The levels are set when this class is loaded, so this does nothing else.
     */
    public static void init() {
    }

    public static boolean isDebug() {
        return debug;
    }

    /**
     * Switches debug output on or off. Without debug, warnings of libraries are suppressed, only their errors are shown.
     */
    public static void setDebug(boolean enabled) {
        debug = enabled;
        for (Logger l : LIBRARY_LOGGERS)
            l.setLevel(enabled ? Level.INFO : Level.SEVERE);
    }

    public static void info(String format, Object... arguments) {
        if (INFO)
            System.out.printf("[INFO] " + format + "%n", arguments);
    }

    public static void debug(String format, Object... arguments) {
        if (debug)
            System.out.printf("[DEBUG] " + format + "%n", arguments);
    }

    public static void error(String format, Object... arguments) {
        System.err.printf((format) + "%n", arguments);
    }

    public static void exception(Exception e) {
        System.err.printf("[EXCEPTION] %s%n", e.getMessage());
        if (debug)
            e.printStackTrace();
    }
}
