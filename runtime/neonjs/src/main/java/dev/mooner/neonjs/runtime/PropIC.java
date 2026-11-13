package dev.mooner.neonjs.runtime;

/** Immutable property-cache entry; derived from NeonJS and adapted to Java. */
public final class PropIC {
    public final Shape shape;
    public final Shape[] chain;
    public final int slot;
    public final int kind;
    public final Shape next;
    public final PropIC link;

    public PropIC(Shape shape, Shape[] chain, int slot, int kind, Shape next, PropIC link) {
        this.shape = shape;
        this.chain = chain;
        this.slot = slot;
        this.kind = kind;
        this.next = next;
        this.link = link;
    }

    public PropIC withLink(PropIC link) {
        return new PropIC(shape, chain, slot, kind, next, link);
    }
}
