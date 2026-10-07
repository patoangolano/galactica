package dev.galactica.client.dev;

import dev.galactica.Galactica;
import dev.galactica.perf.Perf;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Development-only performance benchmark, driven by {@link AutoTest} ({@code -Dgalactica.autotest.perf=true}).
 * A scene is measured between {@link #begin} and {@link #end}: frame times (from {@code Minecraft.runTick}),
 * render-thread allocation, GC time, renderpearl command counts, the inclusive CPU time of the mod's hot
 * paths ({@link Perf}), server tick time, loaded chunks, and a stack sampler over the render and server
 * threads (top self frames and the mod's own methods, inclusive). Each scene logs one {@code [perf]} block.
 */
public final class PerfBench {
    private PerfBench() {}

    private static volatile boolean recording;
    private static long lastFrame;
    private static long[] frames = new long[1 << 16];
    private static int frameCount;
    private static Thread renderThread, serverThread;
    private static long tickStart, tickMax, ticks, tickNanos;
    private static String scene;
    private static long startNanos, startAlloc, startTotalAlloc, startGc;
    private static Sampler sampler;
    private static final List<String> SUMMARY = new ArrayList<>();

    /** Registers the server tick timer once. */
    public static void install() {
        ServerTickEvents.START_SERVER_TICK.register(s -> {
            serverThread = Thread.currentThread();
            tickStart = recording ? System.nanoTime() : 0;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (tickStart == 0 || !recording) return;
            long d = System.nanoTime() - tickStart;
            ticks++;
            tickNanos += d;
            tickMax = Math.max(tickMax, d);
        });
    }

    /** From FrameTimeMixin, at the head of every client frame. */
    public static void frame() {
        renderThread = Thread.currentThread();
        if (!recording) return;
        long now = System.nanoTime();
        if (lastFrame != 0 && frameCount < frames.length) frames[frameCount++] = now - lastFrame;
        lastFrame = now;
    }

    /** Uncapped frame rate, no vsync, no AFK throttling, so frame times measure the work and not the limiter. */
    public static void unthrottle(Minecraft mc) {
        mc.options.framerateLimit().set(260);
        mc.options.enableVsync().set(false);
        mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
        Galactica.LOG.info("[perf] uncapped frame rate, vsync off, render distance {}", mc.options.getEffectiveRenderDistance());
    }

    public static void begin(String name) {
        scene = name;
        Perf.reset();
        frameCount = 0;
        lastFrame = 0;
        ticks = tickNanos = tickMax = 0;
        startNanos = System.nanoTime();
        startAlloc = renderAllocated();
        startTotalAlloc = totalAllocated();
        startGc = gcMillis();
        sampler = new Sampler();
        sampler.start();
        Perf.on = true;
        recording = true;
    }

    public static void end() {
        if (!recording) return;
        recording = false;
        Perf.on = false;
        sampler.stopSampling();
        Minecraft mc = Minecraft.getInstance();
        double seconds = (System.nanoTime() - startNanos) / 1e9;
        long[] f = Arrays.copyOf(frames, frameCount);
        int n = f.length;
        Arrays.sort(f);
        double sum = 0;
        for (long v : f) sum += v;
        double avgMs = n == 0 ? 0 : sum / n / 1e6;
        // 1 % low: the average frame rate over the slowest 1 % of frames.
        int worst = Math.max(1, n / 100);
        double worstSum = 0;
        for (int i = n - worst; i < n; i++) worstSum += i >= 0 ? f[i] : 0;
        double low1 = n == 0 ? 0 : 1e9 / (worstSum / worst);
        double fps = n / seconds;
        double allocMBs = (renderAllocated() - startAlloc) / 1048576.0 / seconds;
        double totalAllocMBs = (totalAllocated() - startTotalAlloc) / 1048576.0 / seconds;
        long gc = gcMillis() - startGc;
        double perFrame = Math.max(1, n);
        Runtime rt = Runtime.getRuntime();
        long heapMb = (rt.totalMemory() - rt.freeMemory()) / 1048576;
        int clientChunks = mc.level != null ? mc.level.getChunkSource().getLoadedChunksCount() : -1;
        int serverChunks = -1;
        var srv = mc.getSingleplayerServer();
        if (srv != null && mc.level != null) {
            ServerLevel sl = srv.getLevel(mc.level.dimension());
            if (sl != null) serverChunks = sl.getChunkSource().getLoadedChunksCount();
        }
        String line = String.format(Locale.ROOT,
                "scene=%s frames=%d fps=%.1f low1=%.1f frameMs avg=%.2f p50=%.2f p95=%.2f p99=%.2f max=%.2f | renderAlloc=%.1fMB/s allAlloc=%.1fMB/s gc=%dms heap=%dMB "
                        + "| draws/f=%.0f passes/f=%.1f bufWrites/f=%.1f bufKB/f=%.1f texWrites/f=%.2f texKB/f=%.1f bufCreates/f=%.2f bufCreateKB/f=%.1f texCreates=%d "
                        + "| mspt avg=%.2f max=%.2f ticks=%d | chunks client=%d server=%d",
                scene, n, fps, low1, avgMs, pct(f, 50), pct(f, 95), pct(f, 99), n == 0 ? 0 : f[n - 1] / 1e6,
                allocMBs, totalAllocMBs, gc, heapMb,
                Perf.draws / perFrame, Perf.renderPasses / perFrame, Perf.bufferWrites / perFrame, Perf.bufferWriteBytes / 1024.0 / perFrame,
                Perf.textureWrites / perFrame, Perf.textureWriteBytes / 1024.0 / perFrame, Perf.buffersCreated / perFrame,
                Perf.bufferCreateBytes / 1024.0 / perFrame, Perf.texturesCreated,
                ticks == 0 ? 0 : tickNanos / 1e6 / ticks, tickMax / 1e6, ticks, clientChunks, serverChunks);
        Galactica.LOG.info("[perf] {}", line);
        StringBuilder sec = new StringBuilder();
        for (Perf.S s : Perf.sections()) {
            long calls = Perf.calls(s);
            if (calls == 0) continue;
            boolean server = s.ordinal() >= Perf.S.SERVER_TICK.ordinal() && s.ordinal() < Perf.S.PLANET_CHUNK.ordinal();
            boolean worker = s.ordinal() >= Perf.S.PLANET_CHUNK.ordinal();
            double ms = Perf.nanos(s) / 1e6;
            // Client sections per frame, server sections per tick, worker sections as total ms and calls.
            if (worker) {
                sec.append(String.format(Locale.ROOT, " %s=%.1fms/%d", s, ms, calls));
            } else {
                double per = server ? Math.max(1, ticks) : perFrame;
                sec.append(String.format(Locale.ROOT, " %s=%.3f", s, ms / per));
            }
        }
        Galactica.LOG.info("[perf] scene={} sections (client ms/frame, server ms/tick, workers ms total/calls):{}", scene, sec);
        sampler.log(scene);
        SUMMARY.add(String.format(Locale.ROOT, "| %-18s | %7.1f | %7.1f | %6.2f | %6.2f | %6.2f | %7.1f | %6.0f | %6.2f | %6.2f |",
                scene, fps, low1, avgMs, pct(f, 99), allocMBs, totalAllocMBs, Perf.draws / perFrame,
                ticks == 0 ? 0 : tickNanos / 1e6 / ticks, tickMax / 1e6));
    }

    /** Logs every scene of this run as one Markdown table. */
    public static void summary() {
        Galactica.LOG.info("[perf] summary\n| scene              |  avg FPS |  1% low | avg ms | p99 ms | rAllocMB/s | allAllocMB/s | draws/f | mspt avg | mspt max |\n{}",
                String.join("\n", SUMMARY));
    }

    private static double pct(long[] sorted, int p) {
        if (sorted.length == 0) return 0;
        int i = Math.min(sorted.length - 1, (int) Math.ceil(p / 100.0 * sorted.length) - 1);
        return sorted[Math.max(0, i)] / 1e6;
    }

    private static long renderAllocated() {
        try {
            var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            return renderThread == null ? 0 : mx.getThreadAllocatedBytes(renderThread.threadId());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long totalAllocated() {
        try {
            return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getTotalThreadAllocatedBytes();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long gcMillis() {
        long t = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) t += Math.max(0, gc.getCollectionTime());
        return t;
    }

    /**
     * A plain stack sampler (every 5 ms) over the render and server threads: which frames are on top (self time)
     * and which of the mod's methods are anywhere on the stack (inclusive), as a share of the samples.
     */
    private static final class Sampler extends Thread {
        private volatile boolean running = true;
        private final Map<String, Integer> renderSelf = new HashMap<>(), renderMod = new HashMap<>(),
                serverSelf = new HashMap<>(), serverMod = new HashMap<>();
        private int renderSamples, serverSamples;

        Sampler() {
            super("Galactica perf sampler");
            setDaemon(true);
        }

        void stopSampling() {
            running = false;
            try {
                join(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            while (running) {
                Thread r = renderThread, s = serverThread;
                if (r != null) {
                    renderSamples++;
                    record(r.getStackTrace(), renderSelf, renderMod);
                }
                if (s != null && s.isAlive()) {
                    serverSamples++;
                    record(s.getStackTrace(), serverSelf, serverMod);
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        private static void record(StackTraceElement[] st, Map<String, Integer> self, Map<String, Integer> mod) {
            if (st.length == 0) return;
            // "Self" is the innermost game or mod frame: JDK and LWJGL frames (sleeps, GL calls) roll up into their caller.
            StackTraceElement top = st[0];
            for (StackTraceElement e : st) {
                String c = e.getClassName();
                if (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.") || c.startsWith("org.lwjgl")) continue;
                top = e;
                break;
            }
            self.merge(frame(top), 1, Integer::sum);
            Set<String> seen = new HashSet<>();
            for (StackTraceElement e : st) {
                if (!e.getClassName().startsWith("dev.galactica") || e.getClassName().startsWith("dev.galactica.client.dev")) continue;
                String k = frame(e);
                if (seen.add(k)) mod.merge(k, 1, Integer::sum);
            }
        }

        private static String frame(StackTraceElement e) {
            String c = e.getClassName();
            return c.substring(c.lastIndexOf('.') + 1) + "." + e.getMethodName();
        }

        void log(String scene) {
            Galactica.LOG.info("[perf] scene={} render self: {}", scene, top(renderSelf, renderSamples, 12));
            Galactica.LOG.info("[perf] scene={} render galactica (incl): {}", scene, top(renderMod, renderSamples, 12));
            Galactica.LOG.info("[perf] scene={} server self: {}", scene, top(serverSelf, serverSamples, 10));
            Galactica.LOG.info("[perf] scene={} server galactica (incl): {}", scene, top(serverMod, serverSamples, 10));
        }

        private static String top(Map<String, Integer> m, int samples, int k) {
            if (samples == 0) return "-";
            StringBuilder b = new StringBuilder();
            m.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(k).forEach(e ->
                    b.append(String.format(Locale.ROOT, " %s %.1f%%;", e.getKey(), 100.0 * e.getValue() / samples)));
            return b.toString();
        }
    }
}
