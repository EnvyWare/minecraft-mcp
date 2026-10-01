package io.izzel.minecraftmcp.neoforge;

import io.izzel.minecraftmcp.background.BackgroundWindow;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;
import net.neoforged.neoforge.common.NeoForge;

final class NeoForgeBackground {
    private NeoForgeBackground() {}

    static void register(IEventBus modBus) {
        if (!BackgroundWindow.enabled()) return;
        if (earlyWindowControl()) {
            System.out.println("[Minecraft MCP] WARNING: background mode is on but earlyWindowControl=true in config/fml.toml. "
                    + "NeoForge's loading window will show and take focus until the game hides it. Set earlyWindowControl = false; see docs/headless.md.");
        }
        modBus.addListener(SoundEngineLoadEvent.class, event -> BackgroundWindow.get().onSoundEngineLoaded(event.getEngine()));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> BackgroundWindow.get().tick());
        Minecraft.getInstance().execute(BackgroundWindow.get()::init);
    }

    static boolean earlyWindowControl() {
        return FMLConfig.getBoolConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL);
    }
}
