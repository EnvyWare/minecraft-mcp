package io.izzel.minecraftmcp.neoforge.mixin;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.Future;

/** Whether a full visibility update is pending or running (it runs off-thread after the camera moves 8 blocks). */
@Mixin(SectionOcclusionGraph.class)
public interface SectionOcclusionGraphAccessor {
    @Accessor("needsFullUpdate")
    boolean minecraftMcp$needsFullUpdate();

    @Accessor("fullUpdateTask")
    Future<?> minecraftMcp$fullUpdateTask();
}
