# Stdio bridge

`minecraft_mcp-bridge-<version>.jar` is a small launcher that speaks MCP over stdio and forwards to the in-game HTTP
endpoint. It is plain Java 21 with no Minecraft or third-party dependencies.

Why: MCP clients such as Claude Code connect to their servers once, at session start. The game client starts later and
is restarted after every code change, and its port and token change each time. The bridge is the long-lived server the
agent connects to. It can start, stop and inspect the client itself, and it lists the client's tools whenever the client
is up.

- MCP stdio transport: newline-delimited JSON-RPC 2.0 on stdin/stdout. Logging goes to stderr only.
- Handles `initialize` (echoes the client's `protocolVersion` if it is `2024-11-05`, `2025-03-26` or `2025-06-18`,
  otherwise answers `2025-06-18`), `ping`, `tools/list`, `tools/call` and notifications. Other methods get `-32601`.
- Declares `tools.listChanged` and sends `notifications/tools/list_changed` when the client comes up, goes away, or its
  tool set changes (checked every 3 seconds).
- `tools/list` returns the bridge's `client.*` tools plus the client's tools if it is reachable.
- Other `tools/call`s are forwarded to the client and its result is returned unchanged. A client-side JSON-RPC error
  becomes a tool result with `isError: true`. If the client is not reachable, the result is `isError: true` with a hint
  to call `client.launch`. Forwarded calls may take up to 15 minutes (condition waits, scenario runs); listing uses a
  3 second timeout.

## Build

```bash
./gradlew :bridge:jar          # bridge/build/libs/minecraft_mcp-bridge-<version>.jar
./gradlew :bridge:publishToMavenLocal
```

Maven coordinates: `com.envyware.minecraftmcp:minecraft_mcp-bridge:<version>-envy.1`.

## Options

| Option | Environment variable | Default | Meaning |
|---|---|---|---|
| `--game-dir <dir>` | `MINECRAFT_MCP_GAME_DIR` | required | Game directory: `mcp/server.json`, `logs/latest.log`, `mcp/launch.log`. For the NeoForge dev client: `neoforge/runs/client`. |
| `--launch-command <cmd>` | `MINECRAFT_MCP_LAUNCH_COMMAND` | none | Command that starts the client. Run through `cmd /c` on Windows and `sh -c` elsewhere. Without it, `client.launch` is unavailable. |
| `--launch-cwd <dir>` | `MINECRAFT_MCP_LAUNCH_CWD` | bridge's working directory | Working directory for the launch command. |
| `--port <n>` | `MINECRAFT_MCP_PORT` | read `server.json` | Fixed client endpoint port. |
| `--token <t>` | `MINECRAFT_MCP_AUTH_TOKEN` | read `server.json` | Fixed client auth token. |
| `--startup-timeout <s>` | `MINECRAFT_MCP_STARTUP_TIMEOUT` | `600` | How long `client.launch` waits for the endpoint. |
| `--keep-client` | `MINECRAFT_MCP_KEEP_CLIENT=true` | off | Leave a launched client running when the bridge exits. By default the bridge stops it. |

Relative paths are resolved against the bridge's working directory (for Claude Code, the project directory).
On Windows the bridge removes `NoDefaultCurrentDirectoryInExePath` from the launched command's environment (some agent
hosts set it), so `gradlew.bat` is found in `--launch-cwd` as in a normal terminal.

With `--port`/`--token` the bridge also passes `MINECRAFT_MCP_PORT`/`MINECRAFT_MCP_AUTH_TOKEN` to the launched process.
The mod reads system properties before environment variables, and `:neoforge:runClient` always sets
`-DminecraftMcp.port` (default `0`), so for a fixed port with Gradle add `-DminecraftMcp.port=<n>` to the launch command
as well. Usually you do not need a fixed endpoint: the bridge reads `server.json` on every request.

## Tools

| Tool | Arguments | What it does |
|---|---|---|
| `client.launch` | `wait` (default `true`), `timeoutSeconds` | Starts the launch command if no client is reachable. Deletes a stale `mcp/server.json` first and writes the process output to `mcp/launch.log` (overwritten each launch). With `wait`, blocks until the endpoint answers `tools/list`, the process exits (returns its exit code and the end of `launch.log`), or the timeout passes. Refuses if a client is already reachable. |
| `client.status` | | Launched process (pid, uptime, pids of the process tree, last exit code), whether the endpoint is reachable, its port and the number of client tools. |
| `client.stop` | `timeoutSeconds` (default `60`) | Asks the client to quit with `mc.client.stop`, waits for the launched process tree to exit, then kills the whole tree (launcher, Gradle and the game JVM) if it is still running. A client the bridge did not launch can only be stopped gracefully. |
| `client.logs` | `lines` (default `200`, max `5000`), `contains`, `source` (`game` or `launch`) | Tail of `logs/latest.log`, or of `mcp/launch.log` (compile errors, launcher output). `contains` filters lines before counting. |

`client.launch` returns once the endpoint answers. Resources may still be loading at that point: check
`mc.client.state` (`loading`) before joining a world; `mc.world.join` also waits for it.

## Claude Code

`.mcp.json` in your project:

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "java",
      "args": [
        "-jar", "path/to/minecraft_mcp-bridge.jar",
        "--game-dir", "runs/client",
        "--launch-command", "gradlew.bat :neoforge:runClient -DminecraftMcp.background=true"
      ]
    }
  }
}
```

On Linux and macOS use `./gradlew` in the launch command. Use [background mode](headless.md#background-mode-windowsmacoslinux-desktop)
so the client does not take over your screen (on NeoForge also set `earlyWindowControl = false`).

Typical loop: `client.launch` → `mc.*` tools → edit code → `client.stop` → `client.launch`. When the build fails,
`client.launch` reports the exit code and the end of `launch.log`; `client.logs` with `source: launch` shows more.

## Limitations

- Killing the process tree relies on the game JVM being a descendant of the launch command. That holds for
  `gradlew ... runClient` with `org.gradle.daemon=false` (this repository's default) and for launchers that start the
  game as a child. With a Gradle daemon the game is a child of the daemon instead, so only the graceful stop applies.
- If the bridge itself is killed hard (not by closing stdin), it cannot stop the client it launched.
- The bridge only proxies tools. Resources and prompts are not exposed.
