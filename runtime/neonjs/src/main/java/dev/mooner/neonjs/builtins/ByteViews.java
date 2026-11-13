/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Byte-array view VarHandles, kept apart from {@link BufferOps} so that their class (and the polymorphic-signature
 * calls) is only loaded where VarHandle exists; see {@link BufferOps#USE_VAR_HANDLES}.
 */
final class ByteViews {
    static final VarHandle I16_LE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    static final VarHandle I16_BE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    static final VarHandle I32_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    static final VarHandle I32_BE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    static final VarHandle I64_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    static final VarHandle I64_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    private ByteViews() {
    }

    static short getI16(byte[] data, int index, boolean littleEndian) {
        return littleEndian ? (short) I16_LE.get(data, index) : (short) I16_BE.get(data, index);
    }

    static int getI32(byte[] data, int index, boolean littleEndian) {
        return littleEndian ? (int) I32_LE.get(data, index) : (int) I32_BE.get(data, index);
    }

    static long getI64(byte[] data, int index, boolean littleEndian) {
        return littleEndian ? (long) I64_LE.get(data, index) : (long) I64_BE.get(data, index);
    }

    static void setI16(byte[] data, int index, short value, boolean littleEndian) {
        if (littleEndian) I16_LE.set(data, index, value);
        else I16_BE.set(data, index, value);
    }

    static void setI32(byte[] data, int index, int value, boolean littleEndian) {
        if (littleEndian) I32_LE.set(data, index, value);
        else I32_BE.set(data, index, value);
    }

    static void setI64(byte[] data, int index, long value, boolean littleEndian) {
        if (littleEndian) I64_LE.set(data, index, value);
        else I64_BE.set(data, index, value);
    }
}
