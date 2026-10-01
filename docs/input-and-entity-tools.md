# Input and entity tools

Screen, keyboard and entity tools that drive the client the way a player would. NeoForge implements all of them;
Fabric reports them as unsupported.

- `mc.screen.click.at` / `mc.screen.widget.click` (press and release)
- `mc.screen.mouse.drag`
- `mc.screen.scroll`
- `mc.screen.state` (nested widgets)
- `mc.keyboard.press` (modifiers, release, keyboard handler route)
- `mc.entity.list`
- `mc.player.look_at_entity`
- `mc.entity.interact`

## Screen clicks

`mc.screen.click.at` and `mc.screen.widget.click` send `mouseClicked` and then `mouseReleased`, like a real click.
The release goes to whatever screen is open afterwards, as with real input. Results gain a `released` field.

`mc.screen.widget.click` also accepts nested ids from `mc.screen.state` (`widget-3.1`). When matching by `message`,
top-level widgets are checked first (the previous behaviour), then nested widgets and list rows. A list row that is
scrolled out of view returns `"status": "out_of_view"` instead of clicking.

## `mc.screen.mouse.drag`

```json
{"fromX": 10, "fromY": 20, "toX": 120, "toY": 20, "button": 0, "steps": 5}
```

Presses at `from`, sends `steps` `mouseDragged` events along a straight line, then releases at `to`. Returns
`status: "dragged"`, `pressed`, `handledMoves`, `released`, the coordinates and the screen class.

## `mc.screen.scroll`

```json
{"x": 213, "y": 120, "amount": -1, "horizontal": 0}
```

Positive `amount` scrolls up, like a mouse wheel. Returns `status: "scrolled"` and whether the screen handled it.

## `mc.screen.state`

Each entry in `children` has `index`, `id`, `class` and, when known, `x`, `y`, `width`, `height` and `message`.
Widgets also report `active` and `visible`. Top-level children keep their `widget-N` ids.

Containers (panels, tab layouts, lists, anything implementing `ContainerEventHandler`) have their own `children`, with ids
such as `widget-3.0`. List rows report their row bounds and `inView`, and `message` is the row's narration (for
example the world name in the world list).

## `mc.keyboard.press`

```json
{"key": "Q", "modifiers": ["F3"], "route": "keyboard_handler"}
```

- `modifiers`: `shift`, `ctrl`, `alt`, `super`, or any other key name to hold during the press (`F3` for debug
  combos). Also accepted as a string: `"ctrl+shift"`.
- `route`:
  - `default` (the previous behaviour): sends `keyPressed` then `keyReleased` to the open screen, or triggers key mappings in-world.
  - `keyboard_handler`: sends the press and release events through Minecraft's `KeyboardHandler.keyPress`,
    exactly like real keyboard input. Global keys (F1, F2, F3 combos, Escape to pause), mod key bindings and NeoForge
    `InputEvent.Key` listeners all fire.

While a press is in progress, held keys are reported as down by `InputConstants.isKeyDown`, so polled checks such as
`Screen.hasShiftDown()` (shift-clicks) and F3 combos behave as if the keys were held. This is virtual: the OS keyboard is
never touched.

Without `modifiers` or `route` the result is the same as before (`status`, `key`). Otherwise it also includes `modifiers`,
`route` and, for the screen route, `handled` and `screen`.

## `mc.entity.list`

```json
{"type": "pig", "radius": 16, "limit": 10, "includeSelf": false}
```

Lists entities known to the client, nearest first. `type` accepts `pig` or `minecraft:pig` (or any modded id).
`radius` defaults to 32 (`0` for any distance) and `limit` to 100. Each entity has `id`, `uuid`, `type`, `name`,
`customName`, `position`, `distance`, `alive` and `isPlayer`.

## `mc.player.look_at_entity`

```json
{"type": "pig", "radius": 16}
```

Selects an entity by `id`, by `uuid`, or the nearest of `type` within `radius` (checked in that order) and rotates
the player towards the centre of its bounding box. Returns `status: "looked_at_entity"`, the entity, `yaw` and `pitch`,
or `status: "not_found"`.

## `mc.entity.interact`

```json
{"type": "villager", "action": "use", "hand": "main", "look": true}
```

Selects the entity like `mc.player.look_at_entity` and, unless `look` is false, looks at it first.

- `use` follows vanilla right-click: `interactAt` with an `EntityHitResult` where the view ray meets the entity's
  bounding box, then `interact` if that did not consume the action, and swings when appropriate.
- `attack` calls `gameMode.attack` and swings.

Returns `status: "interacted"` (with `result`, `consumesAction`, `hand`) or `"attacked"`, plus `inRange` (whether a
real player could reach it; the request is sent either way and the server decides) and the entity.
