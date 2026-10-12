# Agent usage

1. Start the Minecraft client with this mod installed.
2. Read `<gameDir>/mcp/server.json`.
3. Connect to `http://host:port/path` with `Authorization: Bearer <authToken>`.
4. Use `tools/list` and `tools/call`.

The endpoint is localhost-only by default.

## Making videos

Use the [film tools](film-tools.md) instead of moving the player and taking screenshots frame by frame:

1. Join a world (`mc.world.join`), wait for `mc.client.state` to report `rawInWorld: true` and `loading: false`, and
   switch to spectator (`mc.command.run` with `/gamemode spectator`).
2. Call `mc.camera.play` with the shots. Try `"draft": true` first (640x360 at 10 fps): it takes seconds.
3. Read the `contactSheet` image and the `quality` block of the report; then run the final take without `draft`.
