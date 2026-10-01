package io.izzel.minecraftmcp.neoforge.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.izzel.minecraftmcp.background.BackgroundWindow;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * In background mode, never pause on lost focus. Reads the option as false instead of changing it,
 * so nothing is ever written to options.txt.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;pauseOnLostFocus:Z"))
    private boolean minecraftMcp$pauseOnLostFocus(boolean original) {
        return original && !BackgroundWindow.suppressPauseOnLostFocus();
    }
}
