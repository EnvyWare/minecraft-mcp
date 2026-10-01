# Headless support

Minecraft client still needs a graphics context. The supported CI path is virtual display rather than true headless.

Recommended Linux command:

```bash
xvfb-run -a ./gradlew :fabric:runClient -DminecraftMcp.scenarioDir=examples/scenarios -DminecraftMcp.batchExit=true
```

Environment/configuration:

- `MINECRAFT_MCP_HEADLESS=true`
- `MINECRAFT_MCP_BIND=127.0.0.1`
- `MINECRAFT_MCP_PORT=0`
- `MINECRAFT_MCP_SCENARIO_DIR=examples/scenarios`
- `MINECRAFT_MCP_BATCH_EXIT=true`

If no OpenGL context can be created, only logic-level tests such as parser and JSON-RPC unit tests are expected to pass.

## Background mode (Windows/macOS/Linux desktop)

Background mode runs a normal client on your desktop while you keep using the machine. The window stays hidden, never
takes focus, never grabs or confines the mouse, the game does not pause when unfocused, and sound is muted. Agents drive
it exactly as before; `mc.screenshot.take` reads the game's own framebuffer, so screenshots work while the window is hidden.

Supported on NeoForge. Fabric does not implement it yet and reports `"background": false` and `"windowControl": false`
in `mc.debug.capabilities`.

### Setup

1. Disable NeoForge's early loading window in `<gameDir>/config/fml.toml` (in a dev workspace: `runs/client/config/fml.toml`):

   ```toml
   earlyWindowControl = false
   ```

   NeoForge creates and shows its loading window before any mod is loaded, so a mod cannot stop it. With it enabled,
   background mode still works but the loading window appears and takes focus for several seconds at startup, until the
   mod hides it. The client logs a warning and `mc.debug.capabilities` reports `"earlyWindowControl": true` in that case.
   NeoForge's own description says disabling it "disables new GL features"; vanilla rendering is unaffected.
   If you edit the file from PowerShell 5, write it without a BOM (`Set-Content -Encoding utf8` adds one and NeoForge then fails to parse it).

2. Start the client with background mode on:

   ```bash
   ./gradlew :neoforge:runClient -DminecraftMcp.background=true
   ```

   or set `MINECRAFT_MCP_BACKGROUND=true` in the environment (also works for launchers and dependent projects).

Configuration:

| System property | Environment variable | Default | Meaning |
|---|---|---|---|
| `minecraftMcp.background` | `MINECRAFT_MCP_BACKGROUND` | `false` | Enable background mode. |
| `minecraftMcp.backgroundMaxFps` | `MINECRAFT_MCP_BACKGROUND_MAX_FPS` | `30` | In-world frame rate cap while hidden. `0` disables the cap. Menus keep vanilla's 60 fps limit. |
| `minecraftMcp.backgroundMute` | `MINECRAFT_MCP_BACKGROUND_MUTE` | `true` | Mute all game sound. |

### What it changes

- The game window is created hidden with GLFW `VISIBLE=false` and `FOCUS_ON_SHOW=false`.
- The client is kept "inactive", so vanilla's own guard in `MouseHandler.grabMouse()` never captures the cursor.
- The "pause on lost focus" check is bypassed in memory. Sound is muted by setting the master listener gain to zero.
  Nothing is written to `options.txt`: your `pauseOnLostFocus` and volume settings are untouched.
- The frame rate is capped while hidden. An unthrottled hidden client renders at the full limit (measured: ~118 fps,
  about half a CPU core and ~11% GPU on an RTX 4070 Ti), because GPU drivers do not throttle hidden windows.

### Looking in

- `mc.window.show` shows the window without taking focus and restores your normal frame rate limit.
- `mc.window.hide` hides it again, releases the mouse and re-applies the cap.
- `mc.window.state` reports `visible`, `focused`, `active`, `mouseGrabbed`, `width`, `height`, `framerateLimit`, `fps` and `muted`.

While the window is shown, it behaves like a normal game window: if you click into it, it gets focus and the game can
grab the mouse as usual. Call `mc.window.hide` to hand it back to the agent.

`mc.client.state` and `mc.debug.capabilities` include `"background": true|false` and the `window` object.

### Scenarios

Scenarios can require background mode with `"requires": {"background": true}` (or `false`). See `examples/scenarios/window/`.
Run the examples in background mode:

```bash
./gradlew :neoforge:runClient -DminecraftMcp.background=true \
  -DminecraftMcp.scenarioDir=$PWD/examples/scenarios -DminecraftMcp.batchExit=true
```

Batch runs print one `[Minecraft MCP] scenario ...` line per scenario and write the full report to `<gameDir>/mcp/scenario-report.json`.
