package dev.galactica.galaxy;

import dev.galactica.science.SeedRng;

/**
 * Names for procedural stars. Every star has a survey designation derived from its sector;
 * roughly one in eight also gets a pronounceable proper name, like the charted systems of
 * No Man's Sky.
 */
public final class StarNames {
    private StarNames() {}

    private static final String[] ONSETS = {
            "", "b", "br", "c", "ch", "d", "dr", "f", "g", "gl", "h", "k", "kr", "l", "m", "n", "p", "pr",
            "qu", "r", "s", "sh", "st", "t", "th", "tr", "v", "vr", "x", "z", "zh"};
    private static final String[] VOWELS = {"a", "e", "i", "o", "u", "ae", "ai", "au", "ei", "io", "y", "ou"};
    private static final String[] CODAS = {"", "", "", "n", "r", "s", "th", "x", "l", "m", "nd", "rk", "ss"};
    private static final String[] SUFFIXES = {"", "", "", " Prime", " Major", " Minor", "-B", " Secundus"};

    public static String procedural(long starSeed, SectorKey key, int index) {
        SeedRng rng = new SeedRng(starSeed ^ 0x4E414D45L);
        String designation = "GX " + key.code() + "-" + index;
        if (rng.nextInt(8) != 0) return designation;
        int syllables = 2 + rng.nextInt(2);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < syllables; i++) {
            sb.append(ONSETS[rng.nextInt(ONSETS.length)]);
            sb.append(VOWELS[rng.nextInt(VOWELS.length)]);
            if (i == syllables - 1 || rng.nextInt(3) == 0) sb.append(CODAS[rng.nextInt(CODAS.length)]);
        }
        String name = sb.substring(0, 1).toUpperCase() + sb.substring(1);
        return name + SUFFIXES[rng.nextInt(SUFFIXES.length)];
    }

    /** Planet name: star name plus Bayer-style letter (b, c, d ...) as in exoplanet catalogs. */
    public static String planet(String starName, int orbitIndex) {
        return starName + " " + (char) ('b' + orbitIndex);
    }
}
