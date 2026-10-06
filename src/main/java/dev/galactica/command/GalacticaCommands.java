package dev.galactica.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.galactica.Galactica;
import dev.galactica.galaxy.GalaxyModel;
import dev.galactica.galaxy.StarCatalog;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavServer;
import dev.galactica.nav.NavState;
import dev.galactica.nav.Neighbourhood;
import dev.galactica.science.Units;
import dev.galactica.star.Star;
import dev.galactica.system.Body;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.List;

/**
 * <pre>
 * /galactica launch | land            leave for space / come back down
 * /galactica where                    galactic coordinates, region, nearest star
 * /galactica nearest [count]          the closest stars with spectral types
 * /galactica jump &lt;n&gt;                hyperjump to the n-th nearest star
 * /galactica goto &lt;star name&gt;        catalog stars: Sol, Sirius A, Betelgeuse, Sagittarius A* ...
 * /galactica coords &lt;x&gt; &lt;y&gt; &lt;z&gt;      galactocentric parsecs (the Sun is at 8178 0 20.8)
 * /galactica system                   planets and moons of the current system
 * /galactica orbit &lt;body&gt;              park on the day side of a planet or moon of this system
 * /galactica maxwarp &lt;pc/s&gt;          drive limit
 * </pre>
 */
public final class GalacticaCommands {
    private GalacticaCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("galactica")
                .then(Commands.literal("launch").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (!NavServer.launch(p)) {
                        c.getSource().sendFailure(Component.literal("The galactica:space dimension is missing (datapack failed to load?)"));
                        return 0;
                    }
                    say(c, "Launched. Fly with double-jump; hold sprint to spool the warp drive, sneak to cut it.");
                    return 1;
                }))
                .then(Commands.literal("land").executes(c -> {
                    NavServer.land(c.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("where").executes(GalacticaCommands::where))
                .then(Commands.literal("nearest")
                        .executes(c -> nearest(c, 10))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                .executes(c -> nearest(c, IntegerArgumentType.getInteger(c, "count")))))
                .then(Commands.literal("jump")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 200)).executes(GalacticaCommands::jump)))
                .then(Commands.literal("goto")
                        .then(Commands.argument("star", StringArgumentType.greedyString()).executes(GalacticaCommands::gotoStar)))
                .then(Commands.literal("system").executes(GalacticaCommands::system))
                .then(Commands.literal("orbit")
                        .then(Commands.argument("body", StringArgumentType.greedyString()).executes(GalacticaCommands::orbit)))
                .then(Commands.literal("coords")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg(-60_000, 60_000))
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg(-60_000, 60_000))
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg(-60_000, 60_000)).executes(c -> {
                                            ServerPlayer p = c.getSource().getPlayerOrException();
                                            NavServer.moveTo(p, GalacticPos.ofParsecs(DoubleArgumentType.getDouble(c, "x"),
                                                    DoubleArgumentType.getDouble(c, "y"), DoubleArgumentType.getDouble(c, "z")));
                                            return where(c);
                                        })))))
                .then(Commands.literal("maxwarp")
                        .then(Commands.argument("pc_per_s", DoubleArgumentType.doubleArg(1e-9, 1000)).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            double v = DoubleArgumentType.getDouble(c, "pc_per_s") * Units.PARSEC;
                            p.setAttached(Galactica.NAV, p.getAttachedOrCreate(Galactica.NAV).withMaxSpeed(v));
                            say(c, "Drive limit " + NavServer.formatSpeed(v));
                            return 1;
                        }))));
    }

    private static GalacticPos here(ServerPlayer p) {
        return Frames.playerPos(p.getAttachedOrCreate(Galactica.NAV), p.getX(), p.getY(), p.getZ());
    }

    private static int where(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        GalacticPos g = here(p);
        double x = g.xPc(), y = g.yPc(), z = g.zPc();
        double r = Math.sqrt(x * x + y * y);
        double dSol = g.distanceMetres(Frames.SOL_POS) / Units.LIGHT_YEAR;
        say(c, String.format("Galactocentric (%.1f, %.1f, %.1f) pc — R = %.2f kpc, %.0f pc %s the plane",
                x, y, z, r / 1000, Math.abs(z), z >= 0 ? "north of" : "south of"));
        say(c, String.format("Sector %s · %.4g ly from Sol · %s · %.3g stars/pc³",
                g.sector().code(), dSol, region(x, y, z), GalaxyModel.starDensity(x, y, z)));
        Neighbourhood n = NavServer.neighbourhood(p);
        Star s = n.nearestStar(g);
        if (s != null) {
            say(c, String.format("Nearest star: %s (%s), %s", s.name(), s.spectralType(),
                    distance(Neighbourhood.starPos(s).distanceMetres(g))));
        }
        say(c, "Warp: " + NavServer.formatSpeed(p.getAttachedOrCreate(Galactica.NAV).speed()));
        return 1;
    }

    static String region(double x, double y, double z) {
        double r = Math.sqrt(x * x + y * y);
        if (Math.sqrt(r * r + z * z) < 300) return "Galactic Centre";
        if (GalaxyModel.bulgeDensity(x, y, z) > 0.5) return "Galactic Bulge";
        if (Math.abs(z) > 3000 || r > 25_000) return "Stellar Halo";
        if (Math.abs(z) > 900) return "Thick Disk";
        String[] arms = {"Norma Arm", "Scutum–Centaurus Arm", "Sagittarius–Carina Arm", "Perseus Arm"};
        return GalaxyModel.armStrength(x, y) > 0.4 ? arms[GalaxyModel.nearestArm(x, y)] : "Inter-arm disk";
    }

    static String distance(double m) {
        if (m < 1e9) return String.format("%.0f km", m / 1e3);
        if (m < 0.05 * Units.LIGHT_YEAR) return String.format("%.3f AU", m / Units.AU);
        return String.format("%.3f ly (%.3f pc)", m / Units.LIGHT_YEAR, m / Units.PARSEC);
    }

    private static List<Star> sortedNeighbours(ServerPlayer p, GalacticPos g) {
        double x = g.xPc(), y = g.yPc(), z = g.zPc();
        return NavServer.neighbourhood(p).nearbyStars(g).stream()
                .filter(s -> Neighbourhood.starPos(s).distanceMetres(g) > 1e9)
                .sorted(Comparator.comparingDouble(s -> s.distanceTo(x, y, z)))
                .toList();
    }

    private static int nearest(CommandContext<CommandSourceStack> c, int count) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        GalacticPos g = here(p);
        List<Star> stars = sortedNeighbours(p, g);
        for (int i = 0; i < Math.min(count, stars.size()); i++) {
            Star s = stars.get(i);
            say(c, String.format("%2d. %-28s %-6s %s  M=%.2f M☉  L=%.3g L☉  %s", i + 1, s.name(), s.spectralType(),
                    distance(Neighbourhood.starPos(s).distanceMetres(g)), s.mass(), s.luminosity(),
                    s.catalog() ? "★ catalog" : ""));
        }
        return stars.size();
    }

    private static int jump(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        GalacticPos g = here(p);
        List<Star> stars = sortedNeighbours(p, g);
        int n = IntegerArgumentType.getInteger(c, "n");
        if (n > stars.size()) {
            c.getSource().sendFailure(Component.literal("Only " + stars.size() + " stars charted in this neighbourhood"));
            return 0;
        }
        arriveAt(p, stars.get(n - 1));
        say(c, "Hyperjump complete: " + stars.get(n - 1).name() + " (" + stars.get(n - 1).spectralType() + ")");
        return 1;
    }

    private static int gotoStar(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(c, "star");
        if (name.equalsIgnoreCase("earth") || name.equalsIgnoreCase("sol")) {
            NavState nav = p.getAttachedOrCreate(Galactica.NAV);
            NavServer.moveTo(p, Frames.nearEarth(nav.timeSeconds()));
            say(c, "Arrived in high Earth orbit.");
            return 1;
        }
        Star s = StarCatalog.byName(name);
        if (s == null) {
            c.getSource().sendFailure(Component.literal("Unknown catalog star: " + name));
            return 0;
        }
        arriveAt(p, s);
        say(c, "Arrived at " + s.name() + " (" + s.spectralType() + ")");
        return 1;
    }

    /**
     * Drops out of hyperspace where the star appears ~10° across — a safe, dramatic arrival —
     * and never closer than 0.05 AU; black holes are approached to 40 Schwarzschild radii.
     */
    static void arriveAt(ServerPlayer p, Star s) {
        double r = s.radiusMetres();
        double d = s.kind() == dev.galactica.star.StarKind.BLACK_HOLE ? 40 * r : Math.max(0.05 * Units.AU, r / Math.tan(Math.toRadians(5)));
        GalacticPos sp = Neighbourhood.starPos(s);
        // Arrive on the side facing where we came from, slightly above the plane.
        double[] from = here(p).metresFrom(sp);
        double len = Math.sqrt(from[0] * from[0] + from[1] * from[1] + from[2] * from[2]);
        double ux = len > 0 ? from[0] / len : 1, uy = len > 0 ? from[1] / len : 0, uz = len > 0 ? from[2] / len : 0;
        uz += 0.3;
        double n = Math.sqrt(ux * ux + uy * uy + uz * uz);
        NavServer.moveTo(p, sp.plusMetres(ux / n * d, uy / n * d, uz / n * d));
    }

    private static int system(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        GalacticPos g = here(p);
        Neighbourhood n = NavServer.neighbourhood(p);
        Star s = n.systemStar(g);
        if (s == null) {
            c.getSource().sendFailure(Component.literal("Interstellar space: no system within 0.6 pc"));
            return 0;
        }
        say(c, String.format("%s — %s, %.2f M☉, %.3g L☉, %.0f K, %.2f Gyr, [Fe/H] %+.2f; HZ %.2f–%.2f AU",
                s.name(), s.spectralType(), s.mass(), s.luminosity(), s.temperature(), s.ageGyr(), s.metallicity(),
                s.habitableZoneAu()[0], s.habitableZoneAu()[1]));
        List<Body> bodies = n.systemBodies();
        if (bodies.isEmpty()) say(c, "  No planets.");
        for (Body b : bodies) {
            say(c, String.format("  %-22s %-22s a=%.3f AU  %.2f M⊕  %.2f R⊕  g=%.2f  %.0f K  %s%s%s",
                    b.name(), b.type().label, b.orbit().semiMajorAxis() / Units.AU, b.massEarth(), b.radiusEarth(),
                    b.surfaceGravityG(), b.surfaceTemp(), b.atmosphere(),
                    b.life() != Body.Life.NONE ? " · life: " + b.life() : "",
                    b.moons().isEmpty() ? "" : " · " + b.moons().size() + " moons"));
        }
        return bodies.size();
    }

    /**
     * Parks the player on the star-facing side of a body, at a distance where it spans ~40 degrees of
     * sky (rings included), offset 25 degrees off the star line so the terminator shows.
     */
    private static int orbit(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        GalacticPos g = here(p);
        Neighbourhood n = NavServer.neighbourhood(p);
        Star s = n.systemStar(g);
        String name = StringArgumentType.getString(c, "body");
        if (s == null) {
            c.getSource().sendFailure(Component.literal("Interstellar space: no system within 0.6 pc"));
            return 0;
        }
        double t = p.getAttachedOrCreate(Galactica.NAV).timeSeconds();
        for (Neighbourhood.Located l : n.locateBodies(g, t)) {
            Body b = l.body();
            boolean match = b.name().equalsIgnoreCase(name) || b.name().equalsIgnoreCase(s.name() + " " + name);
            if (!match) continue;
            double extent = b.rings() ? 2.4 : 1.0;
            double d = b.radiusMetres() * extent / Math.tan(Math.toRadians(20));
            double[] toStar = Neighbourhood.starPos(s).metresFrom(l.pos());
            double len = Math.sqrt(toStar[0] * toStar[0] + toStar[1] * toStar[1] + toStar[2] * toStar[2]);
            double ux = toStar[0] / len, uy = toStar[1] / len, uz = toStar[2] / len;
            // Rotate the star direction 25 degrees about galactic z so the view shows a gibbous phase, not a flat full disc.
            double a = Math.toRadians(25), ca = Math.cos(a), sa = Math.sin(a);
            double vx = ux * ca - uy * sa, vy = ux * sa + uy * ca, vz = uz + 0.15;
            double vn = Math.sqrt(vx * vx + vy * vy + vz * vz);
            NavServer.moveTo(p, l.pos().plusMetres(vx / vn * d, vy / vn * d, vz / vn * d));
            say(c, String.format("In orbit of %s (%s), altitude %s", b.name(), b.type().label, distance(d - b.radiusMetres())));
            return 1;
        }
        c.getSource().sendFailure(Component.literal("No body named " + name + " in " + s.name() + " (try /galactica system)"));
        return 0;
    }

    private static void say(CommandContext<CommandSourceStack> c, String s) {
        c.getSource().sendSuccess(() -> Component.literal(s), false);
    }
}
