package dev.galactica.client.ship;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.joml.Quaternionf;

public class StarshipRenderState extends EntityRenderState {
    public final Quaternionf attitude = new Quaternionf();
    public float hurtTime;
    public int hurtDir;
    /** |main-engine throttle|, 0 … 1. */
    public float thrust;
    /** Hyperdrive spool progress, 0 idle … 1 about to jump. */
    public float spool;
}
