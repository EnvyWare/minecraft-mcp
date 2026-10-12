# Film tools

Tools for making smooth in-game videos with a handful of calls. The camera moves, frame capture and encoding all run
inside the client, so a whole shot is one tool call instead of several round trips per frame.

- `mc.camera.play`: play keyframed camera shots and encode them to an mp4 at a fixed frame rate.
- `mc.camera.stop`: abort a running `mc.camera.play`.

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
    A camera that holds still on a static scene also counts.
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
