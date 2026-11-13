package dev.mooner.neonjs.runtime;

/** Execution resource termination; derived from NeonJS and adapted to Java. */
public final class ResourceLimitException extends TerminationException {
    public ResourceLimitException(String message) {
        super(message);
    }
}
