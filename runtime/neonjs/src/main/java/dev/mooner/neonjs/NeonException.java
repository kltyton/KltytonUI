/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs;

/** Exception raised to the host for errors in JS code (uncaught JS exceptions, syntax errors, limits). */
public class NeonException extends RuntimeException {
    private NeonValue guestValue;
    private String jsStack;

    public NeonException(String message) {
        super(message);
    }

    public NeonException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The thrown JS value, if the error originated from a JS {@code throw} or built-in error. */
    public NeonValue getGuestValue() {
        return guestValue;
    }

    public void setGuestValue(NeonValue guestValue) {
        this.guestValue = guestValue;
    }

    /** JS stack trace (frames), if available. */
    public String getJsStack() {
        return jsStack;
    }

    public void setJsStack(String jsStack) {
        this.jsStack = jsStack;
    }

    public boolean isSyntaxError() {
        return false;
    }

    public boolean isTermination() {
        return false;
    }
}
