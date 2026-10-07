package dev.galactica.client.map;

import dev.galactica.Galactica;
import dev.galactica.deepsky.DeepSky;
import dev.galactica.deepsky.DeepSkyCatalog;
import dev.galactica.deepsky.DeepSkyObject;
import dev.galactica.deepsky.DeepSkyType;
import dev.galactica.galaxy.SectorKey;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.nav.target.NavTarget;
import dev.galactica.nav.target.TargetGeometry;
import dev.galactica.nav.target.TargetSearch;
import dev.galactica.science.Units;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import dev.galactica.system.BodyType;
import dev.galactica.system.SystemFrame;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the 3D map shows in one frame, built on the render thread from the camera: every object's sprite (written for
 * the GPU relative to the focus in camera units, double-precise), its screen position for picking and labels, and the
 * orbit lines (screen polylines the screen draws as GUI lines). The streamed star field ({@link MapStarField}) is
 * static on the GPU and only projected here for picking.
 *
 * <p>Layers by zoom: the galaxy disk impostor (faded in beyond ~1 kpc), the catalog stars and named black holes, the
 * deep-sky objects (sized by their real radius, never under a few pixels), and, when the focus sits in a star system
 * and the camera is within {@link #SYSTEM_VIEW_PC}, that system: its star, planets and moons at the map's clock with
 * their orbits from the real orbital elements.
 */
final class MapScene {
    /** The system around the focus is drawn when the camera is closer than this to the focus (pc). */
    static final double SYSTEM_VIEW_PC = 0.06;
    /** Points per orbit line. */
    private static final int ORBIT_POINTS = 128;

    /** One object on screen this frame. */
    record Drawn(MapObject object, double x, double y, double depth, double radiusPx) {}

    /** A polyline on screen (pixels); {@code dashed} for a trajectory. */
    record Polyline(double[] xy, int argb, boolean dashed) {}

    final QuadWriter pxSprites = new QuadWriter(false, 512);
    final QuadWriter worldSprites = new QuadWriter(false, 512);
    final QuadWriter bodies = new QuadWriter(true, 64);
    final List<Drawn> drawn = new ArrayList<>();
    final List<Polyline> lines = new ArrayList<>();
    /** Near-cull radius (camera units) for the static star field: the focused system's own star is drawn here instead. */
    double fieldNearCull;
    /** 0..1 strength of the galaxy disk impostor. */
    double discAlpha;
    @Nullable Star system;
    int[] systemAddress;
    private final Neighbourhood nb = new Neighbourhood(Galactica.GALAXY);
    private final List<MapObject> catalog = new ArrayList<>(), deepSky = new ArrayList<>();
    private final Map<Long, double[]> orbitCache = new HashMap<>();
    private long orbitSystem = Long.MIN_VALUE;

    MapScene() {
        for (Star s : StarCatalog.all()) catalog.add(MapObject.star(s, null, NavTarget.CATALOG, s == StarCatalog.SOL ? 100 : 60));
        for (DeepSkyObject o : DeepSkyCatalog.all()) deepSky.add(deepSkyObject(o, 40));
        for (DeepSkyObject o : DeepSky.of(Galactica.GALAXY.seed()).globulars()) deepSky.add(deepSkyObject(o, 15));
    }

    private static MapObject deepSkyObject(DeepSkyObject o, double importance) {
        return new MapObject(MapObject.Kind.DEEP_SKY, o.name, null, null, 0, null, null, o, GalacticPos.ofParsecs(o.x, o.y, o.z),
                deepSkyColour(o.type), importance);
    }

    static int deepSkyColour(DeepSkyType t) {
        return switch (t) {
            case EMISSION_NEBULA -> 0xFFFF5A78;
            case REFLECTION_NEBULA -> 0xFF6A96FF;
            case DARK_NEBULA -> 0xFF6E5A48;
            case SUPERNOVA_REMNANT -> 0xFF68FFB8;
            case PLANETARY_NEBULA -> 0xFF58E8D8;
            case OPEN_CLUSTER -> 0xFFA8CCFF;
            case GLOBULAR_CLUSTER -> 0xFFFFE2A8;
            case GALAXY -> 0xFFE4D6FF;
        };
    }

    static int bodyColour(BodyType t) {
        return switch (t) {
            case LAVA -> 0xFFFF7A40;
            case BARREN -> 0xFFA8A296;
            case DESERT -> 0xFFD8884C;
            case GREENHOUSE -> 0xFFEAD9A2;
            case TERRAN -> 0xFF4F86D8;
            case OCEAN -> 0xFF3466C8;
            case ICE -> 0xFFD0E6F4;
            case SUB_NEPTUNE -> 0xFF86B6DC;
            case ICE_GIANT -> 0xFF72C4E4;
            case GAS_GIANT -> 0xFFDCB486;
            case HOT_JUPITER -> 0xFFE4925E;
        };
    }

    /**
     * Builds the frame. {@code field} is the streamed star batch (projected for picking only), {@code player} the
     * player's galactic position (null when unknown), {@code time} the map clock (the NAV clock).
     */
    void build(MapCamera cam, MapStarField.Batch field, double time, @Nullable GalacticPos player) {
        pxSprites.reset();
        worldSprites.reset();
        bodies.reset();
        drawn.clear();
        lines.clear();
        double dist = cam.distance(), distPc = dist / Units.PARSEC;
        Matrix4d view = cam.view();

        discAlpha = smooth(400, 2_500, distPc);
        if (discAlpha > 0.01) {
            // The bulge: a warm glow 1.6 kpc in radius over the disk layers, which alone look flat edge-on.
            Vector3d gc = cam.toUnits(GalacticPos.ofParsecs(0, 0, 0));
            worldSprites.sprite((float) gc.x, (float) gc.y, (float) gc.z, (float) (1_600 * Units.PARSEC / dist), 0xFFFFD49A, (float) (0.32 * discAlpha));
        }

        // The system around the focus.
        system = null;
        Star near = nb.nearestStar(cam.focus());
        if (near != null && distPc < SYSTEM_VIEW_PC && Neighbourhood.starPos(near).distanceMetres(cam.focus()) < Neighbourhood.SYSTEM_RADIUS_M) {
            system = near;
            if (orbitSystem != near.id()) {
                orbitSystem = near.id();
                orbitCache.clear();
                systemAddress = TargetSearch.addressOf(Galactica.GALAXY, near);
            }
        }
        fieldNearCull = system != null ? Neighbourhood.SYSTEM_RADIUS_M / dist : 0;

        // Streamed field: GPU already has it; project for picking.
        for (MapObject o : field.stars()) addDrawn(cam, o, MapStarField.spritePx(o.star()));

        // Catalog stars and named black holes (the system's own star is the system layer's).
        for (MapObject o : catalog) {
            if (system != null && o.star() == system) continue;
            Vector3d u = cam.toUnits(o.pos());
            double size = o.kind() == MapObject.Kind.BLACK_HOLE ? 4.0 : Math.max(2.5, MapStarField.spritePx(o.star()) + 0.8);
            pxSprites.sprite((float) u.x, (float) u.y, (float) u.z, (float) size, o.argb(), 1F);
            addDrawn(cam, o, size);
        }

        // Deep-sky objects: a soft glow at their real size, and a small core so they stay visible from afar.
        for (MapObject o : deepSky) {
            DeepSkyObject d = o.deepSky();
            Vector3d u = cam.toUnits(o.pos());
            double r = d.radiusPc * Units.PARSEC / dist;
            if (d.type != DeepSkyType.DARK_NEBULA) worldSprites.sprite((float) u.x, (float) u.y, (float) u.z, (float) r, o.argb(), 0.55F);
            pxSprites.sprite((float) u.x, (float) u.y, (float) u.z, 2.2F, o.argb(), 0.8F);
            double[] p = cam.project(u);
            if (p != null) drawn.add(new Drawn(o, p[0], p[1], p[2], Math.max(4, cam.pixelRadius(d.radiusPc * Units.PARSEC, p[2]))));
        }

        if (system != null) buildSystem(cam, view, time);
        if (player != null) {
            double[] p = cam.project(player);
            if (p != null) drawn.add(new Drawn(new MapObject(MapObject.Kind.PLAYER, "", null, null, 0, null, null, null, player, 0xFF7FE0FF, 200),
                    p[0], p[1], p[2], 5));
        }
    }

    private void addDrawn(MapCamera cam, MapObject o, double radiusPx) {
        double[] p = cam.project(o.pos());
        if (p == null || p[0] < -20 || p[1] < -20 || p[0] > cam.width() + 20 || p[1] > cam.height() + 20) return;
        drawn.add(new Drawn(o, p[0], p[1], p[2], radiusPx));
    }

    private void buildSystem(MapCamera cam, Matrix4d view, double time) {
        Star s = system;
        GalacticPos sp = Neighbourhood.starPos(s);
        SectorKey k = systemAddress == null || systemAddress[3] == NavTarget.CATALOG ? null
                : new SectorKey(systemAddress[0], systemAddress[1], systemAddress[2]);
        int index = systemAddress == null ? NavTarget.CATALOG : systemAddress[3];
        boolean addressed = systemAddress != null;

        // The star: a disc at its true size (at least 4 px) with a wide additive glow.
        MapObject starObj = new MapObject(s.kind() == dev.galactica.star.StarKind.BLACK_HOLE ? MapObject.Kind.BLACK_HOLE : MapObject.Kind.STAR,
                s.name(), s, k, index, null, null, null, sp, MapObject.star(s, k, index, 100).argb(), 100);
        Vector3d su = cam.toUnits(sp);
        double[] spr = cam.project(su);
        double starPx = spr == null ? 4 : Math.max(4, cam.pixelRadius(TargetGeometry.starRadius(s), spr[2]));
        pxSprites.sprite((float) su.x, (float) su.y, (float) su.z, (float) Math.min(300, starPx * 4 + 6), starObj.argb(), 1F);
        bodies.quad((float) su.x, (float) su.y, (float) su.z, (float) Math.min(2000, starPx), starObj.argb(), 1F, 0, 0, 0);
        if (spr != null) drawn.add(new Drawn(starObj, spr[0], spr[1], spr[2], starPx));

        for (Body planet : TargetGeometry.system(s)) {
            GalacticPos pp = TargetGeometry.bodyPos(s, sp, planet, null, time);
            drawBody(cam, view, s, k, index, addressed, planet, null, pp, sp);
            orbitLine(cam, s, sp, planet, 0x7090A8D0);
            for (Body moon : planet.moons()) {
                GalacticPos mp = TargetGeometry.bodyPos(s, sp, planet, moon, time);
                // A moon only once its orbit is resolvable: its distance from the planet over ~6 px.
                double sep = moon.orbit().semiMajorAxis() * cam.pixelsPerMetreAtFocus();
                if (sep < 6) continue;
                drawBody(cam, view, s, k, index, addressed, moon, planet, mp, sp);
                orbitLine(cam, s, pp, moon, 0x506C8098);
            }
        }
    }

    private void drawBody(MapCamera cam, Matrix4d view, Star s, @Nullable SectorKey k, int index, boolean addressed, Body b,
                          @Nullable Body parent, GalacticPos pos, GalacticPos starPos) {
        MapObject o = new MapObject(parent == null ? MapObject.Kind.PLANET : MapObject.Kind.MOON, b.name(), addressed ? s : null, k, index,
                b, parent, null, pos, bodyColour(b.type()), parent == null ? 80 : 55);
        Vector3d u = cam.toUnits(pos);
        double[] p = cam.project(u);
        if (p == null) return;
        double px = Math.max(parent == null ? 3.0 : 2.0, cam.pixelRadius(b.radiusMetres(), p[2]));
        // Light from the star, in view space (the sprite is lit like a sphere in the fragment shader).
        double[] toStar = starPos.metresFrom(pos);
        Vector3d l = view.transformDirection(new Vector3d(toStar[0], toStar[1], toStar[2])).normalize();
        bodies.quad((float) u.x, (float) u.y, (float) u.z, (float) Math.min(4000, px), o.argb(), 1F, (float) l.x, (float) l.y, (float) l.z);
        drawn.add(new Drawn(o, p[0], p[1], p[2], px));
    }

    /** The orbit of {@code b} about {@code centre}, from its elements, projected; skipped while smaller than a few pixels. */
    private void orbitLine(MapCamera cam, Star s, GalacticPos centre, Body b, int argb) {
        double[] pts = orbitCache.get(b.id());
        if (pts == null) {
            pts = new double[ORBIT_POINTS * 3];
            double[] buf = new double[3], g = new double[3];
            double period = b.orbit().periodSeconds();
            for (int i = 0; i < ORBIT_POINTS; i++) {
                b.orbit().positionAt(period * i / (ORBIT_POINTS - 1), buf);
                SystemFrame.toGalactic(s, buf[0], buf[1], buf[2], g);
                pts[i * 3] = g[0];
                pts[i * 3 + 1] = g[1];
                pts[i * 3 + 2] = g[2];
            }
            orbitCache.put(b.id(), pts);
        }
        if (b.orbit().apoapsis() * cam.pixelsPerMetreAtFocus() < 4) return;
        lines.add(new Polyline(projectPolyline(cam, centre, pts), argb, false));
    }

    /** Projects offsets (metres from {@code centre}) to screen; a point behind the eye is NaN (the screen breaks the line there). */
    static double[] projectPolyline(MapCamera cam, GalacticPos centre, double[] offsets) {
        Vector3d c = cam.toUnits(centre);
        double inv = 1 / cam.distance();
        int n = offsets.length / 3;
        double[] xy = new double[n * 2];
        for (int i = 0; i < n; i++) {
            double[] p = cam.project(new Vector3d(c.x + offsets[i * 3] * inv, c.y + offsets[i * 3 + 1] * inv, c.z + offsets[i * 3 + 2] * inv));
            xy[i * 2] = p == null ? Double.NaN : p[0];
            xy[i * 2 + 1] = p == null ? Double.NaN : p[1];
        }
        return xy;
    }

    /** The planet or moon {@code id} of the focused system as a map object, at time t (search and target focus). */
    @Nullable MapObject findBody(NavTarget t, double time) {
        TargetGeometry.Resolved r = TargetGeometry.resolve(Galactica.GALAXY, t, time);
        if (r == null || r.body() == null) return null;
        Star s = r.star();
        SectorKey k = t.index() == NavTarget.CATALOG ? null : t.sector();
        return new MapObject(r.parent() == null ? MapObject.Kind.PLANET : MapObject.Kind.MOON, r.body().name(), s, k, t.index(), r.body(),
                r.parent(), null, r.pos(), bodyColour(r.body().type()), 80);
    }

    static double smooth(double a, double b, double x) {
        double t = Math.clamp((x - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
