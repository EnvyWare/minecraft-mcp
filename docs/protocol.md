# Protocol

The first implementation uses MCP-compatible JSON-RPC 2.0 over localhost HTTP POST `/mcp`.

Supported methods:

- `initialize`
- `tools/list`
- `tools/call`

Implemented tools:

- `mc.world.join` supports normal world creation by default and accepts `preset`/`generator: "flat"` or `"superflat"` for superflat test worlds.

- `mc.client.state` returns both `inWorld` and `rawInWorld`. `rawInWorld` means the underlying client level/player exists; `inWorld` means the client is in a playable world state with no loading/GUI screen blocking normal controls.

- `mc.client.state` reports `loading` while a resource loading overlay is showing. `mc.world.join` waits for loading to finish before opening a world.

- `mc.client.state` and `mc.debug.capabilities` also report `background` (whether background mode is on) and `window` (visibility, focus, mouse grab, frame rate). `mc.debug.capabilities` reports `windowControl` (whether `mc.window.*` is supported) and, on NeoForge, `earlyWindowControl`. See [headless.md](headless.md#background-mode-windowsmacoslinux-desktop).

- Screen, keyboard and entity tools are described in [input-and-entity-tools.md](input-and-entity-tools.md).

- Other mods can add tools through the [tool provider SPI](tool-provider-spi.md). `mc.debug.capabilities` lists them under `toolProviders`.

- `mc.client.state`
- `mc.client.stop`
- `mc.player.state`
- `mc.player.swing`
- `mc.player.look`
- `mc.player.look_at`
- `mc.player.look_at_entity`
- `mc.entity.list`
- `mc.entity.interact`
- `mc.player.use_item`
- `mc.player.attack.block`
- `mc.player.destroy.block`
- `mc.player.drop`
- `mc.player.jump`
- `mc.inventory.state`
- `mc.inventory.find`
- `mc.inventory.count`
- `mc.inventory.selected`
- `mc.container.state`
- `mc.container.click`
- `mc.container.quick_move`
- `mc.container.drop`
- `mc.container.close`
- `mc.hotbar.select`
- `mc.command.run`
- `mc.command.suggest`
- `mc.server.sync`
- `mc.screen.current`
- `mc.ticks.wait`
- `mc.keyboard.press`
- `mc.keyboard.hold`
- `mc.screen.mouse.drag`
- `mc.screen.scroll`
- `mc.screenshot.take`
- `mc.packet.recording.start`
- `mc.packet.recording.stop`
- `mc.packet.recording.clear`
- `mc.packet.recording.status`
- `mc.packet.dump`
- `mc.packet.wait`
- `mc.debug.capabilities`
- `mc.window.state`
- `mc.window.show`
- `mc.window.hide`
- `mc.scenario.batch.run`
- `mc.scenario.report`
