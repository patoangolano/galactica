package dev.galactica.client.sky;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * The three textures the sky needs, all generated in code (no assets to ship or license):
 * the baked Milky Way, a Gaussian point-spread function for unresolved stars, and a
 * limb-darkened disc with a soft halo for resolved stars and planets.
 */
public final class SkyTextures {
    private SkyTextures() {}

    private static DynamicTexture galaxy, psf, disc;

    /**
     * Galaxy textures replaced by a resize (sky quality changed), each with the time it was retired.
     * They are closed only after {@link #RETIRE_NANOS}: the exotic imager's worker may still be
     * reading the old {@link NativeImage} through a {@code GalaxySampler} built a few frames earlier.
     */
    private record Retired(DynamicTexture texture, long at) {}
    private static final java.util.ArrayDeque<Retired> RETIRED = new java.util.ArrayDeque<>();
    private static final long RETIRE_NANOS = 30_000_000_000L;

    /** The baked Milky Way, created at the configured sky size; call outside any render pass. */
    public static DynamicTexture galaxy() {
        if (galaxy == null) {
            galaxy = new DynamicTexture(() -> "galactica:milky_way", new NativeImage(SkyManager.skyWidth(), SkyManager.skyHeight(), true));
        }
        return galaxy;
    }

    /**
     * Copies a finished bake into the galaxy texture and uploads it; render thread, outside any pass.
     * The bake's own size (2:1, from its pixel count) wins: after a sky-quality change the texture is
     * recreated at the new size and the old one retired.
     */
    public static void uploadGalaxy(int[] argb) {
        long now = System.nanoTime();
        while (!RETIRED.isEmpty() && now - RETIRED.peekFirst().at() > RETIRE_NANOS) RETIRED.pollFirst().texture().close();
        int w = dev.galactica.config.ConfigEffects.equirectWidth(argb.length), h = argb.length / w;
        DynamicTexture t = galaxy();
        NativeImage img = t.getPixels();
        if (img == null || img.getWidth() != w || img.getHeight() != h) {
            RETIRED.add(new Retired(t, now));
            galaxy = t = new DynamicTexture(() -> "galactica:milky_way", new NativeImage(w, h, true));
            img = t.getPixels();
        }
        for (int i = 0; i < argb.length; i++) img.setPixel(i % w, i / w, argb[i]);
        t.upload();
    }

    /** Airy-like star image: Gaussian core plus a faint 1/r² wing so bright stars bloom a little. */
    public static DynamicTexture psf() {
        if (psf == null) {
            int n = 64;
            NativeImage img = new NativeImage(n, n, true);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    double dx = (x + 0.5) / n * 2 - 1, dy = (y + 0.5) / n * 2 - 1;
                    double r2 = dx * dx + dy * dy;
                    double core = Math.exp(-r2 / (2 * 0.12 * 0.12));
                    double wing = 0.04 / (1 + r2 * 60);
                    double edge = Math.max(0, 1 - r2); // reach zero at the quad edge
                    int a = (int) Math.round(255 * Math.min(1, (core + wing) * edge));
                    img.setPixel(x, y, (a << 24) | 0xFFFFFF);
                }
            }
            psf = new DynamicTexture(() -> "galactica:psf", img);
        }
        return psf;
    }

    /**
     * Disc occupying the inner half of the quad, limb-darkened with the solar law
     * I(μ) = 1 − 0.6(1 − μ), surrounded by a halo that fades to zero at the edge.
     */
    public static DynamicTexture disc() {
        if (disc == null) {
            int n = 256;
            NativeImage img = new NativeImage(n, n, true);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    double dx = (x + 0.5) / n * 2 - 1, dy = (y + 0.5) / n * 2 - 1;
                    double r = Math.sqrt(dx * dx + dy * dy) * 2; // 1 at the disc limb
                    int argb;
                    if (r <= 1) {
                        double mu = Math.sqrt(1 - r * r);
                        int v = (int) Math.round(255 * (1 - 0.6 * (1 - mu)));
                        argb = 0xFF000000 | (v << 16) | (v << 8) | v;
                    } else {
                        double halo = Math.max(0, Math.min(1, 0.5 * Math.pow(1 / r, 3) * (2 - r)));
                        argb = ((int) Math.round(255 * halo) << 24) | 0xFFFFFF;
                    }
                    img.setPixel(x, y, argb);
                }
            }
            disc = new DynamicTexture(() -> "galactica:disc", img);
        }
        return disc;
    }

    public static GpuSampler linearWrapU() {
        return RenderSystem.getSamplerCache().getSampler(AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false);
    }

    public static GpuSampler linearClamp() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }
}
