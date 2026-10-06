package dev.galactica.discovery;

import dev.galactica.Galactica;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** The three discovery packets. Registered by {@link Discovery#init()}. */
public final class DiscoveryPayloads {
    private DiscoveryPayloads() {}

    /** C2S: "I pointed the spectrometer at this". */
    public record Scan(ScanTarget target) implements CustomPacketPayload {
        public static final Type<Scan> TYPE = new Type<>(Galactica.id("scan"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Scan> CODEC = StreamCodec.ofMember(
                (p, buf) -> p.target.write(buf), buf -> new Scan(ScanTarget.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** One planet a transit/RV survey revealed. */
    public record Revealed(int planetIndex, boolean transit, boolean rv, double kMs, double periodDays, double depthPpm, boolean first) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(planetIndex);
            b.writeBoolean(transit);
            b.writeBoolean(rv);
            b.writeDouble(kMs);
            b.writeDouble(periodDays);
            b.writeDouble(depthPpm);
            b.writeBoolean(first);
        }

        static Revealed read(FriendlyByteBuf b) {
            return new Revealed(b.readVarInt(), b.readBoolean(), b.readBoolean(), b.readDouble(), b.readDouble(), b.readDouble(), b.readBoolean());
        }
    }

    /**
     * S2C: the server's verdict on a scan. The client rebuilds the target and its spectrum from
     * {@link #target} with the server's radial velocity, so only numbers travel.
     *
     * @param refusal        translation key when the scan was refused, "" when accepted
     * @param rvKms          radial velocity of the target relative to the observer
     * @param distancePc     observer–target distance
     * @param insideSystem   observer is inside the target star's system (no indirect survey)
     * @param discoverer     first discoverer's name
     * @param discoveryTime  game time of the first discovery
     * @param first          this scan was the first discovery
     * @param awarded        points paid for this scan (discovery, characterisation and survey)
     * @param balance        scanner's point balance afterwards
     * @param customName     the object's custom name, "" for none
     */
    public record ScanResult(ScanTarget target, String refusal, double rvKms, double distancePc, boolean insideSystem,
                             String discoverer, long discoveryTime, boolean first, int awarded, int balance,
                             String customName, List<Revealed> revealed) implements CustomPacketPayload {
        public static final Type<ScanResult> TYPE = new Type<>(Galactica.id("scan_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ScanResult> CODEC = StreamCodec.ofMember(ScanResult::write, ScanResult::read);

        public static ScanResult refused(ScanTarget target, String key) {
            return new ScanResult(target, key, 0, 0, false, "", 0, false, 0, 0, "", List.of());
        }

        public boolean accepted() {
            return refusal.isEmpty();
        }

        private void write(RegistryFriendlyByteBuf b) {
            target.write(b);
            b.writeUtf(refusal);
            b.writeDouble(rvKms);
            b.writeDouble(distancePc);
            b.writeBoolean(insideSystem);
            b.writeUtf(discoverer);
            b.writeLong(discoveryTime);
            b.writeBoolean(first);
            b.writeVarInt(awarded);
            b.writeVarInt(balance);
            b.writeUtf(customName);
            b.writeVarInt(revealed.size());
            for (Revealed r : revealed) r.write(b);
        }

        private static ScanResult read(RegistryFriendlyByteBuf b) {
            ScanTarget t = ScanTarget.read(b);
            String refusal = b.readUtf();
            double rv = b.readDouble(), d = b.readDouble();
            boolean inside = b.readBoolean();
            String who = b.readUtf();
            long time = b.readLong();
            boolean first = b.readBoolean();
            int awarded = b.readVarInt(), balance = b.readVarInt();
            String name = b.readUtf();
            int n = Math.min(64, b.readVarInt());
            List<Revealed> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(Revealed.read(b));
            return new ScanResult(t, refusal, rv, d, inside, who, time, first, awarded, balance, name, List.copyOf(list));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * S2C: codex contents. {@code replace} swaps the whole list (on join); otherwise the entries are
     * upserts. Carries the receiver's account so the codex can show it.
     */
    public record Codex(boolean replace, List<DiscoveryLedger.Entry> entries, int balance, int lifetime) implements CustomPacketPayload {
        public static final Type<Codex> TYPE = new Type<>(Galactica.id("codex"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Codex> CODEC = StreamCodec.ofMember(Codex::write, Codex::read);

        private void write(RegistryFriendlyByteBuf b) {
            b.writeBoolean(replace);
            b.writeVarInt(entries.size());
            for (DiscoveryLedger.Entry e : entries) {
                b.writeUtf(e.key());
                b.writeVarInt(e.kind());
                b.writeUtf(e.label());
                b.writeUtf(e.host());
                b.writeUtf(e.detail());
                b.writeUUID(e.discoverer());
                b.writeUtf(e.discovererName());
                b.writeLong(e.gameTime());
                b.writeUtf(e.customName());
                b.writeVarInt(e.points());
                b.writeUtf(e.method());
                b.writeBoolean(e.characterized());
            }
            b.writeVarInt(balance);
            b.writeVarInt(lifetime);
        }

        private static Codex read(RegistryFriendlyByteBuf b) {
            boolean replace = b.readBoolean();
            int n = b.readVarInt();
            List<DiscoveryLedger.Entry> list = new ArrayList<>(Math.min(n, 4096));
            for (int i = 0; i < n; i++) {
                list.add(new DiscoveryLedger.Entry(b.readUtf(), b.readVarInt(), b.readUtf(), b.readUtf(), b.readUtf(),
                        b.readUUID(), b.readUtf(), b.readLong(), b.readUtf(), b.readVarInt(), b.readUtf(), b.readBoolean()));
            }
            return new Codex(replace, list, b.readVarInt(), b.readVarInt());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
