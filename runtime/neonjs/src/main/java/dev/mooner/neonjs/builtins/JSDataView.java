/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.JSObject;

/** DataView instance. */
public final class JSDataView extends JSObject {
    public final JSArrayBuffer buffer;
    public final int byteOffset;
    /** {@code [[ByteLength]]}, or -1 (auto) for length-tracking views of resizable buffers. */
    public final int fixedByteLength;

    public JSDataView(JSObject prototype, JSArrayBuffer buffer, int byteOffset, int fixedByteLength) {
        super(prototype);
        this.buffer = buffer;
        this.byteOffset = byteOffset;
        this.fixedByteLength = fixedByteLength;
    }

    /** GetViewByteLength, or -1 when IsViewOutOfBounds (which includes a detached buffer). */
    public int byteLengthOrOOB() {
        JSArrayBuffer source = buffer;
        if (source.detached) return -1;
        int bufferLength = source.byteLength();
        if (byteOffset > bufferLength) return -1;
        int length = fixedByteLength;
        if (length < 0) return bufferLength - byteOffset;
        return (long) byteOffset + (long) length > bufferLength ? -1 : length;
    }
}
