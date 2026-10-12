package io.izzel.minecraftmcp.neoforge.mixin;

import io.izzel.minecraftmcp.film.CameraPose;
import io.izzel.minecraftmcp.film.FilmCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a film shot plays, the camera is placed at the shot's pose for this frame instead of at the player's eyes.
 * The camera entity stays the player, so nothing else about rendering changes.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot, float roll);

    @Inject(method = "setup", at = @At("RETURN"))
    private void minecraftMcp$filmPose(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
        CameraPose pose = FilmCamera.pose();
        if (pose == null) return;
        setRotation(pose.yaw(), pose.pitch(), pose.roll());
        setPosition(pose.x(), pose.y(), pose.z());
    }
}
