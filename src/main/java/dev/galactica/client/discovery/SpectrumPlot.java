package dev.galactica.client.discovery;

import dev.galactica.science.spectra.Spectrum;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a {@link Spectrum} with GUI fills only: a logarithmic wavelength axis (so the visible
 * octave and the near-IR both get room), the flux as a connected polyline coloured by wavelength
 * where the eye could see it, a rainbow strip under 380–750 nm, tick labels, and the labelled
 * absorption features in up to three non-overlapping rows above the plot.
 */
final class SpectrumPlot {
    private SpectrumPlot() {}

    static final int LABEL_ROWS = 3, ROW_H = 10;
    private static final int AXIS = 0xFF5A6A8A, GRID = 0x22FFFFFF, TEXT = 0xFFB8C4DC, LINE_MARK = 0x55FF9E80,
            LABEL = 0xFFFFC2A8, IR = 0xFFE8EEFF;
    private static final double[] TICKS = {400, 500, 600, 700, 800, 1000, 1200, 1500, 2000, 2500};

    static double x(double nm, int left, int width) {
        return left + width * Math.log(nm / Spectrum.MIN_NM) / Math.log(Spectrum.MAX_NM / Spectrum.MIN_NM);
    }

    static double nm(double px, int left, int width) {
        return Spectrum.MIN_NM * Math.exp((px - left) / width * Math.log(Spectrum.MAX_NM / Spectrum.MIN_NM));
    }

    /** Draws the plot in [left, left+width) × [top, top+height); labels go in the {@link #LABEL_ROWS} rows above {@code top}. */
    static void draw(GuiGraphicsExtractor g, Font font, Spectrum s, int left, int top, int width, int height) {
        int bottom = top + height;
        g.fill(left, top, left + width, bottom, 0xFF02050C);
        for (int i = 1; i < 4; i++) g.fill(left, top + height * i / 4, left + width, top + height * i / 4 + 1, GRID);
        for (double t : TICKS) {
            int tx = (int) x(t, left, width);
            g.fill(tx, top, tx + 1, bottom, GRID);
        }

        // Feature markers inside the plot first, so the curve draws over them.
        List<int[]> placed = labelLayout(font, s, left, width);
        for (int[] p : placed) g.fill(p[0], top, p[0] + 1, bottom, LINE_MARK);

        if (!s.dark()) {
            double prevY = Double.NaN;
            for (int px = 0; px < width; px++) {
                double a = nm(left + px, left, width), b = nm(left + px + 1, left, width);
                double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
                for (double l = a; l <= b; l += Spectrum.STEP_NM) {
                    double f = s.fluxAt(l);
                    lo = Math.min(lo, f);
                    hi = Math.max(hi, f);
                }
                double fb = s.fluxAt(b);
                lo = Math.min(lo, fb);
                hi = Math.max(hi, fb);
                double yHi = bottom - 1 - hi * (height - 2), yLo = bottom - 1 - lo * (height - 2);
                if (!Double.isNaN(prevY)) {
                    yHi = Math.min(yHi, prevY);
                    yLo = Math.max(yLo, prevY);
                }
                prevY = bottom - 1 - fb * (height - 2);
                int c = colour((a + b) / 2);
                g.fill(left + px, (int) Math.floor(yHi), left + px + 1, (int) Math.floor(yLo) + 1, c);
            }
        }

        // Frame, rainbow strip and axis labels.
        g.outline(left - 1, top - 1, width + 2, height + 2, AXIS);
        for (int px = 0; px < width; px++) {
            double l = nm(left + px + 0.5, left, width);
            if (l >= 380 && l <= 750) g.fill(left + px, bottom + 1, left + px + 1, bottom + 4, 0xFF000000 | rgb(l));
        }
        for (double t : TICKS) {
            int tx = (int) x(t, left, width);
            g.fill(tx, bottom + 1, tx + 1, bottom + 6, AXIS);
            String lab = Integer.toString((int) t);
            g.text(font, lab, Math.min(left + width - font.width(lab), Math.max(left, tx - font.width(lab) / 2)), bottom + 7, TEXT, false);
        }

        // Labels above the plot.
        for (int[] p : placed) {
            int row = p[1];
            String text = s.lines().get(p[2]).label();
            int y = top - (row + 1) * ROW_H;
            g.fill(p[0], y + 8, p[0] + 1, top, 0x66FF9E80);
            g.text(font, text, p[3], y, LABEL, false);
        }
    }

    /** Greedy label placement: deepest features first, each in the lowest free row; repeats of a label nearby are skipped. Returns {x, row, lineIndex, textX}. */
    static List<int[]> labelLayout(Font font, Spectrum s, int left, int width) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < s.lines().size(); i++) order.add(i);
        order.sort((a, b) -> Double.compare(s.lines().get(b).depth(), s.lines().get(a).depth()));
        List<int[]> placed = new ArrayList<>();
        List<List<int[]>> rows = new ArrayList<>();
        for (int r = 0; r < LABEL_ROWS; r++) rows.add(new ArrayList<>());
        for (int i : order) {
            Spectrum.Marked m = s.lines().get(i);
            int lx = (int) x(m.observedNm(), left, width);
            boolean dup = false;
            for (int[] p : placed) {
                if (s.lines().get(p[2]).label().equals(m.label()) && Math.abs(p[0] - lx) < 30) {
                    dup = true;
                    break;
                }
            }
            if (dup) continue;
            int w = font.width(m.label());
            int tx = Math.max(left, Math.min(left + width - w, lx - w / 2));
            for (int r = 0; r < LABEL_ROWS; r++) {
                boolean free = true;
                for (int[] iv : rows.get(r)) {
                    if (tx < iv[1] + 3 && tx + w > iv[0] - 3) {
                        free = false;
                        break;
                    }
                }
                if (free) {
                    rows.get(r).add(new int[] {tx, tx + w});
                    placed.add(new int[] {lx, r, i, tx});
                    break;
                }
            }
        }
        return placed;
    }

    /** Curve colour: the perceived hue in the visible, pale blue-white in the near-IR and UV. */
    static int colour(double nm) {
        if (nm < 380 || nm > 750) return IR;
        int c = rgb(nm);
        // Lift dark ends so the curve stays readable against the black plot.
        int r = Math.max((c >> 16) & 0xFF, 70), gg = Math.max((c >> 8) & 0xFF, 70), b = Math.max(c & 0xFF, 70);
        return 0xFF000000 | (r << 16) | (gg << 8) | b;
    }

    /** Approximate sRGB of monochromatic light (Bruton 1996 piecewise fit), 0xRRGGBB. */
    static int rgb(double nm) {
        double r, g, b;
        if (nm < 440) { r = -(nm - 440) / 60; g = 0; b = 1; }
        else if (nm < 490) { r = 0; g = (nm - 440) / 50; b = 1; }
        else if (nm < 510) { r = 0; g = 1; b = -(nm - 510) / 20; }
        else if (nm < 580) { r = (nm - 510) / 70; g = 1; b = 0; }
        else if (nm < 645) { r = 1; g = -(nm - 645) / 65; b = 0; }
        else { r = 1; g = 0; b = 0; }
        double f = nm < 420 ? 0.3 + 0.7 * (nm - 380) / 40 : nm > 700 ? 0.3 + 0.7 * (750 - nm) / 50 : 1;
        int ri = (int) Math.round(255 * Math.pow(Math.max(0, r * f), 0.8));
        int gi = (int) Math.round(255 * Math.pow(Math.max(0, g * f), 0.8));
        int bi = (int) Math.round(255 * Math.pow(Math.max(0, b * f), 0.8));
        return (ri << 16) | (gi << 8) | bi;
    }
}
