# Agent usage

1. Start the Minecraft client with this mod installed.
2. Read `<gameDir>/mcp/server.json`.
3. Connect to `http://host:port/path` with `Authorization: Bearer <authToken>`.
4. Use `tools/list` and `tools/call`.

The endpoint is localhost-only by default.

## Making videos

Use the [film tools](film-tools.md) instead of moving the player and taking screenshots frame by frame:

1. Write a scene file (see [examples/film/flythrough.json](../examples/film/flythrough.json)): the world, the setup,
   the shots and the output.
2. Call `mc.film.run` with `{"scene": "<path>", "draft": true}`: a 640x360 take in seconds. Read the `contactSheet`
   image and the `quality` block of the report, adjust the shots and run it again (the world is reused).
3. Run it without `draft` for the final take.

For live action that must play in real time, such as a battle, use `mc.record.start` and `mc.record.stop` instead.
