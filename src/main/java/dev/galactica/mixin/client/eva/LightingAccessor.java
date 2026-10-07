package dev.galactica.mixin.client.eva;

import com.mojang.blaze3d.platform.Lighting;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Writes one entry of vanilla's diffuse-light UBO ({@link dev.galactica.client.eva.SpaceLighting}). */
@Mixin(Lighting.class)
public interface LightingAccessor {
    @Invoker("updateBuffer")
    void galactica$updateBuffer(Lighting.Entry entry, Vector3fc light0, Vector3fc light1);
}
