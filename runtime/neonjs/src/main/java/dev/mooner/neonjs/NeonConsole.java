package dev.mooner.neonjs;

/**
 * Receives fully formatted JavaScript console messages.
 * Derived from NeonJS and adapted to Java.
 */
@FunctionalInterface
public interface NeonConsole {
    enum Level {
        DEBUG, LOG, INFO, WARN, ERROR
    }

    NeonConsole STDIO = (level, message) -> {
        if (level.compareTo(Level.WARN) >= 0) {
            System.err.println(message);
        } else {
            System.out.println(message);
        }
    };

    NeonConsole DISCARD = (level, message) -> { };

    void write(Level level, String message);
}
