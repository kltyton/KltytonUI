/*
 * Derived from NeonJS (Apache License 2.0), Copyright 2026 mooner1022.
 * See META-INF/licenses/neonjs/LICENSE. Adapted to Java for KltytonUI.
 */

package dev.mooner.neonjs.builtins;

import dev.mooner.neonjs.runtime.Agent;
import dev.mooner.neonjs.runtime.JSObject;
import dev.mooner.neonjs.runtime.Ops;
import dev.mooner.neonjs.runtime.Realm;
import java.math.BigInteger;
import java.nio.ByteOrder;
import static dev.mooner.neonjs.builtins.BuiltinsKt.rangeErr;

/** Raw byte-level access to buffer data (GetValueFromBuffer / SetValueInBuffer / atomics). */
public final class BufferOps {
    public static final byte[] EMPTY = new byte[0];

    /** Largest supported buffer byte length. */
    public static final int MAX_BYTE_LENGTH = Integer.MAX_VALUE - 8;

    public static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);

    /**
     * Whether multi-byte loads and stores go through byte-array view VarHandles. Elsewhere values are assembled from
     * bytes ({@code -Dneonjs.noVarHandle=true} forces that path, to test it on a JVM).
     */
    public static final boolean USE_VAR_HANDLES = !Boolean.getBoolean("neonjs.noVarHandle")
            && !"Dalvik".equals(System.getProperty("java.vm.name")) && varHandlesAvailable();

    private BufferOps() {
    }

    private static boolean varHandlesAvailable() {
        try {
            ClassLoader loader = BufferOps.class.getClassLoader();
            Class.forName("java.lang.invoke.VarHandle", false, loader);
            Class<?> methodHandles = Class.forName("java.lang.invoke.MethodHandles", false, loader);
            methodHandles.getMethod("byteArrayViewVarHandle", Class.class, ByteOrder.class);
            Object view = ByteViews.I16_LE;
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException unavailable) {
            return false;
        } catch (ExceptionInInitializerError error) {
            if (error.getCause() instanceof UnsupportedOperationException) return false;
            throw error;
        }
    }

    /** A Uint8Array over an immutable ArrayBuffer holding {@code data} (bytes modules). */
    public static JSObject immutableBytes(Realm realm, byte[] data) {
        if (data.length > MAX_BYTE_LENGTH) throw rangeErr("Array buffer allocation failed");
        JSArrayBuffer buffer = new JSArrayBuffer(realm.intrinsics.get("%ArrayBuffer.prototype%"),
                data, data.length, -1, null);
        buffer.immutable = true;
        return new JSTypedArray(realm.intrinsics.get("%Uint8Array.prototype%"),
                ElementType.UINT8, buffer, 0, data.length);
    }

    /** CreateByteDataBlock: zeroed bytes, RangeError instead of OutOfMemoryError. */
    public static byte[] allocate(long length) {
        if (length < 0 || length > MAX_BYTE_LENGTH) throw rangeErr("Array buffer allocation failed");
        if (length == 0L) return EMPTY;
        Agent agent = Agent.current.get();
        if (agent != null) agent.reserveAllocation(length);
        try {
            return new byte[(int) length];
        } catch (OutOfMemoryError error) {
            throw rangeErr("Array buffer allocation failed");
        }
    }

    // ------------------------------------------------------------------ raw loads / stores

    public static short getI16(byte[] data, int index, boolean littleEndian) {
        if (USE_VAR_HANDLES) return ByteViews.getI16(data, index, littleEndian);
        int b0 = data[index] & 0xFF;
        int b1 = data[index + 1] & 0xFF;
        return (short) (littleEndian ? b0 | (b1 << 8) : (b0 << 8) | b1);
    }

    public static int getI32(byte[] data, int index, boolean littleEndian) {
        if (USE_VAR_HANDLES) return ByteViews.getI32(data, index, littleEndian);
        int result = 0;
        for (int k = 0; k < 4; k++) {
            result |= (data[index + (littleEndian ? k : 3 - k)] & 0xFF) << (8 * k);
        }
        return result;
    }

    public static long getI64(byte[] data, int index, boolean littleEndian) {
        if (USE_VAR_HANDLES) return ByteViews.getI64(data, index, littleEndian);
        long result = 0L;
        for (int k = 0; k < 8; k++) {
            result |= ((long) data[index + (littleEndian ? k : 7 - k)] & 0xFFL) << (8 * k);
        }
        return result;
    }

    public static void setI16(byte[] data, int index, short value, boolean littleEndian) {
        if (USE_VAR_HANDLES) {
            ByteViews.setI16(data, index, value, littleEndian);
            return;
        }
        int x = value;
        data[index + (littleEndian ? 0 : 1)] = (byte) x;
        data[index + (littleEndian ? 1 : 0)] = (byte) (x >> 8);
    }

    public static void setI32(byte[] data, int index, int value, boolean littleEndian) {
        if (USE_VAR_HANDLES) {
            ByteViews.setI32(data, index, value, littleEndian);
            return;
        }
        for (int k = 0; k < 4; k++) data[index + (littleEndian ? k : 3 - k)] = (byte) (value >> (8 * k));
    }

    public static void setI64(byte[] data, int index, long value, boolean littleEndian) {
        if (USE_VAR_HANDLES) {
            ByteViews.setI64(data, index, value, littleEndian);
            return;
        }
        for (int k = 0; k < 8; k++) data[index + (littleEndian ? k : 7 - k)] = (byte) (value >> (8 * k));
    }

    /** RawBytesToNumeric for a 64-bit pattern. */
    public static BigInteger bigFromBits(long bits, boolean unsigned) {
        BigInteger value = BigInteger.valueOf(bits);
        return unsigned && bits < 0 ? value.add(TWO_64) : value;
    }

    /** GetValueFromBuffer: returns a Double or (for BigInt types) a BigInteger. */
    public static Object load(byte[] data, int index, ElementType type) {
        return load(data, index, type, true);
    }

    public static Object load(byte[] data, int index, ElementType type, boolean littleEndian) {
        if (type == ElementType.BIGINT64) return BigInteger.valueOf(getI64(data, index, littleEndian));
        if (type == ElementType.BIGUINT64) return bigFromBits(getI64(data, index, littleEndian), true);
        return loadNumber(data, index, type, littleEndian);
    }

    /** GetValueFromBuffer for a Number type. */
    public static double loadNumber(byte[] data, int index, ElementType type) {
        return loadNumber(data, index, type, true);
    }

    public static double loadNumber(byte[] data, int index, ElementType type, boolean littleEndian) {
        switch (type) {
            case INT8:
                return data[index];
            case UINT8:
            case UINT8C:
                return data[index] & 0xFF;
            case INT16:
                return getI16(data, index, littleEndian);
            case UINT16:
                return getI16(data, index, littleEndian) & 0xFFFF;
            case INT32:
                return getI32(data, index, littleEndian);
            case UINT32:
                return (getI32(data, index, littleEndian) & 0xFFFFFFFFL);
            case FLOAT16:
                return Float16.toDouble(getI16(data, index, littleEndian));
            case FLOAT32:
                return Float.intBitsToFloat(getI32(data, index, littleEndian));
            case FLOAT64:
                return Double.longBitsToDouble(getI64(data, index, littleEndian));
            case BIGINT64:
            case BIGUINT64:
                throw new IllegalArgumentException("a Number from " + type);
            default:
                throw new IllegalArgumentException("not a Number type: " + type);
        }
    }

    /** ToInt32 of {@link #loadNumber} (for the integer types up to 32 bits, the element's bits themselves). */
    public static int loadToInt32(byte[] data, int index, ElementType type) {
        switch (type) {
            case INT8:
                return data[index];
            case UINT8:
            case UINT8C:
                return data[index] & 0xFF;
            case INT16:
                return getI16(data, index, true);
            case UINT16:
                return getI16(data, index, true) & 0xFFFF;
            case INT32:
            case UINT32:
                return getI32(data, index, true);
            default:
                return Ops.toInt32(loadNumber(data, index, type));
        }
    }

    /** SetValueInBuffer: {@code value} must already be a Double or BigInteger. */
    public static void store(byte[] data, int index, ElementType type, Object value) {
        store(data, index, type, value, true);
    }

    public static void store(byte[] data, int index, ElementType type, Object value, boolean littleEndian) {
        if (type.isBigInt) setI64(data, index, ((BigInteger) value).longValue(), littleEndian);
        else storeNumber(data, index, type, (Double) value, littleEndian);
    }

    /** SetValueInBuffer of a Number into a Number type. */
    public static void storeNumber(byte[] data, int index, ElementType type, double value) {
        storeNumber(data, index, type, value, true);
    }

    public static void storeNumber(byte[] data, int index, ElementType type, double value, boolean littleEndian) {
        switch (type) {
            case INT8:
            case UINT8:
                data[index] = (byte) Ops.toInt32(value);
                break;
            case UINT8C:
                data[index] = (byte) clamp(value);
                break;
            case INT16:
            case UINT16:
                setI16(data, index, (short) Ops.toInt32(value), littleEndian);
                break;
            case INT32:
            case UINT32:
                setI32(data, index, Ops.toInt32(value), littleEndian);
                break;
            case FLOAT16:
                setI16(data, index, Float16.fromDouble(value), littleEndian);
                break;
            case FLOAT32:
                setI32(data, index, Float.floatToRawIntBits((float) value), littleEndian);
                break;
            case FLOAT64:
                setI64(data, index, Double.doubleToRawLongBits(value), littleEndian);
                break;
            case BIGINT64:
            case BIGUINT64:
                throw new IllegalArgumentException("a Number into " + type);
            default:
                throw new IllegalArgumentException("not a Number type: " + type);
        }
    }

    /** {@link #storeNumber} of an int32 {@code value} (its ToInt32 is itself). */
    public static void storeInt(byte[] data, int index, ElementType type, int value) {
        switch (type) {
            case INT8:
            case UINT8:
                data[index] = (byte) value;
                break;
            case UINT8C:
                data[index] = (byte) (value < 0 ? 0 : Math.min(value, 255));
                break;
            case INT16:
            case UINT16:
                setI16(data, index, (short) value, true);
                break;
            case INT32:
            case UINT32:
                setI32(data, index, value, true);
                break;
            default:
                storeNumber(data, index, type, (double) value);
                break;
        }
    }

    /** ToUint8Clamp on a Number. */
    public static int clamp(double value) {
        if (!(value > 0)) return 0;
        if (value >= 255) return 255;
        double floor = Math.floor(value);
        double diff = value - floor;
        if (diff < 0.5) return (int) floor;
        if (diff > 0.5) return (int) floor + 1;
        int fi = (int) floor;
        return (fi & 1) == 0 ? fi : fi + 1;
    }
}
