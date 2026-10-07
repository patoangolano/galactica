package dev.galactica.client.planet;

import com.mojang.blaze3d.platform.NativeImage;
import dev.galactica.planet.PlanetChunkGenerator;
import dev.galactica.planet.SpaceRelief;
import dev.galactica.planet.SurfaceGrid;
import dev.galactica.planet.TerrainSampler;
import dev.galactica.science.Blackbody;
import dev.galactica.sky.PlanetImager;
import dev.galactica.system.Body;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

/**
 * What the ground looks like from far away, baked from the real generator: one clipmap shared by
 * the surface horizon ({@link FarTerrainRenderer}) and the approach from space
 * ({@link dev.galactica.client.sky.NearPlanetRenderer}), so the terrain drawn before you cross is the
 * terrain that generates after.
 *
 * <p><b>Layout.</b> {@link #LEVELS} nested square levels of {@link #N}² texels around a centre on the
 * surface map, level k at {@code 8·4^k} blocks per texel (8 … 8192: 2 k to 2.1 M blocks across), plus
 * a whole-body equirectangular globe ({@link #GLOBE_W}×{@link #GLOBE_H}) and its cloud layer. Every
 * texel is one real column: {@link TerrainSampler#height} and {@link TerrainSampler#column}, coloured
 * by the generated block's map colour rescaled to a measured broadband albedo, and hillshaded from
 * the baked heights. Texels are stored like the orbital imager's pixels (linear albedo × exposure,
 * sRGB-encoded) so the two meet without a colour step. Heights are kept on the CPU for the horizon
 * mesh.
 *
 * <p><b>Threads.</b> Bakes run on one background thread that fans rows out to the common pool
 * (~65 k columns a level, roughly 0.1–0.3 s). Finished bakes are uploaded on the render thread by
 * {@link #upload()}, which callers run before opening a render pass (uploads inside a pass crash).
 * A level re-centres when its centre is more than an eighth of its span away, snapped to a 16-texel
 * grid so the texels never swim.
 */
public final class SurfaceClipmap {

    public static final int N = 256, LEVELS = 6;
    public static final double BASE_SPACING = 8;
    /** Whole-body globe: 1024 × 512, ~10 km per texel on the Moon, ~40 km on Earth. */
    public static final int GLOBE_W = 1024, GLOBE_H = 512;
    /** Texels of alpha fade at a level's border, so a finer level melts into the coarser one below. */
    static final int FADE = 12;
    private static final int UPLOADS_PER_FRAME = 2;

    private static final ExecutorService BAKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Galactica clipmap baker");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static SurfaceClipmap current;

    /** One finished bake: texel colours (ARGB) and heights, centred on (cx, cz); {@code lit} may be null. */
    record Baked(double cx, double cz, int[] argb, float[] heights, int[] lit) {}

    /**
     * What a sun-lit image of an airless body is made from, per texel: linear albedo with an
     * emission flag (4 floats: r, g, b, 1 for self-luminous lava), the {@link SpaceRelief} slope
     * plus the block terrain's own slope (3 floats, body-fixed), the unit sphere normal (3 floats,
     * body-fixed) and the texel's alpha. Immutable once published.
     */
    record SpaceInputs(double cx, double cz, float[] albedo, float[] slope, float[] normal, float[] alpha) {}

    /** A relit image of {@link SpaceInputs} centred on (cx, cz). */
    record Lit(double cx, double cz, int[] argb) {}

    /**
     * The light of a space view in the body-fixed frame ({@link dev.galactica.planet.PlanetMacro}'s):
     * unit vector to the star and the eye's position in body radii.
     */
    public record SpaceLight(double sx, double sy, double sz, double ex, double ey, double ez) {
        /** True when this light makes a visibly different image from {@code o}: 0.2° of sun or view, 2 % of range. */
        boolean differs(SpaceLight o) {
            if (o == null) return true;
            double ds = sx * o.sx + sy * o.sy + sz * o.sz;
            double ea = Math.sqrt(ex * ex + ey * ey + ez * ez), eb = Math.sqrt(o.ex * o.ex + o.ey * o.ey + o.ez * o.ez);
            double de = (ex * o.ex + ey * o.ey + ez * o.ez) / (ea * eb);
            double cos = Math.cos(Math.toRadians(0.2));
            return ds < cos || de < cos || Math.abs(ea - eb) > 0.02 * Math.min(ea, eb);
        }
    }

    /** A clipmap level as drawn this frame (what was last uploaded). */
    public static final class Level {
        public final int index;
        public final double spacing;
        DynamicTexture texture;
        double cx = Double.NaN, cz = Double.NaN;
        float[] heights;
        volatile Baked done;
        volatile boolean pending;
        double wantCx = Double.NaN, wantCz = Double.NaN;
        /** Sun-lit image for space views of airless bodies (see {@link #litTexture()}). */
        DynamicTexture lit;
        double litCx = Double.NaN, litCz = Double.NaN;
        volatile SpaceInputs space;
        volatile Lit litDone;

        Level(int index) {
            this.index = index;
            this.spacing = BASE_SPACING * Math.pow(4, index);
        }

        public boolean ready() {
            return texture != null && heights != null;
        }

        public DynamicTexture texture() {
            return texture;
        }

        /**
         * The sun-lit image of this level (airless bodies from space), lit per texel with crater relief
         * for the current star and eye; null until one matching the uploaded level is ready.
         */
        public DynamicTexture litTexture() {
            return lit != null && litCx == cx && litCz == cz ? lit : null;
        }

        public double centreX() {
            return cx;
        }

        public double centreZ() {
            return cz;
        }

        /** Width of the level in blocks. */
        public double span() {
            return spacing * N;
        }

        /** Texture coordinate (0…1) of map x; texel centres at (i + ½)/N. */
        public double u(double x) {
            return (x - cx) / span() + 0.5;
        }

        public double v(double z) {
            return (z - cz) / span() + 0.5;
        }

        /** Bilinear height at map (x, z), or NaN outside the level. */
        public double height(double x, double z) {
            if (heights == null) return Double.NaN;
            double fx = (x - cx) / spacing + N / 2.0 - 0.5, fz = (z - cz) / spacing + N / 2.0 - 0.5;
            if (fx < 0 || fz < 0 || fx > N - 1 || fz > N - 1) return Double.NaN;
            int i = Math.min(N - 2, (int) fx), j = Math.min(N - 2, (int) fz);
            double a = fx - i, b = fz - j;
            float[] h = heights;
            double h0 = h[j * N + i] * (1 - a) + h[j * N + i + 1] * a;
            double h1 = h[(j + 1) * N + i] * (1 - a) + h[(j + 1) * N + i + 1] * a;
            return h0 * (1 - b) + h1 * b;
        }
    }

    private final Body body;
    private final SurfaceGrid grid;
    private final TerrainSampler terrain;
    private final Level[] levels = new Level[LEVELS];
    private DynamicTexture globe, clouds;
    private volatile int[] globeArgb, cloudArgb;
    private volatile boolean globePending;
    private boolean globeUploaded;
    private volatile boolean released;
    private int generation;
    /** Airless solid bodies get sun-lit relief images for the view from space. */
    private final boolean spaceLit;
    private final SpaceRelief relief;
    private volatile SpaceInputs globeSpace;
    private volatile Lit globeLitDone;
    private DynamicTexture globeLit;
    /** The light last asked for, and the one the running or last relight used. */
    private volatile SpaceLight light;
    private SpaceLight relitFor;
    private volatile boolean relighting;

    /** Bumped by every upload: renderers rebuild their meshes when it changes. */
    public int generation() {
        return generation;
    }

    private SurfaceClipmap(Body body) {
        this.body = body;
        this.grid = SurfaceGrid.of(body.radiusMetres());
        this.terrain = TerrainSampler.of(body, grid);
        for (int k = 0; k < LEVELS; k++) levels[k] = new Level(k);
        this.spaceLit = spaceLit(body);
        this.relief = SpaceRelief.of(body);
    }

    /**
     * True for bodies whose view from space is lit per texel ({@link #globeLit}, {@link Level#litTexture}):
     * solid, with too little air (≤ 0.05 bar, the near renderer's airless photometry) to hide relief.
     */
    public static boolean spaceLit(Body b) {
        return b.type().solidSurface && !(b.surfacePressureBar() > 0.05);
    }

    public boolean spaceLit() {
        return spaceLit;
    }

    /** The sun-lit globe (airless bodies), or null until the first lit image is uploaded. */
    public DynamicTexture globeLit() {
        return globeUploaded ? globeLit : null;
    }

    /**
     * Asks for sun-lit images under this light (body-fixed). Cheap when nothing moved visibly; a
     * relight of the globe and the levels runs on the baker thread and lands through {@link #upload()}.
     */
    public void requestLight(SpaceLight l) {
        if (!spaceLit) return;
        light = l;
        if (relighting || !l.differs(relitFor)) return;
        relighting = true;
        relitFor = l;
        BAKER.execute(() -> {
            try {
                if (released) return;
                SpaceInputs g = globeSpace;
                if (g != null) globeLitDone = new Lit(0, 0, relight(g, l));
                for (Level lv : levels) {
                    SpaceInputs in = lv.space;
                    if (in != null) lv.litDone = new Lit(in.cx(), in.cz(), relight(in, l));
                }
            } finally {
                relighting = false;
            }
        });
    }

    /** The clipmap of this body, creating it (and releasing another body's) as needed. Render thread. */
    public static SurfaceClipmap of(Body body) {
        if (current != null && current.body.id() == body.id()) return current;
        if (current != null) current.release();
        current = new SurfaceClipmap(body);
        return current;
    }

    private static DynamicTexture white;

    /** A 4×4 opaque white texture, for vertex-coloured sky and haze layers. Render thread. */
    public static DynamicTexture white() {
        if (white == null) {
            NativeImage img = new NativeImage(4, 4, true);
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) img.setPixel(x, y, 0xFFFFFFFF);
            white = new DynamicTexture(() -> "galactica:white", img);
        }
        return white;
    }

    /** The live clipmap, or null. */
    public static SurfaceClipmap current() {
        return current;
    }

    /** Frees every texture; pending bakes are discarded. Render thread. */
    public static void releaseAll() {
        if (current != null) current.release();
        current = null;
    }

    public Body body() {
        return body;
    }

    public SurfaceGrid grid() {
        return grid;
    }

    public TerrainSampler terrain() {
        return terrain;
    }

    public Level level(int k) {
        return levels[k];
    }

    /**
     * True when level k fits on the body: a level wider than half the map circumference would wrap
     * more than half a lap and fold over itself on the sphere (Phobos' whole map is 69 k blocks), so
     * it is neither baked nor drawn.
     */
    public boolean usable(int k) {
        return levels[k].span() <= grid.width() / 2;
    }

    /** The coarsest {@linkplain #usable usable} level, or −1 on a body too small for any. */
    public int coarsestUsable() {
        for (int k = LEVELS - 1; k >= 0; k--) if (usable(k)) return k;
        return -1;
    }

    public DynamicTexture globe() {
        return globeUploaded ? globe : null;
    }

    public DynamicTexture clouds() {
        return globeUploaded ? clouds : null;
    }

    /**
     * Asks for levels {@code minLevel}…{@link #LEVELS}−1 centred near map point (x, z) and for the
     * globe. Cheap when nothing moved; bakes run in the background.
     */
    public void request(double x, double z, int minLevel) {
        if (!globePending && globeArgb == null) {
            globePending = true;
            BAKER.execute(this::bakeGlobe);
        }
        for (int k = Math.max(0, minLevel); k < LEVELS; k++) {
            if (!usable(k)) break;
            Level l = levels[k];
            double snap = l.spacing * 16;
            double cx = grid.wrapX(Math.round(x / snap) * snap), cz = Math.round(z / snap) * snap;
            double reach = l.span() / 8;
            boolean far = Double.isNaN(l.wantCx) || Math.abs(grid.wrapX(cx - l.wantCx)) > reach || Math.abs(cz - l.wantCz) > reach;
            if (far && !l.pending) {
                l.pending = true;
                l.wantCx = cx;
                l.wantCz = cz;
                BAKER.execute(() -> bakeLevel(l, cx, cz));
            }
        }
    }

    /** Uploads finished bakes to their textures. Call on the render thread before any pass opens. */
    public void upload() {
        int budget = UPLOADS_PER_FRAME;
        if (!globeUploaded && globeArgb != null && cloudArgb != null) {
            globe = texture(globe, "galactica:clipmap/globe", GLOBE_W, GLOBE_H, globeArgb);
            clouds = texture(clouds, "galactica:clipmap/clouds", GLOBE_W, GLOBE_H, cloudArgb);
            globeUploaded = true;
            budget--;
            generation++;
        }
        Lit gl = globeLitDone;
        if (globeUploaded && gl != null) {
            globeLitDone = null;
            boolean fresh = globeLit == null;
            globeLit = texture(globeLit, "galactica:clipmap/globe_lit", GLOBE_W, GLOBE_H, gl.argb());
            // The mesh picks the lit globe up on a rebuild; a relight into the same texture needs none.
            if (fresh) generation++;
        }
        // Coarse levels first: they cover the most screen.
        for (int k = LEVELS - 1; k >= 0 && budget > 0; k--) {
            Level l = levels[k];
            Baked b = l.done;
            if (b == null) continue;
            l.done = null;
            l.texture = texture(l.texture, "galactica:clipmap/" + k, N, N, b.argb());
            l.heights = b.heights();
            l.cx = b.cx();
            l.cz = b.cz();
            if (b.lit() != null) {
                l.lit = texture(l.lit, "galactica:clipmap/lit" + k, N, N, b.lit());
                l.litCx = b.cx();
                l.litCz = b.cz();
            }
            budget--;
            generation++;
        }
        // Relit levels: only an image of the level as uploaded is used.
        for (Level l : levels) {
            Lit lt = l.litDone;
            if (lt == null || l.texture == null) continue;
            if (lt.cx() != l.cx || lt.cz() != l.cz) {
                if (l.done == null) l.litDone = null; // stale: the level moved on, its next bake brings its own
                continue;
            }
            l.litDone = null;
            boolean fresh = l.litTexture() == null;
            l.lit = texture(l.lit, "galactica:clipmap/lit" + l.index, N, N, lt.argb());
            l.litCx = lt.cx();
            l.litCz = lt.cz();
            if (fresh) generation++;
        }
    }

    /** Height at map (x, z) from the finest uploaded level that holds it, or NaN. */
    public double height(double x, double z) {
        for (Level l : levels) {
            if (!l.ready() || !usable(l.index)) continue;
            double dx = grid.wrapX(x - l.cx);
            double h = l.height(l.cx + dx, z);
            if (!Double.isNaN(h)) return h;
        }
        return Double.NaN;
    }

    private void release() {
        released = true;
        for (Level l : levels) {
            if (l.texture != null) l.texture.close();
            if (l.lit != null) l.lit.close();
            l.texture = l.lit = null;
            l.heights = null;
            l.space = null;
        }
        if (globe != null) globe.close();
        if (clouds != null) clouds.close();
        if (globeLit != null) globeLit.close();
        globe = clouds = globeLit = null;
        globeSpace = null;
        globeUploaded = false;
    }

    private static DynamicTexture texture(DynamicTexture t, String name, int w, int h, int[] argb) {
        if (t == null) t = new DynamicTexture(() -> name, new NativeImage(w, h, true));
        NativeImage img = t.getPixels();
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) img.setPixel(x, y, argb[row + x]);
        }
        t.upload();
        return t;
    }

    // ---------------------------------------------------------------- baking (background threads)

    private void bakeLevel(Level l, double cx, double cz) {
        try {
            if (released) return;
            double s = l.spacing;
            float[] h = new float[N * N];
            IntStream.range(0, N).parallel().forEach(j -> {
                for (int i = 0; i < N; i++) {
                    double[] p = mapPoint(cx + (i - N / 2 + 0.5) * s, cz + (j - N / 2 + 0.5) * s);
                    h[j * N + i] = terrain.height(p[0], p[1]);
                }
            });
            int[] argb = new int[N * N];
            IntStream.range(0, N).parallel().forEach(j -> {
                TerrainSampler.Column c = new TerrainSampler.Column();
                for (int i = 0; i < N; i++) {
                    double[] p = mapPoint(cx + (i - N / 2 + 0.5) * s, cz + (j - N / 2 + 0.5) * s);
                    float hc = h[j * N + i];
                    float hl = h[j * N + Math.max(0, i - 1)], hr = h[j * N + Math.min(N - 1, i + 1)];
                    float hu = h[Math.max(0, j - 1) * N + i], hd = h[Math.min(N - 1, j + 1) * N + i];
                    int slope = (int) Math.round(Math.max(Math.max(Math.abs(hl - hc), Math.abs(hr - hc)),
                            Math.max(Math.abs(hu - hc), Math.abs(hd - hc))) / s);
                    terrain.column((int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) hc, slope, c);
                    double shade = hillshade((hr - hl) / (2 * s), (hd - hu) / (2 * s));
                    int edge = Math.min(Math.min(i, j), Math.min(N - 1 - i, N - 1 - j));
                    double alpha = Math.min(1, edge / (double) FADE);
                    argb[j * N + i] = texel(c, hc, shade, alpha);
                }
            });
            int[] lit = null;
            if (spaceLit) {
                SpaceInputs in = levelInputs(cx, cz, s, h);
                l.space = in;
                SpaceLight now = light;
                if (now != null) lit = relight(in, now);
            }
            if (!released) l.done = new Baked(cx, cz, argb, h, lit);
        } finally {
            l.pending = false;
        }
    }

    /** Offline previews and tests: a clipmap that is never registered as {@link #current()}. */
    static SurfaceClipmap detached(Body b) {
        return new SurfaceClipmap(b);
    }

    /** Offline: bakes the globe on the calling thread and returns its sun-lit inputs (null unless {@link #spaceLit}). */
    SpaceInputs bakeGlobeNow() {
        bakeGlobe();
        return globeSpace;
    }

    /** Offline: bakes level k centred on map (cx, cz) on the calling thread and returns its sun-lit inputs. */
    SpaceInputs bakeLevelNow(int k, double cx, double cz) {
        bakeLevel(levels[k], cx, cz);
        return levels[k].space;
    }

    private void bakeGlobe() {
        if (released) return;
        int[] g = new int[GLOBE_W * GLOBE_H], cl = new int[GLOBE_W * GLOBE_H];
        int n = GLOBE_W * GLOBE_H;
        float[] gAlb = spaceLit ? new float[n * 4] : null, gSlope = spaceLit ? new float[n * 3] : null,
                gNrm = spaceLit ? new float[n * 3] : null;
        IntStream.range(0, GLOBE_H).parallel().forEach(j -> {
            TerrainSampler.Column c = new TerrainSampler.Column();
            double lat = Math.PI / 2 - (j + 0.5) / GLOBE_H * Math.PI;
            for (int i = 0; i < GLOBE_W; i++) {
                double lon = -Math.PI + (i + 0.5) / GLOBE_W * 2 * Math.PI;
                double x = grid.blockX(lon), z = grid.blockZ(lat);
                int h = terrain.height(x, z);
                terrain.column((int) Math.floor(x), (int) Math.floor(z), h, 0, c);
                g[j * GLOBE_W + i] = texel(c, h, 1, 1);
                if (spaceLit) globeInput(c, x, z, lat, lon, j * GLOBE_W + i, gAlb, gSlope, gNrm);
                double cosLat = Math.cos(lat);
                double cov = PlanetImager.cloudCover(body, cosLat * Math.cos(lon), cosLat * Math.sin(lon), Math.sin(lat));
                cl[j * GLOBE_W + i] = ((int) Math.round(Math.min(1, cov) * 255) << 24) | 0xF4F6F8;
            }
        });
        if (released) return;
        if (spaceLit) {
            float[] ones = new float[n];
            java.util.Arrays.fill(ones, 1f);
            SpaceInputs in = new SpaceInputs(0, 0, gAlb, gSlope, gNrm, ones);
            globeSpace = in;
            SpaceLight now = light;
            if (now != null) globeLitDone = new Lit(0, 0, relight(in, now));
        }
        cloudArgb = cl;
        globeArgb = g;
    }

    // ---------------------------------------------------------------- sun-lit space images

    /**
     * Globe texel inputs: material albedo averaged over the texel × crater albedo, relief slope at the
     * globe's texel scale.
     */
    private void globeInput(TerrainSampler.Column c, double x, double z, double lat, double lon, int t, float[] alb, float[] slope, float[] nrm) {
        double cl = Math.cos(lat);
        double nx = cl * Math.cos(lon), ny = cl * Math.sin(lon), nz = Math.sin(lat);
        double[] sl = new double[3];
        double mare = areaAlbedo(c, x, z, grid.width() / GLOBE_W, grid.width() / 2 / GLOBE_H, alb, t);
        // Texels shrink towards the poles in longitude only: the latitude spacing sets the scale.
        double fac = relief.sample(nx, ny, nz, Math.PI / GLOBE_H, true, 1 - 0.7 * mare, sl);
        scaleAlbedo(alb, t, fac);
        for (int k = 0; k < 3; k++) slope[t * 3 + k] = (float) sl[k];
        nrm[t * 3] = (float) nx;
        nrm[t * 3 + 1] = (float) ny;
        nrm[t * 3 + 2] = (float) nz;
    }

    /**
     * Level texel inputs: the same as the globe's at this level's texel scale, plus the block
     * terrain's own slope (heights in blocks over blocks, as you walk it), along the directions the
     * texture's rows and columns actually run on the sphere (which also handles the fold over a pole).
     */
    private SpaceInputs levelInputs(double cx, double cz, double s, float[] h) {
        float[] alb = new float[N * N * 4], slope = new float[N * N * 3], nrm = new float[N * N * 3], alpha = new float[N * N];
        IntStream.range(0, N).parallel().forEach(j -> {
            for (int i = 0; i < N; i++) {
                double[] p = mapPoint(cx + (i - N / 2 + 0.5) * s, cz + (j - N / 2 + 0.5) * s);
                double lat = grid.latitude(p[1]), lon = grid.longitude(p[0]), cl = Math.cos(lat);
                int t = j * N + i;
                nrm[t * 3] = (float) (cl * Math.cos(lon));
                nrm[t * 3 + 1] = (float) (cl * Math.sin(lon));
                nrm[t * 3 + 2] = (float) Math.sin(lat);
            }
        });
        double footprint = s / grid.radiusBlocks();
        IntStream.range(0, N).parallel().forEach(j -> {
            TerrainSampler.Column c = new TerrainSampler.Column();
            double[] sl = new double[3];
            for (int i = 0; i < N; i++) {
                int t = j * N + i;
                double[] p = mapPoint(cx + (i - N / 2 + 0.5) * s, cz + (j - N / 2 + 0.5) * s);
                double nx = nrm[t * 3], ny = nrm[t * 3 + 1], nz = nrm[t * 3 + 2];
                int il = Math.max(0, i - 1), ir = Math.min(N - 1, i + 1), ju = Math.max(0, j - 1), jd = Math.min(N - 1, j + 1);
                double mare = areaAlbedo(c, p[0], p[1], s, s, alb, t);
                double fac = relief.sample(nx, ny, nz, footprint, true, 1 - 0.7 * mare, sl);
                scaleAlbedo(alb, t, fac);
                // Block slope along the texture's +i and +j directions on the sphere.
                addSlope(sl, nrm, j * N + il, j * N + ir, (h[j * N + ir] - h[j * N + il]) / ((ir - il) * s));
                addSlope(sl, nrm, ju * N + i, jd * N + i, (h[jd * N + i] - h[ju * N + i]) / ((jd - ju) * s));
                double dn = sl[0] * nx + sl[1] * ny + sl[2] * nz;
                slope[t * 3] = (float) (sl[0] - dn * nx);
                slope[t * 3 + 1] = (float) (sl[1] - dn * ny);
                slope[t * 3 + 2] = (float) (sl[2] - dn * nz);
                int edge = Math.min(Math.min(i, j), Math.min(N - 1 - i, N - 1 - j));
                alpha[t] = (float) Math.min(1, edge / (double) FADE);
            }
        });
        return new SpaceInputs(cx, cz, alb, slope, nrm, alpha);
    }

    /** Adds {@code grade} (rise over run) along the unit direction from texel a to texel b. */
    private static void addSlope(double[] sl, float[] nrm, int a, int b, double grade) {
        double dx = nrm[b * 3] - nrm[a * 3], dy = nrm[b * 3 + 1] - nrm[a * 3 + 1], dz = nrm[b * 3 + 2] - nrm[a * 3 + 2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(len > 0) || !Double.isFinite(grade)) return;
        sl[0] += grade * dx / len;
        sl[1] += grade * dy / len;
        sl[2] += grade * dz / len;
    }

    /**
     * Linear albedo of the ground a texel covers, averaged over a 3 × 3 grid of columns across its
     * {@code du} × {@code dv} blocks: the generator's block-scale materials (dirty-ice speckle, the
     * edge of a mare) would otherwise alias into salt-and-pepper noise at kilometre texels. Lava
     * makes the texel self-luminous (flag set, colour pre-divided by the exposure). Returns the
     * share of mare basalt.
     */
    private double areaAlbedo(TerrainSampler.Column c, double x, double z, double du, double dv, float[] out, int t) {
        double r = 0, g = 0, b = 0, mare = 0;
        int lava = 0;
        for (int sj = -1; sj <= 1; sj++) {
            for (int si = -1; si <= 1; si++) {
                double[] p = mapPoint(x + si * du / 3, z + sj * dv / 3);
                int h = terrain.height(p[0], p[1]);
                terrain.column((int) Math.floor(p[0]), (int) Math.floor(p[1]), h, 0, c);
                TerrainSampler.Material m = c.visible();
                if (m == TerrainSampler.Material.LAVA) {
                    lava++;
                    r += Albedo.LAVA[0] * 0.9 / PlanetImager.EXPOSURE;
                    g += Albedo.LAVA[1] * 0.9 / PlanetImager.EXPOSURE;
                    b += Albedo.LAVA[2] * 0.9 / PlanetImager.EXPOSURE;
                    continue;
                }
                if (m == TerrainSampler.Material.BASALT || m == TerrainSampler.Material.SMOOTH_BASALT) mare++;
                double[] a = m == TerrainSampler.Material.WATER ? WATER_ALBEDO : Albedo.TABLE[m.ordinal()];
                if (a == null) a = DEFAULT_ALBEDO;
                r += a[0];
                g += a[1];
                b += a[2];
            }
        }
        out[t * 4] = (float) (r / 9);
        out[t * 4 + 1] = (float) (g / 9);
        out[t * 4 + 2] = (float) (b / 9);
        out[t * 4 + 3] = lava >= 5 ? 1 : 0;
        return mare / 9;
    }

    private static final double[] WATER_ALBEDO = {0.02, 0.06, 0.10}, DEFAULT_ALBEDO = {0.1, 0.1, 0.1};

    /** Applies the crater albedo factor to a non-emissive texel. */
    private static void scaleAlbedo(float[] alb, int t, double fac) {
        if (alb[t * 4 + 3] > 0) return;
        for (int k = 0; k < 3; k++) alb[t * 4 + k] *= (float) fac;
    }

    /** Dim light on the night side, as the near renderer's vertex lighting adds. */
    static final double NIGHT_LIGHT = 0.004;

    /**
     * One sun-lit image: per texel the lit normal normalize(n − slope) and the regolith reflectance
     * ({@link SpaceRelief#regolith}) for the star and the eye, times albedo and the imager's exposure;
     * the star's colour and the auto-exposure are the mesh's vertex colour.
     */
    static int[] relight(SpaceInputs in, SpaceLight l) {
        int n = in.normal().length / 3;
        int[] out = new int[n];
        float[] alb = in.albedo(), sl = in.slope(), nr = in.normal(), al = in.alpha();
        IntStream.range(0, n).parallel().forEach(t -> {
            int a8 = (int) Math.round(Math.max(0, Math.min(1, al[t])) * 255);
            double r = alb[t * 4], g = alb[t * 4 + 1], b = alb[t * 4 + 2];
            double k;
            if (alb[t * 4 + 3] > 0) {
                k = PlanetImager.EXPOSURE;
            } else {
                double nx = nr[t * 3], ny = nr[t * 3 + 1], nz = nr[t * 3 + 2];
                double lx = nx - sl[t * 3], ly = ny - sl[t * 3 + 1], lz = nz - sl[t * 3 + 2];
                double ln = Math.sqrt(lx * lx + ly * ly + lz * lz);
                lx /= ln;
                ly /= ln;
                lz /= ln;
                double mu0 = lx * l.sx() + ly * l.sy() + lz * l.sz();
                double vx = l.ex() - nx, vy = l.ey() - ny, vz = l.ez() - nz;
                double vn = Math.sqrt(vx * vx + vy * vy + vz * vz);
                double mu = (lx * vx + ly * vy + lz * vz) / Math.max(1e-9, vn);
                k = PlanetImager.EXPOSURE * (SpaceRelief.regolith(mu0, mu) + NIGHT_LIGHT);
            }
            out[t] = a8 << 24 | PlanetImager.encode8(r * k) << 16 | PlanetImager.encode8(g * k) << 8 | PlanetImager.encode8(b * k);
        });
        return out;
    }

    /** Map point folded back inside the poles (a level near a pole reaches over it). */
    private double[] mapPoint(double x, double z) {
        double[] over = grid.overPole(x, z);
        return over != null ? over : new double[] {grid.wrapX(x), z};
    }

    /**
     * Lambertian hillshade relative to flat ground under a fixed light 45° high from the north-west,
     * the cartographic convention (Imhof 1982): 1 on the flat, brighter on slopes facing the light.
     */
    static double hillshade(double dhdx, double dhdz) {
        double lx = -0.5, ly = 0.7071, lz = -0.5;
        double nx = -dhdx, ny = 1, nz = -dhdz;
        double n = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double d = (nx * lx + ny * ly + nz * lz) / n;
        return Math.max(0.35, Math.min(1.6, d / ly));
    }

    // ---------------------------------------------------------------- colour

    /**
     * Broadband albedo of each palette material, matched to the orbital imager's palette (itself
     * from measured albedos: vegetation 0.07–0.1, deserts 0.25–0.3, fresh snow 0.75–0.8, lunar
     * highlands 0.16 vs maria 0.07, Martian bright 0.28 vs dark 0.08, Io's sulfur plains 0.55).
     */
    private static double targetAlbedo(TerrainSampler.Material m) {
        return switch (m) {
            case GRASS_BLOCK -> 0.075;
            case DIRT -> 0.11;
            case COARSE_DIRT -> 0.08;
            case SAND -> 0.28;
            case SANDSTONE -> 0.26;
            case RED_SAND -> 0.28;
            case TERRACOTTA -> 0.22;
            case STONE -> 0.13;
            case GRAVEL -> 0.16;
            case TUFF -> 0.14;
            case SNOW_BLOCK -> 0.76;
            case PACKED_ICE -> 0.55;
            case BLUE_ICE -> 0.5;
            case ICE -> 0.6;
            case BASALT, SMOOTH_BASALT -> 0.075;
            case BLACKSTONE -> 0.05;
            case MAGMA_BLOCK -> 0.08;
            case YELLOW_TERRACOTTA -> 0.55;
            case BEDROCK -> 0.1;
            default -> 0.1;
        };
    }

    /**
     * Linear albedo per material: the block's map colour hue at the target broadband albedo, and
     * the lava glow colour. A lazy holder (JLS §12.4.2): built once, then read lock-free by every
     * baking thread.
     */
    private static final class Albedo {
        static final double[][] TABLE = new double[TerrainSampler.Material.values().length][];
        static final double[] LAVA = new double[3];

        static {
            for (TerrainSampler.Material mat : TerrainSampler.Material.values()) {
                if (mat == TerrainSampler.Material.AIR) continue;
                int c = PlanetChunkGenerator.mapColour(mat);
                double r = decode((c >> 16) & 0xFF), g = decode((c >> 8) & 0xFF), b = decode(c & 0xFF);
                double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                double k = lum > 1e-6 ? targetAlbedo(mat) / lum : 0;
                TABLE[mat.ordinal()] = new double[] {r * k, g * k, b * k};
            }
            float[] lava = new float[3];
            Blackbody.linearRgb(1400, lava);
            for (int i = 0; i < 3; i++) LAVA[i] = lava[i];
        }
    }

    /** One texel: water by depth like the imager's seas, lava as emission, land by material and shade. */
    private int texel(TerrainSampler.Column c, double h, double shade, double alpha) {
        TerrainSampler.Material m = c.visible();
        double r, g, b;
        if (m == TerrainSampler.Material.WATER) {
            double dep = smooth((TerrainSampler.SEA_LEVEL - h) / Math.max(1, 3 * terrain.amplitude()));
            r = lerp(dep, 0.020, 0.008);
            g = lerp(dep, 0.075, 0.025);
            b = lerp(dep, 0.110, 0.075);
            shade = 1;
        } else if (m == TerrainSampler.Material.LAVA) {
            // Glow at the imager's lava strength, stored pre-divided so the exposure below restores it.
            r = Albedo.LAVA[0] * 0.9 / PlanetImager.EXPOSURE;
            g = Albedo.LAVA[1] * 0.9 / PlanetImager.EXPOSURE;
            b = Albedo.LAVA[2] * 0.9 / PlanetImager.EXPOSURE;
            shade = 1;
        } else {
            double[] a = Albedo.TABLE[m.ordinal()];
            if (a == null) a = new double[] {0.1, 0.1, 0.1};
            r = a[0];
            g = a[1];
            b = a[2];
        }
        double k = PlanetImager.EXPOSURE * shade;
        int ai = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        return ai << 24 | PlanetImager.encode8(r * k) << 16 | PlanetImager.encode8(g * k) << 8 | PlanetImager.encode8(b * k);
    }

    private static double decode(int v) {
        double c = v / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double smooth(double x) {
        double t = Math.max(0, Math.min(1, x));
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }
}
