package dev.galactica.client.dev;

/**
 * Dev autotest only ({@code -Dgalactica.autotest=true}): the run shares a desktop with whoever sits at it, and a key
 * pressed or a mouse moved over the focused game window used to disengage the autopilot mid-descent (MODLOG gotcha 11:
 * the built ship's Mars landing cut by "manual input (jump)"). While {@link #GATED}, every OS keyboard and mouse event
 * is dropped at its GLFW callback ({@code mixin.client.dev.*GateMixin}). The scripts drive the game through
 * {@code KeyMapping.setDown} and direct calls, which never pass through those handlers, so the scripted manual-override
 * checks still prove that pilot input takes control back. {@code -Dgalactica.autotest.realInput=true} lifts the gate.
 */
public final class AutotestInput {
    private AutotestInput() {}

    public static final boolean GATED = Boolean.getBoolean("galactica.autotest") && !Boolean.getBoolean("galactica.autotest.realInput");

    /** OS events dropped so far (logged at the end of a run). */
    public static int dropped;

    public static boolean drop() {
        if (GATED) dropped++;
        return GATED;
    }
}
