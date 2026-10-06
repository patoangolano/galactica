package dev.galactica.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Owner of the live {@link GalacticaConfig} and of {@code config/galactica.json}.
 *
 * <p><b>Reading (any thread, any side):</b> {@code GalacticaConfigs.get().skyBakeWidth()}. The first
 * call loads the file lazily, so no area depends on entrypoint order; {@link #init()} only makes
 * the load (and its log lines) happen at startup instead of on first use.
 *
 * <p><b>Reacting to edits:</b> {@code GalacticaConfigs.onChange(cfg -> ...)} runs on the thread that
 * applied the change (the client thread for the config screens) after the new value is visible.
 * Renderers that bake from a setting compare the field and schedule their own re-bake; they must
 * not upload GPU resources from inside the listener if a render pass may be open.
 *
 * <p><b>Writing:</b> only the config screens call {@link #apply}. The file is written to a sibling
 * temp file and atomically moved, so a crash mid-save never leaves half a JSON file.
 */
public final class GalacticaConfigs {
    private static final Logger LOG = LoggerFactory.getLogger("Galactica/Config");
    public static final String FILE_NAME = "galactica.json";

    private static final Object LOCK = new Object();
    private static volatile GalacticaConfig current;
    private static volatile Path file;
    private static final List<Consumer<GalacticaConfig>> LISTENERS = new CopyOnWriteArrayList<>();

    private GalacticaConfigs() {
    }

    /** Loads (or creates) the config now. Safe to call more than once. */
    public static void init() {
        get();
    }

    /** The live config; never null. Loads from disk on first call. */
    public static GalacticaConfig get() {
        GalacticaConfig c = current;
        if (c != null) return c;
        synchronized (LOCK) {
            if (current == null) current = loadOrCreate(defaultFile());
            return current;
        }
    }

    /** Registers a listener for later changes (not called for the current value). */
    public static void onChange(Consumer<GalacticaConfig> listener) {
        LISTENERS.add(Objects.requireNonNull(listener));
    }

    /** Makes {@code next} live, saves it, and notifies listeners if anything changed. */
    public static void apply(GalacticaConfig next) {
        Objects.requireNonNull(next);
        GalacticaConfig prev;
        synchronized (LOCK) {
            prev = get();
            current = next;
            Path f = file != null ? file : defaultFile();
            if (f != null) {
                try {
                    write(f, next);
                } catch (IOException e) {
                    LOG.error("Could not save {}: {}", f, e.toString());
                }
            }
        }
        if (!prev.equals(next)) {
            for (Consumer<GalacticaConfig> l : LISTENERS) {
                try {
                    l.accept(next);
                } catch (RuntimeException e) {
                    LOG.error("Config listener {} failed", l, e);
                }
            }
        }
    }

    /**
     * Reads {@code f}, repairing and rewriting it when needed; a missing file is created with
     * defaults. I/O failures fall back to defaults in memory and are logged, never thrown.
     */
    public static GalacticaConfig loadOrCreate(Path f) {
        file = f;
        if (f == null) return GalacticaConfig.defaults();
        try {
            if (!Files.exists(f)) {
                GalacticaConfig d = GalacticaConfig.defaults();
                write(f, d);
                LOG.info("Created {} with defaults", f);
                return d;
            }
            GalacticaConfig.ParseResult r = GalacticaConfig.parse(Files.readString(f, StandardCharsets.UTF_8));
            for (String repair : r.repairs()) LOG.warn("{}: {}", f.getFileName(), repair);
            if (r.needsRewrite()) {
                // Keep the user's original next to the repaired file when we had to fix something.
                if (!r.repairs().isEmpty()) {
                    Files.copy(f, f.resolveSibling(f.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
                }
                write(f, r.config());
            }
            return r.config();
        } catch (IOException e) {
            LOG.error("Could not read {}; using defaults: {}", f, e.toString());
            return GalacticaConfig.defaults();
        }
    }

    /** Atomic write: temp file in the same directory, then move over the target. */
    public static void write(Path f, GalacticaConfig c) throws IOException {
        Path dir = f.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, c.toJson(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** {@code <game dir>/config/galactica.json}, or null outside a Fabric runtime (unit tests). */
    private static Path defaultFile() {
        if (file != null) return file;
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Test hook: forget the live value and file so the next {@link #get} reloads. */
    static void resetForTests(Path f) {
        synchronized (LOCK) {
            current = null;
            file = f;
            LISTENERS.clear();
        }
    }
}
