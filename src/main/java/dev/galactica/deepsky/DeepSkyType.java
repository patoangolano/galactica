package dev.galactica.deepsky;

/** Kinds of deep-sky object, each with a translation key for the HUD. */
public enum DeepSkyType {
    EMISSION_NEBULA("emission_nebula"),
    REFLECTION_NEBULA("reflection_nebula"),
    DARK_NEBULA("dark_nebula"),
    SUPERNOVA_REMNANT("supernova_remnant"),
    PLANETARY_NEBULA("planetary_nebula"),
    OPEN_CLUSTER("open_cluster"),
    GLOBULAR_CLUSTER("globular_cluster"),
    GALAXY("galaxy");

    public final String key;

    DeepSkyType(String key) {
        this.key = key;
    }

    public String translationKey() {
        return "deepsky.galactica.type." + key;
    }

    public boolean isCluster() {
        return this == OPEN_CLUSTER || this == GLOBULAR_CLUSTER;
    }
}
