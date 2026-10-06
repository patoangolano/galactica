package dev.galactica.survival;

/**
 * The rules of life support (spec §3), free of Minecraft so they can be unit tested.
 *
 * <p>Oxygen is counted in <em>seconds of breathing</em>. A resting adult consumes ~0.84 kg O₂ per day, so the
 * 2 700 s tank stands for ~26 g of O₂ — a small EVA bottle. The suit keeps its own {@link #SUIT_CAPACITY}
 * buffer (the EMU's secondary O₂ pack is rated for 30 min; 10 min keeps tanks relevant) and tops it up from
 * tanks in the inventory, first tank first, so a player never has to swap bottles by hand.
 */
public final class LifeSupportModel {
    private LifeSupportModel() {}

    /** O₂ seconds held by a full tank (spec §1: 45 min). */
    public static final int TANK_CAPACITY = 2700;
    /** O₂ seconds buffered inside the suit itself. */
    public static final int SUIT_CAPACITY = 600;

    /** Below this an unsuited body loses heat faster than it makes it (spec §3). */
    public static final double COLD_LIMIT_K = 200.0;
    /** Above this sweating cannot keep core temperature down (spec §3; wet-bulb limits are ~308 K). */
    public static final double HOT_LIMIT_K = 330.0;
    /** Above this unprotected lungs and sinuses barotrauma (spec §3; Venus is 92 bar). */
    public static final double CRUSH_LIMIT_BAR = 10.0;

    /**
     * Breathable air at ≥ 0.5 bar refills the suit (open visor) and lets the suit compressor refill tanks.
     * Rates are game balance: a full suit in 10 s, a full tank in 3 min.
     */
    public static final double RECHARGE_MIN_BAR = 0.5;
    public static final int SUIT_RECHARGE_PER_SECOND = 60;
    public static final int TANK_RECHARGE_PER_SECOND = 15;

    /** Hazards for one second of exposure. {@code suitBreathing} means the suit, not the air, feeds the lungs. */
    public record Assessment(boolean suitBreathing, boolean suffocating, boolean cold, boolean hot, boolean crushed) {
        public boolean safe() {
            return !suffocating && !cold && !hot && !crushed;
        }
    }

    public static Assessment assess(Environment env, boolean fullSuit, int suitO2) {
        boolean needsSuitAir = !env.breathable();
        boolean suitBreathing = needsSuitAir && fullSuit && suitO2 > 0;
        boolean exposed = !fullSuit;
        return new Assessment(
                suitBreathing,
                needsSuitAir && !suitBreathing,
                exposed && env.tempK() < COLD_LIMIT_K,
                exposed && env.tempK() > HOT_LIMIT_K,
                exposed && env.pressureBar() > CRUSH_LIMIT_BAR);
    }

    public static boolean canRecharge(Environment env) {
        return env.breathable() && env.pressureBar() >= RECHARGE_MIN_BAR;
    }

    /**
     * Tops the suit up to {@link #SUIT_CAPACITY} from {@code tanks} in order. {@code tanks} is updated in place.
     *
     * @return the new suit O₂
     */
    public static int refillSuit(int suitO2, int[] tanks) {
        int need = SUIT_CAPACITY - Math.max(0, suitO2);
        int suit = Math.max(0, suitO2);
        for (int i = 0; i < tanks.length && need > 0; i++) {
            int take = Math.min(need, Math.max(0, tanks[i]));
            tanks[i] -= take;
            suit += take;
            need -= take;
        }
        return suit;
    }

    /**
     * One second of breathing from the suit followed by the top-up from tanks, so a suit with tanks behind it
     * reads full until the tanks run dry.
     */
    public static int breatheOneSecond(int suitO2, int[] tanks) {
        return refillSuit(Math.max(0, suitO2 - 1), tanks);
    }

    /** One second of recharge from breathable air. Tanks are filled in order. */
    public static int rechargeOneSecond(int suitO2, int[] tanks) {
        int suit = Math.min(SUIT_CAPACITY, Math.max(0, suitO2) + SUIT_RECHARGE_PER_SECOND);
        for (int i = 0; i < tanks.length; i++) {
            if (tanks[i] < TANK_CAPACITY) {
                tanks[i] = Math.min(TANK_CAPACITY, Math.max(0, tanks[i]) + TANK_RECHARGE_PER_SECOND);
                break;
            }
        }
        return suit;
    }

    /** Total O₂ seconds left, suit plus tanks. */
    public static int reserve(int suitO2, int[] tanks) {
        long sum = Math.max(0, suitO2);
        for (int t : tanks) sum += Math.max(0, t);
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }
}
