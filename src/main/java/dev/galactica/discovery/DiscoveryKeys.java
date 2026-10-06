package dev.galactica.discovery;

import dev.galactica.star.Star;
import dev.galactica.system.Body;

import java.util.Locale;

/**
 * Stable string keys for discoverable objects. Star ids and body ids are 64-bit seed hashes (or
 * small negatives for the real catalog), unique across the galaxy, so the key needs no address.
 * Also validates player-chosen names.
 */
public final class DiscoveryKeys {
    private DiscoveryKeys() {}

    public static final int MAX_NAME_LENGTH = 32;

    public static String star(Star s) {
        return star(s.id());
    }

    public static String star(long id) {
        return "s" + id;
    }

    public static String body(Body b) {
        return body(b.id());
    }

    public static String body(long id) {
        return "b" + id;
    }

    public static boolean isStar(String key) {
        return key.startsWith("s");
    }

    /**
     * A trimmed, single-spaced name, or null if unacceptable: 1–{@value #MAX_NAME_LENGTH}
     * characters, letters (any script), digits, spaces and {@code - ' . ( ) * +}; no formatting
     * codes ({@code §}) or control characters.
     */
    public static String cleanName(String raw) {
        if (raw == null) return null;
        String s = raw.strip().replaceAll("\\s+", " ");
        if (s.isEmpty() || s.length() > MAX_NAME_LENGTH) return null;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            boolean ok = Character.isLetterOrDigit(cp) || cp == ' ' || "-'.()*+".indexOf(cp) >= 0;
            if (!ok) return null;
            i += Character.charCount(cp);
        }
        return s;
    }

    /** Case-insensitive comparison key for uniqueness checks. */
    public static String fold(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
