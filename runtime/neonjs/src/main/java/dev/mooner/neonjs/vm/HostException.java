package dev.mooner.neonjs.vm;

/** Host exception crossing into JavaScript; derived from NeonJS and adapted to Java. */
public final class HostException extends RuntimeException {
    private final Throwable hostCause;

    public HostException(Throwable cause) {
        super(cause.toString(), cause);
        hostCause = cause;
    }

    public Throwable getHostCause() {
        return hostCause;
    }
}
