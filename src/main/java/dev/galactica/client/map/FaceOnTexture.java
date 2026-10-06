package dev.galactica.client.map;

import com.mojang.blaze3d.platform.NativeImage;
import dev.galactica.Galactica;
import dev.galactica.nav.Frames;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The face-on background as a GPU texture. Loading or baking runs on a daemon worker: the PNG
 * cache under {@code config/galactica/map_faceon.png} is read if present, otherwise
 * {@link FaceOnBake} runs (~3 s) and writes it. The finished {@link NativeImage} is handed over and
 * turned into a {@link DynamicTexture} on the client thread from {@link #resolve()}, which the
 * screen calls in {@code tick()}: creating a DynamicTexture uploads, and an upload while a render
 * pass is open crashes (MODLOG), so it never happens during drawing.
 */
public final class FaceOnTexture {
    private FaceOnTexture() {}

    public static final Identifier ID = Galactica.id("dynamic/map_faceon");
    static final String CACHE_NAME = "map_faceon.png";

    private static volatile NativeImage pending;
    private static volatile boolean started, failed;
    private static boolean registered;

    public static Path cacheFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(Galactica.MOD_ID).resolve(CACHE_NAME);
    }

    /** Starts loading or baking once per game session. */
    public static synchronized void request() {
        if (started) return;
        started = true;
        Thread t = new Thread(FaceOnTexture::load, "Galactica map bake");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void load() {
        Path file = cacheFile();
        try {
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    NativeImage img = NativeImage.read(in);
                    if (img.getWidth() == FaceOnBake.SIZE) {
                        pending = img;
                        return;
                    }
                    img.close();
                }
            }
            long t0 = System.nanoTime();
            int[] argb = FaceOnBake.bake(Frames.GALAXY_SEED, FaceOnBake.SIZE);
            NativeImage img = new NativeImage(FaceOnBake.SIZE, FaceOnBake.SIZE, false);
            for (int i = 0; i < argb.length; i++) img.setPixel(i % FaceOnBake.SIZE, i / FaceOnBake.SIZE, argb[i]);
            Galactica.LOG.info("Galactica: face-on galaxy map baked in {} ms", (System.nanoTime() - t0) / 1_000_000);
            try {
                Files.createDirectories(file.getParent());
                img.writeToFile(file);
            } catch (Exception e) {
                Galactica.LOG.warn("Galactica: could not cache the galaxy map at {}", file, e);
            }
            pending = img;
        } catch (Exception e) {
            failed = true;
            Galactica.LOG.error("Galactica: galaxy map background failed", e);
        }
    }

    /** Client thread, outside rendering: registers the texture once the worker is done. True when drawable. */
    public static boolean resolve() {
        if (registered) return true;
        NativeImage img = pending;
        if (img == null) return false;
        pending = null;
        Minecraft.getInstance().getTextureManager().register(ID, new DynamicTexture(() -> "galactica:map_faceon", img));
        registered = true;
        return true;
    }

    public static boolean ready() {
        return registered;
    }

    public static boolean failed() {
        return failed;
    }
}
