package dev.galactica.client.map;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A growable direct buffer of camera-facing quads in {@code POSITION_TEX_COLOR} (24 bytes a vertex) or, with
 * {@code normals}, {@code POSITION_TEX_COLOR_NORMAL} (28 bytes: the normal as signed bytes plus padding), written the
 * way the GPU reads them (native byte order). Reused frame to frame: {@link #reset} keeps the memory.
 */
final class QuadWriter {
    private final boolean normals;
    private ByteBuffer buf;
    private int quads;

    QuadWriter(boolean normals, int initialQuads) {
        this.normals = normals;
        this.buf = ByteBuffer.allocateDirect(Math.max(16, initialQuads) * 4 * stride()).order(ByteOrder.nativeOrder());
    }

    int stride() {
        return normals ? 28 : 24;
    }

    void reset() {
        buf.clear();
        quads = 0;
    }

    int quads() {
        return quads;
    }

    /** The written quads, positioned for an upload (position 0, limit at the end). */
    ByteBuffer data() {
        ByteBuffer d = buf.duplicate().order(ByteOrder.nativeOrder());
        d.flip();
        return d;
    }

    private void ensure() {
        if (buf.remaining() >= 4 * stride()) return;
        ByteBuffer bigger = ByteBuffer.allocateDirect(buf.capacity() * 2).order(ByteOrder.nativeOrder());
        buf.flip();
        bigger.put(buf);
        buf = bigger;
    }

    /** A sprite at (x, y, z) model units, half-size {@code size} (pixels, or model units for world-sized sprites). */
    void sprite(float x, float y, float z, float size, int argb, float alpha) {
        quad(x, y, z, size, argb, alpha, 0, 0, 0);
    }

    /** A lit body sprite: {@code (lx, ly, lz)} is the unit direction to its light in view space (0 for self-lit). */
    void quad(float x, float y, float z, float size, int argb, float alpha, float lx, float ly, float lz) {
        ensure();
        byte r = (byte) (argb >> 16), g = (byte) (argb >> 8), b = (byte) argb, a = (byte) Math.round(Math.clamp(alpha, 0F, 1F) * 255);
        float[] cx = {-1, 1, 1, -1}, cy = {-1, -1, 1, 1};
        for (int i = 0; i < 4; i++) {
            buf.putFloat(x).putFloat(y).putFloat(z).putFloat(cx[i] * size).putFloat(cy[i] * size);
            buf.put(r).put(g).put(b).put(a);
            if (normals) {
                buf.put((byte) Math.round(Math.clamp(lx, -1F, 1F) * 127)).put((byte) Math.round(Math.clamp(ly, -1F, 1F) * 127))
                        .put((byte) Math.round(Math.clamp(lz, -1F, 1F) * 127)).put((byte) 0);
            }
        }
        quads++;
    }

    /** A textured quad from four corners (model units) with UVs, one colour (the galaxy disk layers). */
    void textured(float[][] corners, float[][] uv, int argb, float alpha) {
        ensure();
        byte r = (byte) (argb >> 16), g = (byte) (argb >> 8), b = (byte) argb, a = (byte) Math.round(Math.clamp(alpha, 0F, 1F) * 255);
        for (int i = 0; i < 4; i++) {
            buf.putFloat(corners[i][0]).putFloat(corners[i][1]).putFloat(corners[i][2]).putFloat(uv[i][0]).putFloat(uv[i][1]);
            buf.put(r).put(g).put(b).put(a);
        }
        quads++;
    }
}
