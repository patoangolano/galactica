package dev.galactica.client.discovery;

import dev.galactica.discovery.Discovery;
import dev.galactica.discovery.DiscoveryLedger;
import dev.galactica.discovery.DiscoveryPayloads;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The client's copy of the server's discovery codex, kept current by
 * {@link DiscoveryPayloads.Codex} packets. Also feeds {@link Discovery#clientNames()} so the HUD
 * and map can show custom names through {@code Discovery.displayName}. Client thread only.
 */
public final class ClientCodex {
    private ClientCodex() {}

    private static final Map<String, DiscoveryLedger.Entry> ENTRIES = new LinkedHashMap<>();
    private static int balance, lifetime, revision;

    static void apply(DiscoveryPayloads.Codex c) {
        if (c.replace()) {
            ENTRIES.clear();
            Discovery.clientNames().clear();
        }
        for (DiscoveryLedger.Entry e : c.entries()) {
            ENTRIES.put(e.key(), e);
            if (e.customName().isEmpty()) Discovery.clientNames().remove(e.key());
            else Discovery.clientNames().put(e.key(), e.customName());
        }
        balance = c.balance();
        lifetime = c.lifetime();
        revision++;
    }

    static void clear() {
        ENTRIES.clear();
        Discovery.clientNames().clear();
        balance = lifetime = 0;
        revision++;
    }

    public static List<DiscoveryLedger.Entry> entries() {
        return new ArrayList<>(ENTRIES.values());
    }

    public static DiscoveryLedger.Entry get(String key) {
        return ENTRIES.get(key);
    }

    public static int balance() {
        return balance;
    }

    public static int lifetime() {
        return lifetime;
    }

    /** Bumped on every change, so screens can tell when to re-sort. */
    public static int revision() {
        return revision;
    }
}
