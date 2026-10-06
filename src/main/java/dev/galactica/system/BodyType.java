package dev.galactica.system;

public enum BodyType {
    LAVA(true, "Lava world"),
    BARREN(true, "Barren rock"),
    DESERT(true, "Desert world"),
    GREENHOUSE(true, "Greenhouse world"),
    TERRAN(true, "Temperate terrestrial"),
    OCEAN(true, "Ocean world"),
    ICE(true, "Ice world"),
    SUB_NEPTUNE(false, "Sub-Neptune"),
    ICE_GIANT(false, "Ice giant"),
    GAS_GIANT(false, "Gas giant"),
    HOT_JUPITER(false, "Hot Jupiter");

    /** Whether a player can land: giants have no surface, only a cloud deck to dive into. */
    public final boolean solidSurface;
    public final String label;

    BodyType(boolean solidSurface, String label) {
        this.solidSurface = solidSurface;
        this.label = label;
    }
}
