package io.izzel.minecraftmcp.neoforge.mixin;

import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Row geometry for list entries in mc.screen.state. */
@Mixin(AbstractSelectionList.class)
public interface AbstractSelectionListAccessor {
    @Invoker("getRowTop")
    int minecraftMcp$getRowTop(int index);

    @Invoker("getRowBottom")
    int minecraftMcp$getRowBottom(int index);
}
