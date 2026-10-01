package io.izzel.minecraftmcp.neoforge.mixin;

import com.mojang.blaze3d.platform.Window;
import io.izzel.minecraftmcp.background.BackgroundWindow;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In background mode, create the game window hidden and without focus-on-show. Only takes effect when
 * NeoForge's early window is disabled (earlyWindowControl=false); otherwise the window already exists.
 */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J"))
    private void minecraftMcp$backgroundWindowHints(CallbackInfo ci) {
        if (BackgroundWindow.enabled()) {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        }
    }
}
