package io.github.kltyton.kltytonui.script.neon.host;

/** Distinguishes an explicit JavaScript undefined from null in Java value views. */
public enum NeonUndefined {
    INSTANCE;
    @Override public String toString() { return "undefined"; }
}

