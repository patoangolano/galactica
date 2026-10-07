package dev.galactica.client.map;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Screen-space picking and label de-cluttering for the 3D map. Pure Java.
 *
 * <ul>
 *   <li>{@link #pick}: the object under the cursor is the one whose marker is nearest within its reach (its drawn
 *       radius plus a few pixels of slack); among markers that contain the cursor the smallest wins (then the more
 *       important), so a planet is clickable over the star it orbits.</li>
 *   <li>{@link #placeLabels}: labels go in order of importance and each is dropped if its box would overlap one
 *       already placed, up to a cap, so a dense field shows its brightest names and zooming in reveals more.</li>
 * </ul>
 */
public final class MapPicking {
    private MapPicking() {}

    /** Extra pixels around a marker that still count as on it. */
    public static final double SLACK_PX = 5.0;

    /** One drawn object on screen. */
    public record Hit<T>(T object, double x, double y, double radiusPx, double importance) {}

    public static <T> Hit<T> pick(List<Hit<T>> hits, double mx, double my) {
        Hit<T> best = null;
        double bestScore = Double.MAX_VALUE;
        for (Hit<T> h : hits) {
            double dx = h.x() - mx, dy = h.y() - my;
            double d = Math.sqrt(dx * dx + dy * dy);
            double reach = h.radiusPx() + SLACK_PX;
            if (d > reach) continue;
            // A marker the cursor is inside beats any it is only near; among those the smallest (the most specific: a
            // planet over its star's glow) wins, then the more important. Near misses go to the nearest edge.
            double score = d <= h.radiusPx() ? -1e9 + h.radiusPx() * 1e3 - h.importance() : d - h.radiusPx() - 1e-3 * h.importance();
            if (score < bestScore) {
                bestScore = score;
                best = h;
            }
        }
        return best;
    }

    /** A label to place: anchor (the marker's right edge), size and importance. */
    public record Label<T>(T object, String text, double x, double y, int width, int height, double importance) {}

    /** The labels that fit, most important first, none overlapping another (boxes padded by 2 px). */
    public static <T> List<Label<T>> placeLabels(List<Label<T>> labels, int max) {
        List<Label<T>> sorted = new ArrayList<>(labels);
        sorted.sort(Comparator.comparingDouble((Label<T> l) -> -l.importance()));
        List<Label<T>> placed = new ArrayList<>();
        for (Label<T> l : sorted) {
            if (placed.size() >= max) break;
            boolean free = true;
            for (Label<T> p : placed) {
                if (l.x() - 2 < p.x() + p.width() + 2 && l.x() + l.width() + 2 > p.x() - 2
                        && l.y() - 2 < p.y() + p.height() + 2 && l.y() + l.height() + 2 > p.y() - 2) {
                    free = false;
                    break;
                }
            }
            if (free) placed.add(l);
        }
        return placed;
    }

    /** A 1-2-5 length whose bar is about {@code targetPx} long at {@code pxPerMetre}: {metres, pixels}. */
    public static double[] scaleBar(double pxPerMetre, double targetPx) {
        double want = targetPx / pxPerMetre;
        double p = Math.pow(10, Math.floor(Math.log10(want)));
        double nice = want / p >= 5 ? 5 * p : want / p >= 2 ? 2 * p : p;
        return new double[] {nice, nice * pxPerMetre};
    }
}
