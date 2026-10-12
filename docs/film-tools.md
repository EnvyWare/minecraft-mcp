# Film tools

Tools for making smooth in-game videos with a handful of calls. The camera moves, frame capture and encoding all run
inside the client, so a whole shot is one tool call instead of several round trips per frame.

- `mc.film.run`: run a scene file (world setup, shots, output) in one call. Start here.
- `mc.camera.play`: play keyframed camera shots and encode them to an mp4 at a fixed frame rate.
- `mc.camera.stop`: abort a running `mc.camera.play`.
- `mc.record.start`, `mc.record.stop`, `mc.record.state`: record what the client renders in real time, e.g. a battle.
- `mc.hud.state`, `mc.hud.set`: hide or show the HUD (F1) directly.

Supported on NeoForge. Fabric reports the tools as not implemented. Works in [background mode](headless.md#background-mode-windowsmacoslinux-desktop):
frames are read from the game's own framebuffer, so the window can stay hidden.

## ffmpeg

Videos are encoded by piping raw frames into an external [ffmpeg](https://ffmpeg.org/) with libx264. The mod looks for it in this order:

1. the `ffmpeg` argument of the call (must be an existing file named `ffmpeg*`, so the endpoint cannot start other programs);
2. the `minecraftMcp.ffmpeg` system property or the `MINECRAFT_MCP_FFMPEG` environment variable (the bridge passes its
   environment to the client it launches);
3. `ffmpeg` on the `PATH`.

Without ffmpeg the frames are written as a PNG sequence instead (`<name>_frames/frame_00000.png`, ...) and the result
has a warning. `"format": "png"` asks for a PNG sequence on purpose.

## `mc.film.run`

Runs a scene: joins (or creates) the world, sets the game up for filming, plays every shot with `mc.camera.play` and
returns a compact report. One call per take.

```json
{"scene": "C:/path/to/examples/film/flythrough.json", "draft": true}
```

| Argument | Meaning |
|---|---|
| `scene` | Path of a JSON scene file (relative paths are under the game directory), or the scene object itself. |
| `draft` | Quick take: the scene's `draft` overrides (or 640x360 at 10 fps), written next to the output as `<name>-draft.mp4`. |
| `shots` | Only run the shots with these names (a name or a list), e.g. to iterate on one shot. |
| `skipSetup` | Skip the world and game setup (the client is already set up from an earlier run). |
| `output` | Override the output path. |
| `verbose` | Also return the full `mc.camera.play` report as `cameraReport`. |

Example scene ([examples/film/flythrough.json](../examples/film/flythrough.json)):

```json
{
  "name": "spawn-flythrough",
  "origin": "spawn",
  "setup": {
    "world": {"name": "film_flythrough", "seed": 424242},
    "time": 1000,
    "weather": "clear",
    "clearEntities": true
  },
  "camera": {"freezeWorld": true},
  "output": {"path": "films/spawn-flythrough.mp4", "width": 1280, "height": 720, "fps": 30},
  "draft": {"width": 640, "height": 360, "fps": 10},
  "shots": [
    {"name": "crane", "seconds": 6, "camera": [[-24, 3, -24], [-20, 18, -36], [-4, 40, -52]], "lookAt": [0, 4, 0], "clearance": true},
    {"name": "flyover", "seconds": 10, "camera": [[-4, 40, -52], [40, 34, -10], [100, 30, 40], [170, 34, 70]], "pitchMin": 5, "pitchMax": 30, "clearance": true},
    {"name": "orbit", "preset": "orbit", "center": [0, 0, 0], "radius": 48, "height": 36, "degrees": 120, "lookHeight": 4, "seconds": 8, "clearance": true}
  ]
}
```

Scene fields:

| Field | Meaning |
|---|---|
| `name` | Scene name, used for the default output name. |
| `origin` | `"spawn"` (the world spawn) or `[x, y, z]`: every shot coordinate (`camera`, `path`, `look`, `lookAt`, `center`) is relative to it. The spawn does not move between runs, unlike the player. |
| `setup` | World and game setup, below. |
| `shots` | Shots, as for [`mc.camera.play`](#shots). |
| `output` | Output arguments of `mc.camera.play` (`path`, `fps`, `width`, `height`, `crf`, `preset`, `format`, `ffmpeg`, `contactSheet`). |
| `camera` | Behaviour arguments of `mc.camera.play` (`freezeWorld`, `readyRadius`, `hideHud`, ...). |
| `draft` | Output overrides for `draft: true` runs, e.g. `{"width": 640, "height": 360, "fps": 10}`. |

`setup` fields, run in this order:

| Field | Default | Meaning |
|---|---|---|
| `world` | none | `mc.world.join` arguments (`name`, `seed`, `preset`), or just the name. Skipped when the client is already in a world of that name; another world is left first. Created with structures unless `"structures": false`. Without `world`, the scene films the current world. |
| `worldTimeoutSeconds` | `300` | How long to wait for the world to load. |
| `quiet` | `true` | Gamerules for a still, quiet scene: `sendCommandFeedback`, `doDaylightCycle`, `doWeatherCycle`, `doMobSpawning`, `doPatrolSpawning`, `doTraderSpawning`, `doInsomnia`, `doFireTick`, `announceAdvancements` all `false`. |
| `gamemode` | `spectator` | Game mode. |
| `gamerules` | none | More gamerules, e.g. `{"randomTickSpeed": 0}`; these win over `quiet`. |
| `time` | unchanged | `/time set` value: ticks or `day`, `noon`, `night`, `midnight`. |
| `weather` | unchanged | `clear`, `rain` or `thunder`. |
| `clearEntities` | off | `true` kills every non-player entity (and the items and XP it drops); or a selector such as `"type=!minecraft:player,type=!minecraft:villager"`. Silent when nothing matches. |
| `commands` | none | More commands, in order. |
| `steps` | none | Tool calls in the [scenario step format](scenarios.md) (`tool`, `args`, `expect`), e.g. a mod's own setup tool. `"optional": true` turns a failure into a warning. |

Commands are sent through the client connection (the world needs cheats, which `mc.world.join` worlds have), then a
`mc.server.sync` round trip makes sure the server has run them before filming starts.

Report:

```json
{
  "status": "ok",
  "scene": "spawn-flythrough",
  "output": "C:\\...\\films\\spawn-flythrough.mp4",
  "format": "mp4", "width": 1280, "height": 720, "fps": 30, "frames": 720, "seconds": 24.0, "draft": false,
  "timing": {"wallSeconds": 41.2, "setupSeconds": 12.5, "preloadSeconds": 9.1, "captureSeconds": 18.3, "captureFps": 39.3, "encodeTailSeconds": 0.4, "forcedFrames": 0},
  "quality": {"verdict": "ok", "frozenFrames": 0, "blackFrames": 0, "jitter": 0.08, "motionSpikes": 0, "...": "..."},
  "contactSheet": "C:\\...\\films\\spawn-flythrough.contact.png",
  "shots": [{"name": "crane", "frames": 180, "lift": 0, "captureFps": 35.2, "forcedFrames": 0, "maxSpeed": 14.1, "maxTurnRate": 22.5}],
  "origin": [0.5, 72.0, -15.5],
  "setup": {"world": "created film_flythrough", "commands": 16, "steps": 0},
  "warnings": []
}
```

A typical loop: run with `draft: true`, read the contact sheet, adjust the shots, run again (the world is reused, so
only the shots run again), then run without `draft` for the final take.

Why a scene format next to scenarios: a scenario is a test (steps with expectations, pass or fail, run in batches
from a directory), while a scene describes a take: a world, a camera script and an output. Scenes reuse the scenario
machinery where they overlap: setup `steps` are scenario steps run by the same engine, and `mc.film.run` and
`mc.camera.play` can be steps of a scenario. Scene files live outside scenario directories (`examples/film/`), so
batch runs do not pick them up. Scenes are JSON only; YAML would need a parser the mod does not ship.

## `mc.camera.play`

```json
{
  "output": "films/flythrough.mp4",
  "width": 1280,
  "height": 720,
  "fps": 30,
  "shots": [
    {
      "name": "valley",
      "seconds": 8,
      "camera": [[-7, 115, -10], [40, 112, -5], [90, 110, 10], [140, 115, 0]],
      "lookAt": [150, 90, 40],
      "clearance": true
    }
  ]
}
```

Blocks until every shot has been recorded and encoded, then returns a report (below). The player must be in a world;
put it in spectator mode first (`/gamemode spectator`), because the player is moved along with the camera.

How it works:

- Frame `n` of a shot is the camera pose at time `n / fps`, whatever the capture speed, so the output is smooth even
  when the client renders slowly. Frames that are not ready are rendered again at the same pose and not captured.
- Before each frame is captured, the chunks within `readyRadius` of the camera must be loaded and no render sections
  may be waiting to compile or upload. After `frameTimeoutMs` the frame is captured anyway and counted in `forcedFrames`.
- The server only sends chunks around the player, and the client centres its render grid on the player, so the player
  is moved to the camera every frame (client-side; the server accepts it from a singleplayer owner).
- Before recording a shot, a preload pass moves the camera along the path in steps of `readyRadius` chunks and waits
  at each stop until the chunks within `preloadRadius` are loaded, so world generation happens before recording.
- The HUD is hidden, the frame-rate cap and vsync are lifted, and the window is resized to `width`x`height` for the
  duration, then everything is restored. If the window cannot be resized, frames are scaled and a warning says so.

Output arguments:

| Argument | Default | Meaning |
|---|---|---|
| `output` (or `path`) | `films/<name>-<time>.mp4` | Output file. Relative paths are under the game directory. |
| `fps` | `30` | Output frame rate. |
| `width`, `height` | window size | Output size. The window is resized to it (`resize: false` scales instead). |
| `draft` | `false` | Quick look: 640x360 at 10 fps, x264 `ultrafast`, CRF 28. Overrides `fps`, `width`, `height`, `crf`, `preset`. |
| `format` | `mp4` | `mp4` or `png` (a PNG sequence directory). |
| `crf`, `preset` | `18`, `veryfast` | x264 quality and speed. |
| `ffmpeg` | see above | Path to the ffmpeg executable. |
| `contactSheet` | `16` | Number of thumbnails on the contact sheet; `0` for none. |

Behaviour arguments:

| Argument | Default | Meaning |
|---|---|---|
| `hideHud` | `true` | Hide the HUD (`options.hideGui`) while recording. |
| `movePlayer` | `true` | Move the player with the camera so chunks load around it. |
| `freezeWorld` | `false` | Run `/tick freeze` while recording (entities, water and the sun stand still), `/tick unfreeze` after. |
| `readyRadius` | `6` | Chunks around the camera that must be loaded before a frame is captured (capped at render distance - 1). |
| `preloadRadius` | `8` | Chunks that must be loaded at each preload stop. |
| `frameTimeoutMs` | `2000` | Longest wait for one frame before capturing it anyway. |
| `preloadTimeoutMs` | `60000` | Longest wait at one preload stop. |

The shots can be given as `shots` (a list), `shot` (one object), or as shot fields at the top level.

### Shots

Common fields:

| Field | Default | Meaning |
|---|---|---|
| `name` | `shot<n>` | Name used in the report. |
| `seconds` (or `duration`) | `6` | Length of the shot. Frames = `round(seconds * fps)`. |
| `easing` | `easeInOut` | `linear`, `easeInOut`, `easeInOutCubic`, `easeInOutSine`, `easeIn`, `easeOut`. `"linear": true` also works. |
| `spacing` | `arc` | `arc`: constant speed along the path, however the keyframes are spaced. `uniform`: equal time per keyframe segment. |
| `pitchMin`, `pitchMax` | none | Clamp the camera pitch (degrees, positive looks down). |
| `smoothing` | `0.17` | Time constant in seconds of the look smoothing (yaw and pitch follow the target with this lag); `0` for none. Defaults to `0` for orbits, fixed `lookAt` points and explicit angles. |
| `fov` | game setting | Field of view in degrees: one number, or keyframes `[70, 50]` spread over the shot. |
| `roll` | `0` | Camera roll in degrees: one number or keyframes. |
| `clearance` | off | `true` (3 blocks) or a number: lift the whole move until every point has that many air blocks around and above the camera. |
| `preload` | `true` | Run the preload pass before the shot. |

Presets (`preset`):

- `path` (default): the camera flies through `camera` keyframes (`cam` also works), `[x, y, z]` or `{x, y, z}`. Where it looks:
  - `look`: keyframes reached at the same time as the camera keyframes;
  - `lookAt`: one fixed point (or a list, same as `look`);
  - `target`: an entity, tracked every frame: `{"id": 123}`, `{"uuid": "..."}` or `{"type": "minecraft:pig", "radius": 32}`;
  - `angles`: `[[yaw, pitch], ...]` keyframes (or `yaw` and `pitch` numbers);
  - none of these: along the direction of travel.
- `orbit`: circles `center` (a point) or `target` (an entity, followed as it moves) at `radius` (8) and `height` (3) above
  it, starting at `startAngle` (0: north of the centre looking south) and turning `degrees` (90). Looks at the centre
  plus `lookHeight` (0).
- `follow`: flies `height` (2) above a `path` (keyframes along a road or river), `back` (0) blocks behind the point on
  the path, looking `lookAhead` (12) blocks further along it at `lookHeight` (1).

The path is a centripetal Catmull-Rom spline through the keyframes: it passes through every keyframe and does not
overshoot or loop when keyframes are unevenly spaced.

### Report

```json
{
  "status": "ok",
  "output": {"format": "mp4", "path": "C:\\...\\films\\flythrough.mp4", "width": 1280, "height": 720, "bytes": 9306325},
  "frames": 150,
  "fps": 30,
  "seconds": 5.0,
  "quality": {"frames": 150, "blackFrames": 0, "frozenFrames": 0, "longestFrozenRun": 0, "meanMotion": 4.22, "jitter": 0.07, "motionSpikes": 0, "verdict": "ok", "warnings": []},
  "contactSheet": "C:\\...\\films\\flythrough.contact.png",
  "renderSize": {"width": 1280, "height": 720},
  "draft": false,
  "timing": {"wallSeconds": 4.94, "preloadSeconds": 2.32, "captureSeconds": 2.08, "captureFps": 72.2, "encodeTailSeconds": 0.28, "waitedFrames": 106, "forcedFrames": 0},
  "shots": [{"name": "valley", "preset": "path", "seconds": 5, "easing": "easeInOut", "pathLength": 151.27, "preloadStops": 3, "frames": 150, "lift": 0, "captureFps": 72.2, "maxSpeed": 60.71, "maxTurnRate": 51.35, "...": "..."}],
  "warnings": []
}
```

- `contactSheet`: one PNG with evenly spaced thumbnails (first and last frame included), each labelled with its frame
  number and time. Read this one image to check a take.
- `quality`, computed from small luma thumbnails of every frame:
  - `blackFrames`: mean brightness below 8/255.
  - `frozenFrames`, `longestFrozenRun`: frames that barely differ from the previous one (mean change below 0.25/255).
    In `mc.camera.play`, frames where the camera itself hardly moved (the eased ends of a shot, a hold) are counted as
    `stillFrames` instead; in a recording every unchanged frame counts as frozen.
  - `meanMotion`: average change between frames.
  - `jitter`: how unevenly the picture changes from frame to frame, relative to `meanMotion`. Smooth moves are well below 0.3.
  - `motionSpikes`: frames whose change is far below or above both neighbours' (a stutter or pop-in).
  - `verdict`: `ok`, or `check` with `warnings`.
  The first frame of each shot is not compared with the frame before it (that is a cut).
- `timing.captureFps`: output frames per second of capture time, the number to compare with real-time playback.
  `waitedFrames` were held back until the world around the camera was ready; `forcedFrames` were captured anyway after
  `frameTimeoutMs`.
- Per shot: `lift` (blocks added by `clearance`), `pathLength`, `preloadStops`, `maxSpeed` (blocks/second) and
  `maxTurnRate` (degrees/second) of the camera, useful to spot a move that is too fast.

## `mc.camera.stop`

Aborts a running `mc.camera.play` (it then fails with "stopped by mc.camera.stop") and restores the client settings.
Returns `{"status": "stopping"}`, or `{"status": "idle"}` when nothing is playing.

## `mc.record.start`, `mc.record.stop`, `mc.record.state`

For things that must play in real time, such as a Pixelmon battle, record whatever the client renders: the player's
view, or the view another tool sets up.

```json
{
  "output": "films/battle.mp4",
  "fps": 30,
  "width": 1280,
  "height": 720,
  "maxSeconds": 120
}
```

`mc.record.start` returns at once (`{"status": "recording", "output": ...}`). Output frame `k` is the frame rendered
last at wall-clock time `k / fps`; when rendering falls behind, the previous frame is repeated so the video keeps
real-time pace (`duplicatedFrames`, with a warning above 5%). While recording, the frame-rate cap is raised to twice
the output rate (at least 60).

It takes the same output arguments as `mc.camera.play` (`output`, `fps`, `width`, `height`, `draft`, `format`, `crf`,
`preset`, `ffmpeg`, `contactSheet`) plus `hideHud` (default `true`), `resize` (default `true`) and `maxSeconds`
(default `600`; the recording stops by itself after that, and `mc.record.stop` then returns its report).

`mc.record.stop` finishes the file and returns the same report as `mc.camera.play` (output, frames, quality, contact
sheet) with `timing.renderedFps`, `capturedFrames` and `duplicatedFrames`. `mc.record.state` returns
`{"recording": true, "seconds": ..., "frames": ..., "duplicatedFrames": ...}` or `{"recording": false}`.

Only one recording or camera shot runs at a time.

## `mc.hud.state`, `mc.hud.set`

```json
{"hidden": true}
```

`mc.hud.set` sets the F1 state (`options.hideGui`) directly, which is reliable in background mode where a simulated F1
press is not; `{"visible": true}` also works. Nothing is written to `options.txt`. Both tools return:

```json
{"hidden": true, "debugScreen": false, "screen": null}
```

`mc.camera.play` and `mc.record.start` hide the HUD for their duration by default (`hideHud`) and restore it after.
Mods that draw overlays outside the vanilla HUD layers may ignore F1; hide those with the mod's own settings.
