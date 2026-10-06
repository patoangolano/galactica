package dev.galactica.planet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;

/** The slot table's rules, free of Minecraft state so they can be tested directly. */
public final class SlotPolicy {
    private SlotPolicy() {}

    /** One surface slot: the body it holds (empty = free) and the game time it was last occupied. */
    public record Entry(Optional<PlanetAddress> address, long lastUsed) {
        public static final Entry FREE = new Entry(Optional.empty(), 0L);
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                PlanetAddress.CODEC.optionalFieldOf("body").forGetter(Entry::address),
                Codec.LONG.optionalFieldOf("last_used", 0L).forGetter(Entry::lastUsed)
        ).apply(i, Entry::new));

        public boolean free() {
            return address.isEmpty();
        }
    }

    /** The slot already holding {@code a}, else the lowest free slot, else −1. */
    public static int choose(List<Entry> entries, PlanetAddress a) {
        int free = -1;
        for (int k = 0; k < entries.size(); k++) {
            Entry e = entries.get(k);
            if (e.address().isPresent() && e.address().get().equals(a)) return k;
            if (free < 0 && e.free()) free = k;
        }
        return free;
    }

    /**
     * Occupied slots to wipe so that at least {@code keepFree} are free: least recently used first
     * (ties to the lower index), never a slot for which {@code pinned} holds.
     */
    public static List<Integer> evictionPlan(List<Entry> entries, int keepFree, IntPredicate pinned) {
        int free = (int) entries.stream().filter(Entry::free).count();
        List<Integer> out = new ArrayList<>();
        if (free >= keepFree) return out;
        List<Integer> occupied = new ArrayList<>();
        for (int k = 0; k < entries.size(); k++) if (!entries.get(k).free() && !pinned.test(k)) occupied.add(k);
        occupied.sort(Comparator.comparingLong((Integer k) -> entries.get(k).lastUsed()).thenComparingInt(k -> k));
        for (int i = 0; i < occupied.size() && free + out.size() < keepFree; i++) out.add(occupied.get(i));
        return out;
    }
}
