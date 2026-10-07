package dev.galactica.client.nav;

import dev.galactica.Galactica;
import dev.galactica.client.sky.SkyManager;
import dev.galactica.nav.Frames;
import dev.galactica.nav.GalacticPos;
import dev.galactica.nav.NavState;
import dev.galactica.nav.autopilot.AutopilotLegs;
import dev.galactica.nav.autopilot.AutopilotMath;
import dev.galactica.nav.autopilot.AutopilotServer;
import dev.galactica.nav.autopilot.AutopilotState;
import dev.galactica.nav.target.NavTarget;
import dev.galactica.nav.target.NavTargets;
import dev.galactica.nav.target.TargetGeometry;
import dev.galactica.science.Units;
import dev.galactica.ship.DriveRules;
import dev.galactica.ship.Starship;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * The locked target as the HUD, the cockpit display and the map show it, computed once per client tick: its name,
 * distance, the autopilot's phase and ETA (the server's, or an estimate from the drive rules when it is off), and its
 * direction in the space frame for the on-screen marker. A passenger reads the pilot's target and autopilot (both are
 * synced to everyone tracking the pilot), so the whole crew sees the same course.
 *
 * @param target   the target
 * @param distance metres from the craft to its centre
 * @param surface  metres to its surface (or arrival sphere for a deep-sky object)
 * @param eta      seconds to arrival, negative when unknown
 * @param state    the autopilot driving this craft
 * @param dirMc    unit direction to the target in block coordinates (space only, else null)
 * @param viaJump  the estimate assumes hyperjumps (another system and the exotic matter for it)
 */
public record TargetReadout(NavTarget target, double distance, double surface, double eta, AutopilotState state,
                            double @Nullable [] dirMc, boolean viaJump, boolean fromPilot) {

    private static @Nullable TargetReadout current;

    public static @Nullable TargetReadout current() {
        return current;
    }

    /** The player whose course this client shows: the pilot of the ship it rides in, else itself. */
    public static Player courseOwner(Player self) {
        if (self.getVehicle() instanceof Starship s && s.getControllingPassenger() instanceof Player pilot) return pilot;
        return self;
    }

    public static void tick(Minecraft mc) {
        current = null;
        if (mc.player == null || mc.level == null) return;
        Player owner = courseOwner(mc.player);
        NavTarget t = owner.getAttached(NavTargets.TYPE);
        if (t == null) return;
        NavState nav = mc.player.getAttached(Galactica.NAV);
        if (nav == null) return;
        boolean space = mc.level.dimension() == Galactica.SPACE;
        Entity root = mc.player.getRootVehicle();
        GalacticPos here = space ? Frames.playerPos(nav, root.getX(), root.getY(), root.getZ()) : nav.origin();
        TargetGeometry.Resolved r = TargetGeometry.resolve(Galactica.GALAXY, t, nav.timeSeconds());
        if (r == null) return;
        double[] d = r.pos().metresFrom(here);
        double dist = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        AutopilotState ap = AutopilotServer.state(owner);
        double surface = dist - r.radius();
        boolean viaJump = false;
        double eta = ap.engaged() ? ap.eta() : -1;
        if (!ap.engaged()) {
            var leg = AutopilotLegs.leg(t, r, SkyManager.get().neighbourhood().systemStar(here)); // off space, here is where the craft re-enters it
            Starship ship = mc.player.getVehicle() instanceof Starship s ? s : null;
            double ly = dist / Units.LIGHT_YEAR;
            if (!leg.last() && ship != null && DriveRules.canJump(ly, ship.exotic())) {
                viaJump = true;
                eta = AutopilotMath.hyperjumpSeconds(1);
            } else {
                eta = AutopilotMath.warpSeconds(Math.max(0, leg.surfaceDistance(here)), leg.arrival(), nav.maxSpeed());
            }
        }
        double[] dir = null;
        if (space && dist > 0) {
            double[] m = Frames.galacticToMc(d[0] / dist, d[1] / dist, d[2] / dist);
            dir = m;
        }
        current = new TargetReadout(t, dist, surface, eta, ap, dir, viaJump, owner != mc.player);
    }

    /** "1.52 AU", "4.24 ly", "8,200 km". */
    public static String distanceText(double m) {
        return AutopilotServer.distance(m);
    }
}
