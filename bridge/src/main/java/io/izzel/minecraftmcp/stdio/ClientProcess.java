package io.izzel.minecraftmcp.stdio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** The client process started by {@code client.launch}: the shell, the launcher it runs (e.g. Gradle) and the game JVM below it. */
public final class ClientProcess {
    private final String command; private final Path cwd; private final Path log; private final Map<String, String> extraEnv;
    private final Map<Long, ProcessHandle> remembered = new LinkedHashMap<>();
    private Process process; private Instant startedAt; private Integer lastExitCode; private Instant lastExitAt;

    public ClientProcess(String command, Path cwd, Path log, Map<String, String> extraEnv) { this.command = command; this.cwd = cwd; this.log = log; this.extraEnv = extraEnv; }

    public static List<String> shell(String command) {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? List.of("cmd", "/c", command) : List.of("sh", "-c", command);
    }

    public boolean configured() { return command != null && !command.isBlank(); }

    public synchronized long start() throws IOException {
        if (!configured()) throw new IllegalStateException("No launch command configured; start the bridge with --launch-command or MINECRAFT_MCP_LAUNCH_COMMAND");
        if (alive()) throw new IllegalStateException("The launched client is still running (pid " + process.pid() + ")");
        Files.createDirectories(log.getParent());
        ProcessBuilder builder = new ProcessBuilder(shell(command)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.to(log.toFile()));
        if (cwd != null) builder.directory(cwd.toFile());
        // some hosts (e.g. agent sandboxes) set this, which stops cmd from finding `gradlew.bat` in the launch directory
        builder.environment().remove("NoDefaultCurrentDirectoryInExePath");
        builder.environment().putAll(extraEnv);
        Process started = builder.start();
        started.getOutputStream().close();
        process = started; startedAt = Instant.now(); lastExitCode = null; lastExitAt = null; remembered.clear();
        started.onExit().thenAccept(p -> { synchronized (this) { if (process == p) { lastExitCode = p.exitValue(); lastExitAt = Instant.now(); } } });
        return started.pid();
    }

    /** Whether the launched process tree is still running (the root, or anything it spawned that outlived it). */
    public synchronized boolean alive() { return process != null && !tree().isEmpty(); }

    public synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("launchCommand", command);
        m.put("launched", process != null);
        if (process == null) return m;
        List<ProcessHandle> tree = tree();
        m.put("alive", !tree.isEmpty());
        m.put("pid", process.pid());
        m.put("startedAt", startedAt.toString());
        if (!tree.isEmpty()) m.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds());
        m.put("processes", tree.stream().map(ProcessHandle::pid).toList());
        if (lastExitAt != null) { m.put("lastExitCode", lastExitCode); m.put("lastExitAt", lastExitAt.toString()); }
        return m;
    }

    /** Waits until the whole launched tree has exited. */
    public boolean waitForExit(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (alive()) {
            if (System.nanoTime() > deadline) return false;
            Thread.sleep(250);
        }
        return true;
    }

    /** Forcibly kills the launched process and all of its descendants; returns the pids that were killed. */
    public synchronized List<Long> destroyTree() {
        List<ProcessHandle> tree = tree();
        // children first, so a launcher such as Gradle cannot respawn or outlive its game JVM
        Collections.reverse(tree);
        List<Long> killed = new ArrayList<>();
        for (ProcessHandle handle : tree) if (handle.destroyForcibly()) killed.add(handle.pid());
        return killed;
    }

    /** Live processes of the launched tree: the root (if alive) plus its descendants, also those remembered after the root exited. */
    private List<ProcessHandle> tree() {
        if (process == null) return List.of();
        Map<Long, ProcessHandle> handles = new LinkedHashMap<>();
        ProcessHandle root = process.toHandle();
        handles.put(root.pid(), root);
        root.descendants().forEach(h -> handles.put(h.pid(), h));
        // an orphaned child is no longer reported by root.descendants() once the root exits, so keep following what we saw
        for (ProcessHandle known : List.copyOf(remembered.values())) { handles.putIfAbsent(known.pid(), known); known.descendants().forEach(h -> handles.putIfAbsent(h.pid(), h)); }
        handles.values().removeIf(h -> !h.isAlive());
        remembered.clear(); remembered.putAll(handles);
        return new ArrayList<>(handles.values());
    }
}
