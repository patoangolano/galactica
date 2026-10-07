package dev.galactica.client.sky;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import dev.galactica.sky.PlanetImager;
import dev.galactica.system.Body;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * One texture per resolved planet or moon, painted by {@link PlanetImager} on the render thread
 * before the sky pass opens. A texture is repainted only when what it shows has changed visibly:
 * the view or light direction moved more than ~0.3°, the planet spun more than ~0.5°, or its size
 * on screen crossed a power-of-two bucket. At most {@link #REPAINTS_PER_FRAME} are repainted per
 * frame (≈10 ms each at 256²), so flying through a moon system never stalls a frame.
 */
final class PlanetSprites {
    private PlanetSprites() {}

    static final int REPAINTS_PER_FRAME = 2;
    private static final double DIR_EPS = Math.cos(Math.toRadians(0.3));
    private static final double ROT_EPS = Math.toRadians(0.5);
    private static final int EVICT_FRAMES = 200;

    private static final class Entry {
        DynamicTexture texture;
        int size;
        double[] forward, toStar;
        double rotation = Double.NaN;
        int lastSeen;
        int[] scratch;
    }

    private static final Map<Long, Entry> ENTRIES = new HashMap<>();
    private static int frame, repaintsThisFrame;

    static void beginFrame() {
        frame++;
        repaintsThisFrame = 0;
    }

    /** Returns the texture for this body, repainted if needed. {@code pixels} is the wanted on-screen size. */
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
        boolean resized = e.texture == null || e.size != size;
        if (resized) {
            if (e.texture != null) e.texture.close();
            e.texture = new DynamicTexture(() -> "galactica:planet/" + body.name(), new NativeImage(size, size, true));
            e.size = size;
            e.scratch = new int[size * size];
            e.forward = null;
        }
        boolean stale = e.forward == null
                || dot(e.forward, view.forward) < DIR_EPS || dot(e.toStar, view.toStar) < DIR_EPS
                || Math.abs(e.rotation - view.rotationRad) > ROT_EPS;
        if (stale && (resized || repaintsThisFrame < REPAINTS_PER_FRAME)) {
            repaintsThisFrame++;
            PlanetImager.render(body, view, size, e.scratch);
            NativeImage img = e.texture.getPixels();
            // The imager's column 0 is the −right edge; our quad puts u = 0 on the +right side.
            for (int y = 0; y < size; y++) {
                int row = y * size;
                for (int x = 0; x < size; x++) img.setPixel(size - 1 - x, y, e.scratch[row + x]);
            }
            e.texture.upload();
            e.forward = view.forward.clone();
            e.toStar = view.toStar.clone();
            e.rotation = view.rotationRad;
        }
        return e.texture;
    }

    /** Frees textures of bodies that have not been on screen for a while. */
    static void evict() {
        Iterator<Entry> it = ENTRIES.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (frame - e.lastSeen > EVICT_FRAMES) {
                if (e.texture != null) e.texture.close();
                it.remove();
            }
        }
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
