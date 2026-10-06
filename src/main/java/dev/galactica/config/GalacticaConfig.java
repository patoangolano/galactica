package dev.galactica.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Galactica's user settings as one immutable, validated value. Pure Java (Gson only), so it is
 * unit-tested without a game; {@link GalacticaConfigs} owns the live instance and the file.
 *
 * <p><b>How other code reads it.</b> Call {@code GalacticaConfigs.get()} at the point of use (it is
 * a volatile read of an immutable object, cheap enough for every frame) and never cache the
 * returned instance across frames. Code that bakes something expensive from a setting (sky
 * panorama width, planet texture size) registers {@code GalacticaConfigs.onChange(cfg -> ...)}
 * and re-bakes when the relevant field differs. Every getter already returns a clamped, legal value.
 *
 * <p><b>File format.</b> {@code config/galactica.json}, an object with an integer {@code version}
 * ({@link #SCHEMA_VERSION}). Unknown keys are ignored, missing keys take defaults, out-of-range
 * numbers are clamped and bad enum names fall back to the default; {@link #parse} reports every
 * such repair so the store can log it and rewrite a clean file.
 */
public final class GalacticaConfig {

    /** Bump when a field changes meaning; {@link #parse} migrates older files forward. */
    public static final int SCHEMA_VERSION = 1;

    /** Generic three-step quality for effects whose cost scales with sample/particle count. */
    public enum Quality {
        LOW, MEDIUM, HIGH;

        /** Multiplier on a renderer's baseline sample/particle budget: 0.5, 1, 2. */
        public double budgetScale() {
            return switch (this) {
                case LOW -> 0.5;
                case MEDIUM -> 1.0;
                case HIGH -> 2.0;
            };
        }
    }

    /** Engine exhaust: OFF hides plumes entirely (the ship still flies). */
    public enum ExhaustQuality {
        OFF, LOW, MEDIUM, HIGH
    }

    // ---------------------------------------------------------------- legal ranges

    /** Equirect sky bake widths (height is width/2). 4096 ≈ 5.3′ per texel, near the 1′ eye limit only at HIGH. */
    public static final int[] SKY_BAKE_WIDTHS = {1024, 2048, 4096};
    public static final int PLANET_TEXTURE_MIN = 128, PLANET_TEXTURE_MAX = 1024;
    public static final double ENTRY_EFFECTS_MIN = 0.0, ENTRY_EFFECTS_MAX = 1.0;
    /**
     * Warp cap in parsecs per second, the same range {@code /galactica maxwarp} accepts. With
     * 1 pc = 3.0857 × 10^16 m (IAU 2015 B2) and c = 2.9979 × 10^8 m/s, 1 pc/s ≈ 1.03 × 10^8 c: the
     * upper bound 1000 pc/s ≈ 1.0 × 10^11 c; the lower bound 1e-9 pc/s ≈ 3.1 × 10^4 km/s ≈ 0.10 c
     * (1 AU in about 81 minutes), effectively "warp off".
     */
    public static final double WARP_MIN_PC_PER_S = 1e-9, WARP_MAX_PC_PER_S = 1000.0;
    public static final double HUD_SCALE_MIN = 0.5, HUD_SCALE_MAX = 2.0;

    // ---------------------------------------------------------------- fields

    private final int skyBakeWidth;
    private final int planetTextureMax;
    private final boolean starPlasma;
    private final Quality starPlasmaQuality;
    private final ExhaustQuality exhaustQuality;
    private final double entryEffects;
    private final boolean realNightSky;
    private final boolean seamlessTransitions;
    private final double warpMaxPcPerS;
    private final double hudScale;
    private final boolean showLegend;

    private GalacticaConfig(Builder b) {
        this.skyBakeWidth = snapSkyWidth(b.skyBakeWidth);
        this.planetTextureMax = snapPow2(b.planetTextureMax, PLANET_TEXTURE_MIN, PLANET_TEXTURE_MAX);
        this.starPlasma = b.starPlasma;
        this.starPlasmaQuality = b.starPlasmaQuality == null ? Quality.MEDIUM : b.starPlasmaQuality;
        this.exhaustQuality = b.exhaustQuality == null ? ExhaustQuality.MEDIUM : b.exhaustQuality;
        this.entryEffects = clamp(b.entryEffects, ENTRY_EFFECTS_MIN, ENTRY_EFFECTS_MAX, 1.0);
        this.realNightSky = b.realNightSky;
        this.seamlessTransitions = b.seamlessTransitions;
        this.warpMaxPcPerS = clamp(b.warpMaxPcPerS, WARP_MIN_PC_PER_S, WARP_MAX_PC_PER_S, 10.0);
        this.hudScale = clamp(b.hudScale, HUD_SCALE_MIN, HUD_SCALE_MAX, 1.0);
        this.showLegend = b.showLegend;
    }

    public static GalacticaConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.skyBakeWidth = skyBakeWidth;
        b.planetTextureMax = planetTextureMax;
        b.starPlasma = starPlasma;
        b.starPlasmaQuality = starPlasmaQuality;
        b.exhaustQuality = exhaustQuality;
        b.entryEffects = entryEffects;
        b.realNightSky = realNightSky;
        b.seamlessTransitions = seamlessTransitions;
        b.warpMaxPcPerS = warpMaxPcPerS;
        b.hudScale = hudScale;
        b.showLegend = showLegend;
        return b;
    }

    // ---------------------------------------------------------------- getters

    /** Sky panorama bake width in texels: 1024, 2048 or 4096. Height is half of it. */
    public int skyBakeWidth() {
        return skyBakeWidth;
    }

    /** Largest planet/moon surface texture edge, a power of two in [128, 1024]. */
    public int planetTextureMax() {
        return planetTextureMax;
    }

    /** Animated stellar photosphere (granulation, limb darkening) instead of a flat disc. */
    public boolean starPlasma() {
        return starPlasma;
    }

    public Quality starPlasmaQuality() {
        return starPlasmaQuality;
    }

    public ExhaustQuality exhaustQuality() {
        return exhaustQuality;
    }

    /** Re-entry plasma/heating overlay strength, 0 (off) to 1 (physical). */
    public double entryEffects() {
        return entryEffects;
    }

    /** Use the real Earth night sky (catalogued stars, Milky Way band) on Earth's surface. */
    public boolean realNightSky() {
        return realNightSky;
    }

    /** Mask dimension switches with approach tickets and the cross-fade (research plan B). */
    public boolean seamlessTransitions() {
        return seamlessTransitions;
    }

    /**
     * Default warp-drive cap in pc/s (× {@code Units.PARSEC} for m/s). <b>Server-side default
     * only:</b> the live cap is per player, in the {@code Galactica.NAV} attachment
     * ({@code withMaxSpeed}), and {@code /galactica maxwarp} overrides it. Server code reads this
     * value only when it first creates a player's NAV attachment, from the config file of the
     * physical side running the server: the integrated server in singleplayer, the dedicated
     * server's own {@code config/galactica.json} in multiplayer. Changing it never touches players
     * who already have a NAV attachment, and a multiplayer client's screen does not affect the server.
     */
    public double warpMaxPcPerS() {
        return warpMaxPcPerS;
    }

    /** Multiplier on Galactica HUD element size. */
    public double hudScale() {
        return hudScale;
    }

    /** Draw the colour/marker legend on the galaxy map and HUD. */
    public boolean showLegend() {
        return showLegend;
    }

    // ---------------------------------------------------------------- JSON

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Pretty JSON, keys in a stable order, always stamped with {@link #SCHEMA_VERSION}. */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", SCHEMA_VERSION);
        o.addProperty("skyQuality", skyBakeWidth);
        o.addProperty("planetTextureMax", planetTextureMax);
        o.addProperty("starPlasma", starPlasma);
        o.addProperty("starPlasmaQuality", starPlasmaQuality.name().toLowerCase(Locale.ROOT));
        o.addProperty("exhaustQuality", exhaustQuality.name().toLowerCase(Locale.ROOT));
        o.addProperty("entryEffects", entryEffects);
        o.addProperty("realNightSky", realNightSky);
        o.addProperty("seamlessTransitions", seamlessTransitions);
        o.addProperty("warpMaxPcPerS", warpMaxPcPerS);
        o.addProperty("hudScale", hudScale);
        o.addProperty("showLegend", showLegend);
        return GSON.toJson(o) + "\n";
    }

    /** Result of {@link #parse}: the repaired config and a human-readable line per repair. */
    public record ParseResult(GalacticaConfig config, List<String> repairs, int fileVersion) {
        /** True when the file should be rewritten (repairs were made or the schema is older). */
        public boolean needsRewrite() {
            return !repairs.isEmpty() || fileVersion != SCHEMA_VERSION;
        }
    }

    /**
     * Parses a config file's text. Never throws: unreadable JSON yields defaults with one repair
     * line, so a hand-edit typo cannot stop the game from starting.
     */
    public static ParseResult parse(String json) {
        List<String> repairs = new ArrayList<>();
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(json == null ? "" : json);
            if (!e.isJsonObject()) {
                repairs.add("root is not a JSON object; using defaults");
                return new ParseResult(defaults(), repairs, 0);
            }
            o = e.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException ex) {
            repairs.add("unreadable JSON (" + ex.getMessage() + "); using defaults");
            return new ParseResult(defaults(), repairs, 0);
        }

        int version = readInt(o, "version", 0, repairs);
        if (version > SCHEMA_VERSION) {
            repairs.add("file version " + version + " is newer than " + SCHEMA_VERSION + "; unknown fields ignored");
        }
        migrate(o, version, repairs);

        Builder b = builder();
        if (o.has("skyQuality")) {
            int w = readSkyQuality(o.get("skyQuality"), repairs);
            if (w > 0) b.skyBakeWidth = w;
        }
        b.planetTextureMax = readInt(o, "planetTextureMax", b.planetTextureMax, repairs);
        b.starPlasma = readBool(o, "starPlasma", b.starPlasma, repairs);
        b.starPlasmaQuality = readEnum(o, "starPlasmaQuality", Quality.class, b.starPlasmaQuality, repairs);
        b.exhaustQuality = readEnum(o, "exhaustQuality", ExhaustQuality.class, b.exhaustQuality, repairs);
        b.entryEffects = readDouble(o, "entryEffects", b.entryEffects, repairs);
        b.realNightSky = readBool(o, "realNightSky", b.realNightSky, repairs);
        b.seamlessTransitions = readBool(o, "seamlessTransitions", b.seamlessTransitions, repairs);
        b.warpMaxPcPerS = readDouble(o, "warpMaxPcPerS", b.warpMaxPcPerS, repairs);
        b.hudScale = readDouble(o, "hudScale", b.hudScale, repairs);
        b.showLegend = readBool(o, "showLegend", b.showLegend, repairs);

        GalacticaConfig c = b.build();
        // Report clamping/snapping so a user who typed 3000 sees why it became 4096.
        if (b.skyBakeWidth != c.skyBakeWidth) repairs.add("skyQuality " + b.skyBakeWidth + " snapped to " + c.skyBakeWidth);
        if (b.planetTextureMax != c.planetTextureMax) repairs.add("planetTextureMax " + b.planetTextureMax + " snapped to " + c.planetTextureMax);
        if (Double.compare(b.entryEffects, c.entryEffects) != 0) repairs.add("entryEffects " + b.entryEffects + " clamped to " + c.entryEffects);
        if (Double.compare(b.warpMaxPcPerS, c.warpMaxPcPerS) != 0) repairs.add("warpMaxPcPerS " + b.warpMaxPcPerS + " clamped to " + c.warpMaxPcPerS);
        if (Double.compare(b.hudScale, c.hudScale) != 0) repairs.add("hudScale " + b.hudScale + " clamped to " + c.hudScale);
        return new ParseResult(c, repairs, version);
    }

    /**
     * Schema migrations, applied in place before reading. Version 0 means "no version key": the
     * pre-release M7 drafts wrote {@code skyQuality} as "low/medium/high", which
     * {@link #readSkyQuality} already accepts, so only the stamp changes.
     */
    private static void migrate(JsonObject o, int version, List<String> repairs) {
        if (version < 1 && o.size() > 0) {
            repairs.add("migrated unversioned config to schema 1");
        }
    }

    /** Accepts a bake width (1024/2048/4096, or anything snapped to the nearest) or low/medium/high. */
    private static int readSkyQuality(JsonElement e, List<String> repairs) {
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) return (int) Math.round(p.getAsDouble());
            if (p.isString()) {
                switch (p.getAsString().trim().toLowerCase(Locale.ROOT)) {
                    case "low" -> { return 1024; }
                    case "medium" -> { return 2048; }
                    case "high" -> { return 4096; }
                    default -> {
                        try {
                            return Integer.parseInt(p.getAsString().trim());
                        } catch (NumberFormatException ignored) {
                            // fall through to the repair line below
                        }
                    }
                }
            }
        }
        repairs.add("skyQuality " + e + " is not 1024/2048/4096 or low/medium/high; default kept");
        return -1;
    }

    private static int readInt(JsonObject o, String key, int def, List<String> repairs) {
        if (!o.has(key)) return def;
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            double d = e.getAsDouble();
            if (Double.isFinite(d)) return (int) Math.round(d);
        }
        repairs.add(key + " " + e + " is not a number; default " + def + " kept");
        return def;
    }

    private static double readDouble(JsonObject o, String key, double def, List<String> repairs) {
        if (!o.has(key)) return def;
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            double d = e.getAsDouble();
            if (Double.isFinite(d)) return d;
        }
        repairs.add(key + " " + e + " is not a finite number; default " + def + " kept");
        return def;
    }

    private static boolean readBool(JsonObject o, String key, boolean def, List<String> repairs) {
        if (!o.has(key)) return def;
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
        repairs.add(key + " " + e + " is not true/false; default " + def + " kept");
        return def;
    }

    private static <E extends Enum<E>> E readEnum(JsonObject o, String key, Class<E> type, E def, List<String> repairs) {
        if (!o.has(key)) return def;
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            String s = e.getAsString().trim().toUpperCase(Locale.ROOT);
            for (E v : type.getEnumConstants()) if (v.name().equals(s)) return v;
        }
        repairs.add(key + " " + e + " is not one of " + java.util.Arrays.toString(type.getEnumConstants()).toLowerCase(Locale.ROOT)
                + "; default " + def.name().toLowerCase(Locale.ROOT) + " kept");
        return def;
    }

    // ---------------------------------------------------------------- validation helpers

    /** Nearest legal sky width; ties go to the smaller (cheaper) one. */
    static int snapSkyWidth(int w) {
        int best = SKY_BAKE_WIDTHS[0];
        for (int c : SKY_BAKE_WIDTHS) if (Math.abs(c - w) < Math.abs(best - w)) best = c;
        return best;
    }

    /** Nearest power of two in log space (so 700 → 512, 800 → 1024), clamped to [min, max]. */
    static int snapPow2(int v, int min, int max) {
        if (v <= min) return min;
        if (v >= max) return max;
        int lo = Integer.highestOneBit(v), hi = lo << 1;
        int snapped = (double) v / lo < (double) hi / v ? lo : hi;
        return Math.max(min, Math.min(max, snapped));
    }

    static double clamp(double v, double min, double max, double fallback) {
        if (!Double.isFinite(v)) return fallback;
        return Math.max(min, Math.min(max, v));
    }

    // ---------------------------------------------------------------- equality

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof GalacticaConfig c)) return false;
        return skyBakeWidth == c.skyBakeWidth && planetTextureMax == c.planetTextureMax && starPlasma == c.starPlasma
                && starPlasmaQuality == c.starPlasmaQuality && exhaustQuality == c.exhaustQuality
                && Double.compare(entryEffects, c.entryEffects) == 0 && realNightSky == c.realNightSky
                && seamlessTransitions == c.seamlessTransitions && Double.compare(warpMaxPcPerS, c.warpMaxPcPerS) == 0
                && Double.compare(hudScale, c.hudScale) == 0 && showLegend == c.showLegend;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(skyBakeWidth, planetTextureMax, starPlasma, starPlasmaQuality, exhaustQuality,
                entryEffects, realNightSky, seamlessTransitions, warpMaxPcPerS, hudScale, showLegend);
    }

    @Override
    public String toString() {
        return toJson().replace('\n', ' ');
    }

    // ---------------------------------------------------------------- builder

    /** Mutable staging copy used by the config screens; {@link #build} validates. */
    public static final class Builder {
        /** Default 2048: the bake width the sky shipped with in M1–M6 ({@code SkyManager.SKY_WIDTH}). */
        public int skyBakeWidth = 2048;
        public int planetTextureMax = 512;
        public boolean starPlasma = true;
        public Quality starPlasmaQuality = Quality.MEDIUM;
        public ExhaustQuality exhaustQuality = ExhaustQuality.MEDIUM;
        public double entryEffects = 1.0;
        public boolean realNightSky = true;
        public boolean seamlessTransitions = true;
        /** Default 10 pc/s, the full-warp speed documented in {@code nav.Frames}. */
        public double warpMaxPcPerS = 10.0;
        public double hudScale = 1.0;
        public boolean showLegend = true;

        private Builder() {
        }

        public GalacticaConfig build() {
            return new GalacticaConfig(this);
        }
    }
}
