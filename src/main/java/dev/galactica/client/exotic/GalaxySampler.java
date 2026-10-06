package dev.galactica.client.exotic;

import com.mojang.blaze3d.platform.NativeImage;
import dev.galactica.client.sky.SkyManager;
import dev.galactica.client.sky.SkyTextures;
import dev.galactica.exotic.ExoticImager;
import dev.galactica.science.Blackbody;

/**
 * Reads the baked Milky Way back from the CPU copy that {@link SkyTextures#galaxy()} keeps, so the
 * lens sprites show the same sky that is drawn behind them. Directions are Minecraft axes and map to
 * the equirectangular layout of {@code GalaxySkyBaker.equirectDirection}: u is the longitude around
 * +Y starting at −Z, v runs from the zenith to the nadir. Bilinear, sRGB decoded to linear.
 */
final class GalaxySampler implements ExoticImager.SkySampler {
    private static final float[] DECODE = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double s = i / 255.0;
            DECODE[i] = (float) (s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4));
        }
    }

    private final NativeImage img;
    private final int w, h;

    GalaxySampler() {
        img = SkyTextures.galaxy().getPixels();
        w = img != null ? img.getWidth() : SkyManager.SKY_WIDTH;
        h = img != null ? img.getHeight() : SkyManager.SKY_HEIGHT;
    }

    @Override
    public void sample(double x, double y, double z, float[] out) {
        if (img == null) {
            out[0] = out[1] = out[2] = 0;
            return;
        }
        double n = Math.sqrt(x * x + y * y + z * z);
        double u = Math.atan2(x, -z) / (2 * Math.PI);
        if (u < 0) u += 1;
        double v = Math.acos(Math.max(-1, Math.min(1, y / n))) / Math.PI;
        double fx = u * w - 0.5, fy = v * h - 0.5;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        double tx = fx - x0, ty = fy - y0;
        out[0] = out[1] = out[2] = 0;
        for (int k = 0; k < 4; k++) {
            int xi = Math.floorMod(x0 + (k & 1), w);
            int yi = Math.max(0, Math.min(h - 1, y0 + (k >> 1)));
            double wt = ((k & 1) == 0 ? 1 - tx : tx) * ((k >> 1) == 0 ? 1 - ty : ty);
            int argb = img.getPixel(xi, yi);
            out[0] += (float) (wt * DECODE[(argb >> 16) & 0xFF]);
            out[1] += (float) (wt * DECODE[(argb >> 8) & 0xFF]);
            out[2] += (float) (wt * DECODE[argb & 0xFF]);
        }
    }

    /** Linear → sRGB for vertex colours. */
    static int encode(float linear) {
        return (int) Math.round(255 * Blackbody.encode(linear));
    }
}
