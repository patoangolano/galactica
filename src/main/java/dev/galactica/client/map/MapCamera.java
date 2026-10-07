package dev.galactica.client.map;

import dev.galactica.nav.GalacticPos;
import dev.galactica.science.Units;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4d;

/**
 * The 3D map's orbit camera, in the galactic frame (z toward the north galactic pole). It circles a focus point at a
 * distance that spans 100 km to 80 kpc on a logarithmic zoom, so one camera frames a moon, a planetary system, the
 * stars around the Sun and the whole Milky Way.
 *
 * <p><b>Precision.</b> The focus is a {@link GalacticPos} (sub-millimetre anywhere in the galaxy). Everything drawn is
 * expressed relative to the focus in <i>camera units</i>, metres divided by the camera distance, so the eye always
 * sits at distance 1 from the origin and float matrices stay exact near the focus whatever the zoom.
 *
 * <p><b>Smoothness.</b> Zoom, focus moves and orbiting set targets; {@link #update} eases toward them (the distance in
 * log space), so a double-click flies to an object instead of jumping. Pure Java, unit-tested.
 */
public final class MapCamera {
    public static final double MIN_DISTANCE = 1.0e5;
    public static final double MAX_DISTANCE = 80_000 * Units.PARSEC;
    public static final double FOV_Y_DEG = 55.0;
    private static final double MAX_PITCH = Math.toRadians(89.0);
    /** Zoom factor per scroll notch. */
    public static final double ZOOM_STEP = 1.25;

    private GalacticPos focus, focusTarget;
    private double distance, distanceTarget;
    private double yaw, pitch;
    private int width = 1, height = 1;

    public MapCamera(GalacticPos focus, double distance, double yaw, double pitch) {
        this.focus = this.focusTarget = focus;
        this.distance = this.distanceTarget = clampDistance(distance);
        this.yaw = yaw;
        this.pitch = Math.clamp(pitch, -MAX_PITCH, MAX_PITCH);
    }

    public static double clampDistance(double d) {
        return Math.clamp(Double.isFinite(d) ? d : MAX_DISTANCE, MIN_DISTANCE, MAX_DISTANCE);
    }

    public void setViewport(int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
    }

    public int width() { return width; }
    public int height() { return height; }
    public GalacticPos focus() { return focus; }
    public GalacticPos focusTarget() { return focusTarget; }
    public double distance() { return distance; }
    public double distanceTarget() { return distanceTarget; }
    public double yaw() { return yaw; }
    public double pitch() { return pitch; }

    // ---------------------------------------------------------------- controls

    /** Scroll notches (positive zooms in), eased by {@link #update}. */
    public void zoom(double notches) {
        distanceTarget = clampDistance(distanceTarget * Math.pow(ZOOM_STEP, -notches));
    }

    public void setDistanceTarget(double d) {
        distanceTarget = clampDistance(d);
    }

    /** Orbits by a mouse drag in pixels (a full viewport width turns the view by 360°). */
    public void orbit(double dxPx, double dyPx) {
        yaw -= dxPx / width * 2 * Math.PI;
        pitch = Math.clamp(pitch + dyPx / height * Math.PI, -MAX_PITCH, MAX_PITCH);
    }

    /** Pans the focus with a drag so the point under the cursor follows it (at the focus's depth). */
    public void pan(double dxPx, double dyPx) {
        double metresPerPx = 2 * distanceTarget * Math.tan(Math.toRadians(FOV_Y_DEG) / 2) / height;
        Vector3d right = right(), up = up();
        double sx = -dxPx * metresPerPx, sy = dyPx * metresPerPx;
        moveFocus(right.x * sx + up.x * sy, right.y * sx + up.y * sy, right.z * sx + up.z * sy);
    }

    /**
     * Flies the focus: {@code forward} along the view, {@code strafe} to the right, {@code lift} along galactic north,
     * each in units of the camera distance (the WASD/QE keys move a fraction of the distance per second).
     */
    public void fly(double forward, double strafe, double lift) {
        Vector3d f = forward(), r = right();
        double d = distanceTarget;
        moveFocus((f.x * forward + r.x * strafe) * d, (f.y * forward + r.y * strafe) * d, (f.z * forward + r.z * strafe + lift) * d);
    }

    /** Moves the focus target by a galactic offset in metres (eased); keeps the current focus easing toward it. */
    public void moveFocus(double dx, double dy, double dz) {
        focusTarget = focusTarget.plusMetres(dx, dy, dz);
    }

    /** Eases to a new focus and distance (a double-click, a search hit, Home). */
    public void flyTo(GalacticPos target, double newDistance) {
        focusTarget = target;
        distanceTarget = clampDistance(newDistance);
    }

    /** Jumps there at once (the screen opening). */
    public void snapTo(GalacticPos target, double newDistance) {
        focus = focusTarget = target;
        distance = distanceTarget = clampDistance(newDistance);
    }

    /**
     * Eases toward the targets over {@code dt} seconds: the distance exponentially in log space, the focus along the
     * straight line, with a time constant of ~0.15 s; a far focus move also pulls the distance out first, so the view
     * zooms out and back in instead of sliding across the galaxy at close range.
     */
    public void update(double dt) {
        double k = 1 - Math.exp(-Math.max(0, dt) / 0.15);
        double[] off = focusTarget.metresFrom(focus);
        double gap = Math.sqrt(off[0] * off[0] + off[1] * off[1] + off[2] * off[2]);
        double wanted = distanceTarget;
        if (gap > 3 * distance) wanted = Math.max(wanted, gap * 0.6); // fly out over a long move
        distance = clampDistance(Math.exp(Math.log(distance) + (Math.log(wanted) - Math.log(distance)) * k));
        if (gap < 1e-3) {
            focus = focusTarget;
        } else {
            // Long moves travel a fixed fraction per frame relative to the view size, so they take about the same time.
            // ... but crawl while the view is much smaller than the trip, so it zooms out before it travels.
            double step = gap * k * Math.clamp(2 * distance / gap, 0.05, 1.0);
            focus = focus.plusMetres(off[0] / gap * step, off[1] / gap * step, off[2] / gap * step);
        }
    }

    /** True while the camera is still easing. */
    public boolean moving() {
        double[] off = focusTarget.metresFrom(focus);
        return Math.abs(Math.log(distanceTarget / distance)) > 1e-3 || off[0] * off[0] + off[1] * off[1] + off[2] * off[2] > 1e-6 * distance * distance;
    }

    // ---------------------------------------------------------------- geometry

    /** Unit vector from the focus to the eye. */
    public Vector3d toEye() {
        double cp = Math.cos(pitch);
        return new Vector3d(cp * Math.cos(yaw), cp * Math.sin(yaw), Math.sin(pitch));
    }

    public Vector3d forward() {
        return toEye().negate();
    }

    public Vector3d right() {
        Vector3d r = forward().cross(0, 0, 1);
        return r.lengthSquared() < 1e-12 ? new Vector3d(1, 0, 0) : r.normalize();
    }

    public Vector3d up() {
        return right().cross(forward()).normalize();
    }

    /** The eye's galactic position. */
    public GalacticPos eye() {
        Vector3d e = toEye().mul(distance);
        return focus.plusMetres(e.x, e.y, e.z);
    }

    /** View matrix in camera units: the eye at {@link #toEye()} (distance 1) looking at the origin, galactic z up. */
    public Matrix4d view() {
        Vector3d e = toEye();
        Vector3d up = Math.abs(e.z) > 0.999 ? new Vector3d(0, 1, 0) : new Vector3d(0, 0, 1);
        return new Matrix4d().setLookAt(e.x, e.y, e.z, 0, 0, 0, up.x, up.y, up.z);
    }

    /** Perspective projection in camera units (no depth test is used: near and far only bound the clip). */
    public Matrix4d projection(boolean zZeroToOne) {
        return new Matrix4d().setPerspective(Math.toRadians(FOV_Y_DEG), (double) width / height, 1.0e-6, 1.0e15, zZeroToOne);
    }

    /** Camera units of a galactic position: metres from the focus divided by the distance. */
    public Vector3d toUnits(GalacticPos p) {
        double[] d = p.metresFrom(focus);
        return new Vector3d(d[0] / distance, d[1] / distance, d[2] / distance);
    }

    /**
     * Screen position (pixels from the top-left of the viewport) and depth (camera units along the view) of a point
     * given in camera units, or null behind the eye.
     */
    public double[] project(Vector3d units) {
        Matrix4d vp = viewProjection();
        double x = units.x, y = units.y, z = units.z;
        Vector4d c = new Vector4d(vp.m00() * x + vp.m10() * y + vp.m20() * z + vp.m30(), vp.m01() * x + vp.m11() * y + vp.m21() * z + vp.m31(),
                vp.m02() * x + vp.m12() * y + vp.m22() * z + vp.m32(), vp.m03() * x + vp.m13() * y + vp.m23() * z + vp.m33());
        if (c.w <= 1e-12) return null;
        double nx = c.x / c.w, ny = c.y / c.w;
        return new double[] {(nx + 1) * 0.5 * width, (1 - ny) * 0.5 * height, c.w};
    }

    private Matrix4d vpCache;
    private double vpYaw = Double.NaN, vpPitch, vpW, vpH;

    /** projection × view (GL depth convention: only x, y and w are used), cached until the view or viewport changes. */
    public Matrix4d viewProjection() {
        if (vpCache == null || vpYaw != yaw || vpPitch != pitch || vpW != width || vpH != height) {
            vpCache = projection(false).mul(view());
            vpYaw = yaw;
            vpPitch = pitch;
            vpW = width;
            vpH = height;
        }
        return vpCache;
    }

    public double[] project(GalacticPos p) {
        return project(toUnits(p));
    }

    /** Pixels a length of {@code metres} spans at the focus's depth. */
    public double pixelsPerMetreAtFocus() {
        return height / (2 * distance * Math.tan(Math.toRadians(FOV_Y_DEG) / 2));
    }

    /** Pixels a sphere of radius {@code radiusMetres} at camera depth {@code depthUnits} spans (its radius on screen). */
    public double pixelRadius(double radiusMetres, double depthUnits) {
        if (depthUnits <= 0) return 0;
        return radiusMetres / distance / depthUnits * height / (2 * Math.tan(Math.toRadians(FOV_Y_DEG) / 2));
    }
}
