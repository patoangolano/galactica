package dev.galactica.discovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The server's record of discoveries and survey accounts, as plain Java so the rules are testable:
 * who found what first, when, by which method, how much it paid, its custom name, and each
 * player's point balance. {@link DiscoveryLog} persists it as SavedData.
 */
public final class DiscoveryLedger {

    public static final int KIND_STAR = 0, KIND_PLANET = 1, KIND_MOON = 2;
    public static final String METHOD_SPECTRUM = "spectrum", METHOD_TRANSIT = "transit", METHOD_RV = "rv",
            METHOD_TRANSIT_RV = "transit+rv";

    /**
     * One discovery.
     *
     * @param key            {@link DiscoveryKeys} key
     * @param kind           {@link #KIND_STAR}, {@link #KIND_PLANET} or {@link #KIND_MOON}
     * @param label          catalogue/procedural designation
     * @param host           host star designation for bodies, "" for stars
     * @param detail         one-line summary (spectral type, body type…)
     * @param discoverer     first discoverer
     * @param discovererName their name when they found it
     * @param gameTime       world game time of the discovery, ticks
     * @param customName     name chosen by the discoverer, "" for none
     * @param points         points paid out for this object so far (all finders)
     * @param method         how it was first found
     * @param characterized  a direct spectrum has been taken
     */
    public record Entry(String key, int kind, String label, String host, String detail, UUID discoverer,
                        String discovererName, long gameTime, String customName, int points, String method,
                        boolean characterized) {

        public String displayName() {
            return customName.isEmpty() ? label : customName;
        }

        Entry withCustomName(String n) {
            return new Entry(key, kind, label, host, detail, discoverer, discovererName, gameTime, n, points, method, characterized);
        }

        Entry characterize(int newPoints) {
            String m = method.equals(METHOD_SPECTRUM) ? method : method + "+" + METHOD_SPECTRUM;
            return new Entry(key, kind, label, host, detail, discoverer, discovererName, gameTime, customName, newPoints, m, true);
        }
    }

    public record Account(int balance, int lifetime) {
        public static final Account EMPTY = new Account(0, 0);
    }

    /** Result of recording a scan or survey: the stored entry, whether it was new, points paid now. */
    public record Outcome(Entry entry, boolean first, int awarded) {}

    public enum RenameResult { OK, NOT_FOUND, NOT_DISCOVERER, BAD_NAME, TAKEN }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<UUID, Account> accounts = new HashMap<>();

    public DiscoveryLedger() {}

    public DiscoveryLedger(Collection<Entry> entries, Map<UUID, Account> accounts) {
        for (Entry e : entries) this.entries.put(e.key(), e);
        this.accounts.putAll(accounts);
    }

    public Entry get(String key) {
        return entries.get(key);
    }

    public Collection<Entry> entries() {
        return entries.values();
    }

    public Map<UUID, Account> accounts() {
        return accounts;
    }

    public Account account(UUID player) {
        return accounts.getOrDefault(player, Account.EMPTY);
    }

    /**
     * A direct spectrum of the object. New: recorded, characterised, full value to the scanner.
     * Previously only surveyed: becomes characterised and the scanner earns the rest of the value;
     * the first discoverer is unchanged. Already characterised: nothing.
     */
    public Outcome recordScan(String key, int kind, String label, String host, String detail, UUID who, String whoName,
                              long time, int fullPoints) {
        Entry e = entries.get(key);
        if (e == null) {
            e = new Entry(key, kind, label, host, detail, who, whoName, time, "", fullPoints, METHOD_SPECTRUM, true);
            entries.put(key, e);
            award(who, fullPoints);
            return new Outcome(e, true, fullPoints);
        }
        if (!e.characterized()) {
            int rest = Math.max(0, fullPoints - e.points());
            e = e.characterize(Math.max(fullPoints, e.points()));
            entries.put(key, e);
            award(who, rest);
            return new Outcome(e, false, rest);
        }
        return new Outcome(e, false, 0);
    }

    /** An indirect (transit/RV) detection: recorded uncharacterised with a survey share if new. */
    public Outcome recordSurvey(String key, int kind, String label, String host, String detail, UUID who, String whoName,
                                long time, int fullPoints, String method) {
        Entry e = entries.get(key);
        if (e != null) return new Outcome(e, false, 0);
        int pts = SurveyValue.surveyShare(fullPoints);
        e = new Entry(key, kind, label, host, detail, who, whoName, time, "", pts, method, false);
        entries.put(key, e);
        award(who, pts);
        return new Outcome(e, true, pts);
    }

    public RenameResult rename(String key, UUID who, String rawName) {
        Entry e = entries.get(key);
        if (e == null) return RenameResult.NOT_FOUND;
        if (!e.discoverer().equals(who)) return RenameResult.NOT_DISCOVERER;
        String name = DiscoveryKeys.cleanName(rawName);
        if (name == null) return RenameResult.BAD_NAME;
        String f = DiscoveryKeys.fold(name);
        for (Entry o : entries.values()) {
            if (o != e && (DiscoveryKeys.fold(o.customName()).equals(f) || DiscoveryKeys.fold(o.label()).equals(f))) {
                return RenameResult.TAKEN;
            }
        }
        entries.put(key, e.withCustomName(name));
        return RenameResult.OK;
    }

    /** Spends points if the balance covers them. */
    public boolean spend(UUID who, int points) {
        Account a = account(who);
        if (points < 0 || a.balance() < points) return false;
        accounts.put(who, new Account(a.balance() - points, a.lifetime()));
        return true;
    }

    private void award(UUID who, int points) {
        if (points <= 0) return;
        Account a = account(who);
        accounts.put(who, new Account(a.balance() + points, a.lifetime() + points));
    }

    /** Custom names by key, for every renamed object. */
    public Map<String, String> customNames() {
        Map<String, String> m = new HashMap<>();
        for (Entry e : entries.values()) if (!e.customName().isEmpty()) m.put(e.key(), e.customName());
        return m;
    }

    public List<Entry> snapshot() {
        return new ArrayList<>(entries.values());
    }
}
