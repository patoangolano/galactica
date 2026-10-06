package dev.galactica.client.vfx;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Contents of a std140 uniform block made only of {@code vec4} members (and {@code vec4} arrays).
 *
 * <p>In std140 a {@code vec4} has base alignment 16 and an array of {@code vec4} has stride 16
 * (OpenGL 4.6 core spec, §7.6.2.2, rules 4 and 4a), so such a block is exactly a packed float
 * array with no padding: slot {@code i} lives at byte offset {@code 16·i}. Restricting blocks to
 * this shape removes every alignment pitfall and lets the packing be unit-tested without a GPU.
 *
 * <p>Value semantics: two blocks with the same floats are equal, so a GPU ring can skip
 * re-uploading identical data.
 */
public final class Vec4Block {
    private final float[] data;

    public Vec4Block(int vec4Count) {
        if (vec4Count <= 0) throw new IllegalArgumentException("vec4Count must be > 0");
        this.data = new float[vec4Count * 4];
    }

    public int vec4Count() {
        return data.length / 4;
    }

    /** Size in bytes, already a multiple of 16 as std140 requires for the whole block. */
    public int sizeBytes() {
        return data.length * Float.BYTES;
    }

    public Vec4Block put(int slot, float x, float y, float z, float w) {
        int o = slot * 4;
        data[o] = x;
        data[o + 1] = y;
        data[o + 2] = z;
        data[o + 3] = w;
        return this;
    }

    public Vec4Block put(int slot, double x, double y, double z, double w) {
        return put(slot, (float) x, (float) y, (float) z, (float) w);
    }

    public float get(int slot, int component) {
        return data[slot * 4 + component];
    }

    /** Writes the block at the buffer's position in native byte order (GPU upload order). */
    public void write(ByteBuffer out) {
        ByteBuffer b = out.order() == ByteOrder.nativeOrder() ? out : out.duplicate().order(ByteOrder.nativeOrder());
        int start = out.position();
        for (int i = 0; i < data.length; i++) b.putFloat(start + i * 4, data[i]);
        out.position(start + data.length * 4);
    }

    public Vec4Block copy() {
        Vec4Block c = new Vec4Block(vec4Count());
        System.arraycopy(data, 0, c.data, 0, data.length);
        return c;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Vec4Block v && Arrays.equals(data, v.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }
}
