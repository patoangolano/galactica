package dev.galactica.client.sky;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import dev.galactica.sky.PlanetImager;
import dev.galactica.system.Body;
import org.lwjgl.system.MemoryUtil;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One texture per resolved planet or moon, painted by {@link PlanetImager}. A texture is repainted
 * only when what it shows has changed visibly: the view or light direction moved more than ~0.3°,
 * the planet spun more than ~0.5°, or its size on screen crossed a power-of-two bucket.
 *
 * <p>Repaints run on a background thread: a 512² sprite of a cratered body costs ~75 ms of CPU
 * (crater relief per pixel), which on the render thread would stall every frame of an approach.
 * Until a repaint lands the previous image stays on screen, at most a frame or a few behind. Only a
 * body's very first image is painted on the render thread (there is nothing to show before it), at
 * no more than {@link #FIRST_SIZE}² (a few ms); the full size follows in the background. At most
 * {@link #UPLOADS_PER_FRAME} finished images are uploaded a frame, before the sky pass opens.
 */
final class PlanetSprites {
    private PlanetSprites() {}

    static final int UPLOADS_PER_FRAME = 2;
    static final int FIRST_SIZE = 64;
    private static final double DIR_EPS = Math.cos(Math.toRadians(0.3));
    private static final double ROT_EPS = Math.toRadians(0.5);
    private static final int EVICT_FRAMES = 200;

    private static final ExecutorService PAINTER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Galactica planet imager");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /** A painted image and what it was painted for. */
    private record Painted(int size, int[] argb, double[] forward, double[] toStar, double rotation) {}

    private static final class Entry {
        DynamicTexture texture;
        int size;
        /** What the uploaded texture shows. */
        double[] forward, toStar;
        double rotation = Double.NaN;
        /** What the repaint in flight (or last asked for) shows; null when none was asked for yet. */
        double[] wantForward, wantToStar;
        double wantRotation = Double.NaN;
        int wantSize;
        volatile boolean painting;
        volatile Painted done;
        int lastSeen;
    }

    private static final Map<Long, Entry> ENTRIES = new HashMap<>();
    private static int frame, uploadsThisFrame;

    static void beginFrame() {
        frame++;
        uploadsThisFrame = 0;
    }

    /** Returns the texture for this body, asking for a repaint if needed. {@code pixels} is the wanted on-screen size. */
    static DynamicTexture get(Body body, PlanetImager.View view, double pixels) {
        long perf = dev.galactica.perf.Perf.t0();
        try {
            return getTimed(body, view, pixels);
        } finally {
            dev.galactica.perf.Perf.add(dev.galactica.perf.Perf.S.PLANET_SPRITES, perf);
        }
    }

    private static DynamicTexture getTimed(Body body, PlanetImager.View view, double pixels) {
        int size = dev.galactica.config.ConfigEffects.spriteSize(pixels, dev.galactica.config.GalacticaConfigs.get().planetTextureMax());
        Entry e = ENTRIES.computeIfAbsent(body.id(), id -> new Entry());
        e.lastSeen = frame;
        if (e.texture == null) {
            // First sight: something to draw right now, small enough to paint in a few ms.
            int first = Math.min(size, FIRST_SIZE);
            int[] argb = new int[first * first];
            PlanetImager.render(body, view, first, argb);
            show(body, e, new Painted(first, argb, view.forward.clone(), view.toStar.clone(), view.rotationRad));
        }
        Painted p = e.done;
        if (p != null && uploadsThisFrame < UPLOADS_PER_FRAME) {
            e.done = null;
            uploadsThisFrame++;
            show(body, e, p);
        }
        if (!e.painting && (size != e.wantSize || differs(e.wantForward, e.wantToStar, e.wantRotation, view))) {
            boolean shownStale = size != e.size || differs(e.forward, e.toStar, e.rotation, view);
            if (shownStale) {
                e.painting = true;
                e.wantSize = size;
                e.wantForward = view.forward.clone();
                e.wantToStar = view.toStar.clone();
                e.wantRotation = view.rotationRad;
                Painted job = new Painted(size, null, e.wantForward, e.wantToStar, e.wantRotation);
                PAINTER.execute(() -> {
                    try {
                        int[] argb = new int[job.size() * job.size()];
                        PlanetImager.render(body, view, job.size(), argb);
                        e.done = new Painted(job.size(), argb, job.forward(), job.toStar(), job.rotation());
                    } finally {
                        e.painting = false;
                    }
                });
            }
        }
        return e.texture;
    }

    private static boolean differs(double[] forward, double[] toStar, double rotation, PlanetImager.View view) {
        return forward == null || dot(forward, view.forward) < DIR_EPS || dot(toStar, view.toStar) < DIR_EPS
                || Math.abs(rotation - view.rotationRad) > ROT_EPS;
    }

    /** Uploads a painted image into the body's texture (resized as needed). Render thread, outside a pass. */
    private static void show(Body body, Entry e, Painted p) {
        int n = p.size();
        if (e.texture == null || e.size != n) {
            if (e.texture != null) e.texture.close();
            e.texture = new DynamicTexture(() -> "galactica:planet/" + body.name(), new NativeImage(n, n, true));
            e.size = n;
        }
        NativeImage img = e.texture.getPixels();
        // The imager's column 0 is the −right edge; our quad puts u = 0 on the +right side. One pass
        // into the RGBA image (ABGR as little-endian ints).
        var dst = MemoryUtil.memIntBuffer(img.getPointer(), n * n);
        int[] src = p.argb();
        for (int y = 0; y < n; y++) {
            int row = y * n;
            for (int x = 0; x < n; x++) {
                int c = src[row + x];
                dst.put(row + n - 1 - x, c & 0xFF00FF00 | c >> 16 & 0xFF | (c & 0xFF) << 16);
            }
        }
        e.texture.upload();
        e.forward = p.forward();
        e.toStar = p.toStar();
        e.rotation = p.rotation();
    }

    /** Frees textures of bodies that have not been on screen for a while. */
    static void evict() {
        Iterator<Entry> it = ENTRIES.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (frame - e.lastSeen > EVICT_FRAMES) {
                if (e.texture != null) e.texture.close();
                e.done = null;
                it.remove();
            }
        }
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
