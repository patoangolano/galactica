package dev.galactica.star;

public enum StarKind {
    MAIN_SEQUENCE("V"),
    GIANT("III"),
    SUPERGIANT("I"),
    WHITE_DWARF("D"),
    NEUTRON_STAR("NS"),
    BLACK_HOLE("BH");

    public final String luminosityClass;

    StarKind(String luminosityClass) {
        this.luminosityClass = luminosityClass;
    }

    public boolean isRemnant() {
        return this == WHITE_DWARF || this == NEUTRON_STAR || this == BLACK_HOLE;
    }
}
