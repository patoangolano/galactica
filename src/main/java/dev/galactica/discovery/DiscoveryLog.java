package dev.galactica.discovery;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.galactica.Galactica;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code data/galactica/discoveries.dat} in the server's (overworld) data storage: the
 * {@link DiscoveryLedger} of every discovery and every player's survey account.
 */
public final class DiscoveryLog extends SavedData {

    private record AccountRow(UUID player, int balance, int lifetime) {
        static final Codec<AccountRow> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(AccountRow::player),
                Codec.INT.fieldOf("balance").forGetter(AccountRow::balance),
                Codec.INT.fieldOf("lifetime").forGetter(AccountRow::lifetime)
        ).apply(i, AccountRow::new));
    }

    static final Codec<DiscoveryLedger.Entry> ENTRY_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("key").forGetter(DiscoveryLedger.Entry::key),
            Codec.INT.fieldOf("kind").forGetter(DiscoveryLedger.Entry::kind),
            Codec.STRING.fieldOf("label").forGetter(DiscoveryLedger.Entry::label),
            Codec.STRING.optionalFieldOf("host", "").forGetter(DiscoveryLedger.Entry::host),
            Codec.STRING.optionalFieldOf("detail", "").forGetter(DiscoveryLedger.Entry::detail),
            UUIDUtil.CODEC.fieldOf("by").forGetter(DiscoveryLedger.Entry::discoverer),
            Codec.STRING.optionalFieldOf("by_name", "?").forGetter(DiscoveryLedger.Entry::discovererName),
            Codec.LONG.optionalFieldOf("time", 0L).forGetter(DiscoveryLedger.Entry::gameTime),
            Codec.STRING.optionalFieldOf("name", "").forGetter(DiscoveryLedger.Entry::customName),
            Codec.INT.optionalFieldOf("points", 0).forGetter(DiscoveryLedger.Entry::points),
            Codec.STRING.optionalFieldOf("method", DiscoveryLedger.METHOD_SPECTRUM).forGetter(DiscoveryLedger.Entry::method),
            Codec.BOOL.optionalFieldOf("characterized", true).forGetter(DiscoveryLedger.Entry::characterized)
    ).apply(i, DiscoveryLedger.Entry::new));

    public static final Codec<DiscoveryLog> CODEC = RecordCodecBuilder.create(i -> i.group(
            ENTRY_CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(d -> d.ledger.snapshot()),
            AccountRow.CODEC.listOf().optionalFieldOf("accounts", List.of()).forGetter(DiscoveryLog::accountRows)
    ).apply(i, DiscoveryLog::new));

    // Same DataFixTypes as PlanetSlots: SavedDataStorage needs a non-null type to run update().
    public static final SavedDataType<DiscoveryLog> TYPE = new SavedDataType<>(Galactica.id("discoveries"),
            DiscoveryLog::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final DiscoveryLedger ledger;

    public DiscoveryLog() {
        ledger = new DiscoveryLedger();
    }

    private DiscoveryLog(List<DiscoveryLedger.Entry> entries, List<AccountRow> rows) {
        Map<UUID, DiscoveryLedger.Account> acc = new HashMap<>();
        for (AccountRow r : rows) acc.put(r.player(), new DiscoveryLedger.Account(r.balance(), r.lifetime()));
        ledger = new DiscoveryLedger(entries, acc);
    }

    public static DiscoveryLog get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    /** The ledger; call {@link #setDirty()} after changing it. */
    public DiscoveryLedger ledger() {
        return ledger;
    }

    private List<AccountRow> accountRows() {
        List<AccountRow> rows = new ArrayList<>();
        ledger.accounts().forEach((u, a) -> rows.add(new AccountRow(u, a.balance(), a.lifetime())));
        return rows;
    }
}
