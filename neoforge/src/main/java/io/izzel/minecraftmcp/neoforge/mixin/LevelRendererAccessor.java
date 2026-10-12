package io.izzel.minecraftmcp.neoforge.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The section visibility graph, to tell when a film frame's visible sections are settled. */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("sectionOcclusionGraph")
    SectionOcclusionGraph minecraftMcp$sectionOcclusionGraph();
}
