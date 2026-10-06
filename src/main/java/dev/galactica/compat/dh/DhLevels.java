package dev.galactica.compat.dh;

import dev.galactica.planet.PlanetSlots;

/** Pure helpers for matching DH level wrappers to Galactica dimensions (no DH classes referenced). */
public final class DhLevels {
    private DhLevels() {
    }

    /**
     * Slot of a DH dimension name ({@code ServerLevelWrapper.getDimensionName()} returns
     * {@code level.dimension().identifier().toString()}, e.g. {@code "galactica:surface_3"}), or −1.
     */
    public static int surfaceSlot(String dimensionName) {
        if (dimensionName == null) return -1;
        String prefix = "galactica:surface_";
        if (!dimensionName.startsWith(prefix)) return -1;
        String n = dimensionName.substring(prefix.length());
        if (n.isEmpty() || n.length() > 3) return -1;
        for (int i = 0; i < n.length(); i++) if (!Character.isDigit(n.charAt(i))) return -1;
        int k = Integer.parseInt(n);
        return k < PlanetSlots.COUNT ? k : -1;
    }

    /** True for the space dimension, which DH should skip (it is empty void plus a sky shell). */
    public static boolean isSpace(String dimensionName) {
        return "galactica:space".equals(dimensionName);
    }
}
