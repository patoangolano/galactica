package dev.galactica.client.planet;

import com.mojang.blaze3d.platform.NativeImage;
import dev.galactica.sky.PlanetImager;
import dev.galactica.system.Body;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Painted textures for bodies seen from a surface: a parent giant hanging over its moon, sibling
 * moons, neighbouring planets near conjunction. Same policy as the space sky's cache: repaint only
 * on a visible change (≥ 0.5° of view or light, ≥ 1° of spin, a size bucket), at most one per frame,
 * since a surface sky changes slowly and the frame budget belongs to terrain.
 */
final class SurfaceSprites {
    private SurfaceSprites() {}

    private static final int REPAINTS_PER_FRAME = 1;
    private static final double DIR_EPS = Math.cos(Math.toRadians(0.5));
    private static final double ROT_EPS = Math.toRadians(1.0);
    private static final int EVICT_FRAMES = 400;

    private static final class Entry {
        DynamicTexture texture;
        int size;
        double[] forward, toStar;
        double rotation = Double.NaN;
        int lastSeen;
        int[] scratch;
    }

    private static final Map<Long, Entry> ENTRIES = new HashMap<>();
    private static int frame, repaints;

    static void beginFrame() {
        frame++;
        repaints = 0;
    }

    static DynamicTexture get(Body body, PlanetImager.View view, double pixels) {
        int size = dev.galactica.config.ConfigEffects.spriteSize(pixels, dev.galactica.config.GalacticaConfigs.get().planetTextureMax());
        Entry e = ENTRIES.computeIfAbsent(body.id(), id -> new Entry());
        e.lastSeen = frame;
        boolean resized = e.texture == null || e.size != size;
        if (resized) {
            if (e.texture != null) e.texture.close();
            e.texture = new DynamicTexture(() -> "galactica:surface_sky/" + body.name(), new NativeImage(size, size, true));
            e.size = size;
            e.scratch = new int[size * size];
            e.forward = null;
        }
        boolean stale = e.forward == null || dot(e.forward, view.forward) < DIR_EPS || dot(e.toStar, view.toStar) < DIR_EPS
                || Math.abs(e.rotation - view.rotationRad) > ROT_EPS;
        if (stale && (resized || repaints < REPAINTS_PER_FRAME)) {
            repaints++;
            PlanetImager.render(body, view, size, e.scratch);
            NativeImage img = e.texture.getPixels();
            // Imager column 0 is the −right edge; the sprite quad puts u = 0 on +right.
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

    /** Frees every painted texture: called when the player leaves the surface. */
    static void clear() {
        for (Entry e : ENTRIES.values()) if (e.texture != null) e.texture.close();
        ENTRIES.clear();
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
