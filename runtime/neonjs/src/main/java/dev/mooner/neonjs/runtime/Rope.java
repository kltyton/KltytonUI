package dev.mooner.neonjs.runtime;

import java.util.ArrayDeque;

/** Lazily flattened concatenation string; derived from NeonJS and adapted to Java. */
public final class Rope implements CharSequence {
    public static final int MAX_LENGTH = (1 << 30) - 25;

    private CharSequence left;
    private CharSequence right;
    private String flat;
    private final int length;
    public final int depth;

    public Rope(CharSequence left, CharSequence right) {
        this.left = left;
        this.right = right;
        length = left.length() + right.length();
        depth = Math.max(depthOf(left), depthOf(right)) + 1;
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public char charAt(int index) {
        return toString().charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return toString().substring(start, end);
    }

    @Override
    public String toString() {
        String cached = flat;
        if (cached != null) return cached;
        StringBuilder builder = new StringBuilder(length);
        appendTo(builder);
        String result = builder.toString();
        flat = result;
        left = null;
        right = null;
        return result;
    }

    private void appendTo(StringBuilder builder) {
        ArrayDeque<CharSequence> stack = new ArrayDeque<>();
        stack.addLast(this);
        while (!stack.isEmpty()) {
            CharSequence sequence = stack.removeLast();
            if (sequence instanceof Rope rope) {
                String cached = rope.flat;
                if (cached != null) {
                    builder.append(cached);
                } else {
                    stack.addLast(rope.right);
                    stack.addLast(rope.left);
                }
            } else {
                builder.append(sequence);
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CharSequence && toString().equals(other.toString());
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    private static int depthOf(CharSequence sequence) {
        return sequence instanceof Rope rope ? rope.depth : 0;
    }

    public static CharSequence concat(CharSequence a, CharSequence b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        long combined = (long) a.length() + b.length();
        if (combined > MAX_LENGTH) throw JSException.rangeErrorNoRealm("Invalid string length");
        if (combined > 65536) {
            Agent agent = Agent.current.get();
            if (agent != null) agent.checkStringLength(combined);
        }
        if (combined < 32 || (a instanceof Rope ropeA && ropeA.depth > 2000)
                || (b instanceof Rope ropeB && ropeB.depth > 2000)) {
            return a.toString() + b.toString();
        }
        return new Rope(a, b);
    }
}
