package io.izzel.minecraftmcp.neoforge.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.InputConstants;
import io.izzel.minecraftmcp.input.VirtualKeys;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Report keys held by MCP input as down, so polled checks (Screen.hasShiftDown, F3 combos) match real input. */
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
    @ModifyReturnValue(method = "isKeyDown", at = @At("RETURN"))
    private static boolean minecraftMcp$virtualKeyDown(boolean original, long window, int key) {
        return original || VirtualKeys.isDown(key);
    }
}
