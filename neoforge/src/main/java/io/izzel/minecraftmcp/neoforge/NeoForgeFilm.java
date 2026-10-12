package io.izzel.minecraftmcp.neoforge;

import io.izzel.minecraftmcp.entity.EntitySelector;
import io.izzel.minecraftmcp.film.CameraMath;
import io.izzel.minecraftmcp.film.CameraPose;
import io.izzel.minecraftmcp.film.Clearance;
import io.izzel.minecraftmcp.film.FfmpegSink;
import io.izzel.minecraftmcp.film.FilmCamera;
import io.izzel.minecraftmcp.film.FilmEncoder;
import io.izzel.minecraftmcp.film.FilmSettings;
import io.izzel.minecraftmcp.film.FrameSink;
import io.izzel.minecraftmcp.film.Frames;
import io.izzel.minecraftmcp.film.PngSequenceSink;
import io.izzel.minecraftmcp.film.Shot;
import io.izzel.minecraftmcp.film.ShotSampler;
import io.izzel.minecraftmcp.neoforge.mixin.LevelRendererAccessor;
import io.izzel.minecraftmcp.neoforge.mixin.SectionOcclusionGraphAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The in-client camera player behind {@code mc.camera.play}. Shots run on the render thread: before each frame the
 * camera is put at the pose for output frame {@code n} (time {@code n / fps}), after the frame the framebuffer is copied
 * to the encoder once the chunks and render sections around the camera are ready. A frame that is not ready is rendered
 * again at the same pose, so the output stays smooth however slowly the client renders or loads.
 *
 * <p>The client only receives chunks around the player, and Minecraft centres its render-section grid on the player, so
 * the (spectator) player is moved to the camera every frame. The tool call itself blocks on the calling thread.
 */
final class NeoForgeFilm {
    private static final NeoForgeFilm INSTANCE = new NeoForgeFilm();
    /** Minecraft treats a frame-rate limit of 260 as unlimited. */
    static final int UNCAPPED = 260;

    private final Minecraft mc = Minecraft.getInstance();
    private volatile Take take;
    private volatile Recording recording;
    private volatile Map<String, Object> finishedRecording;
    private ByteBuffer readback;

    private NeoForgeFilm() {}

    static NeoForgeFilm get() {
        return INSTANCE;
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Pre.class, event -> INSTANCE.preFrame(event.getPartialTick()));
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, event -> INSTANCE.postFrame());
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeFov.class, event -> {
            CameraPose pose = FilmCamera.pose();
            if (pose != null && !Float.isNaN(pose.fov())) event.setFOV(pose.fov());
        });
    }

    /** Options of {@code mc.camera.play} beyond the shots and the output. */
    record PlayOptions(boolean hideHud, boolean movePlayer, boolean freezeWorld, boolean resize, int preloadRadius, int readyRadius, long frameTimeoutMs, long preloadTimeoutMs) {
        static PlayOptions from(Map<String, Object> args) {
            return new PlayOptions(
                    bool(args.get("hideHud"), true),
                    bool(args.get("movePlayer"), true),
                    bool(args.get("freezeWorld"), false),
                    bool(args.get("resize"), true),
                    integer(args.get("preloadRadius"), 8),
                    integer(args.get("readyRadius"), 6),
                    integer(args.get("frameTimeoutMs"), 2000),
                    integer(args.get("preloadTimeoutMs"), 60000));
        }
    }

    /** Render-thread state of one {@code mc.camera.play} call. */
    private static final class Take {
        final PlayOptions options;
        final ClientSetup setup;
        volatile FilmEncoder encoder;
        volatile ShotRun shot;
        volatile boolean stopRequested;
        int maxFreeBuffers;

        Take(PlayOptions options, ClientSetup setup) {
            this.options = options;
            this.setup = setup;
        }
    }

    /** One shot being recorded. Render thread only, apart from {@link #done}. */
    private static final class ShotRun {
        final ShotSampler sampler;
        final Integer targetId;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        boolean cut;
        int frame;
        CameraPose pose;
        CameraPose previous;
        double[] lastTarget;
        long waitStart;
        boolean settled;
        int waitedFrames;
        int forcedFrames;
        long waitNanos;
        long startNanos;
        long endNanos;
        double maxSpeed;
        double maxTurnRate;

        ShotRun(ShotSampler sampler, Integer targetId, boolean cut, double[] target) {
            this.sampler = sampler;
            this.targetId = targetId;
            this.cut = cut;
            this.lastTarget = target;
        }
    }

    Map<String, Object> play(Map<String, Object> args) throws Exception {
        long started = System.nanoTime();
        List<Shot> shots = Shot.parseAll(args);
        PlayOptions options = PlayOptions.from(args);
        FilmSettings settings = FilmSettings.from(args, FMLPaths.GAMEDIR.get(), String.valueOf(args.getOrDefault("name", shots.get(0).name())));
        List<String> warnings = new ArrayList<>(settings.warnings());
        Take t = onRender(() -> begin(options, settings, warnings));
        try {
            int[] size = awaitRenderSize(settings, warnings);
            FrameSink sink = settings.format().equals("mp4")
                    ? new FfmpegSink(settings.ffmpeg(), settings.output(), size[0], size[1], settings.fps(), settings.width(), settings.height(), settings.crf(), settings.preset())
                    : new PngSequenceSink(settings.output(), size[0], size[1]);
            t.encoder = new FilmEncoder(sink, size[0], size[1], settings.fps(), settings.contactSheetFrames());
            List<Map<String, Object>> shotReports = new ArrayList<>();
            double preloadSeconds = 0, captureSeconds = 0;
            int frames = 0, waited = 0, forced = 0;
            for (int i = 0; i < shots.size(); i++) {
                Shot shot = shots.get(i);
                Map<String, Object> report = new LinkedHashMap<>(shot.describe());
                Integer targetId = shot.target() == null ? null : onRender(() -> resolveTarget(shot));
                double[] target = targetId == null ? null : onRender(() -> targetPosition(targetId, 1.0F, null));
                long preloadStart = System.nanoTime();
                int lift = preload(t, shot, new ShotSampler(shot, settings.fps(), 0), target, report, warnings);
                double preloadTook = seconds(System.nanoTime() - preloadStart);
                preloadSeconds += preloadTook;
                ShotSampler sampler = new ShotSampler(shot, settings.fps(), lift);
                ShotRun run = new ShotRun(sampler, targetId, i > 0, target);
                onRender(() -> { t.shot = run; return null; });
                long timeoutMs = sampler.frames() * (options.frameTimeoutMs() + 250L) + 120_000L;
                try {
                    run.done.get(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    t.stopRequested = true;
                    throw new IllegalStateException(shot.name() + ": timed out after " + timeoutMs + " ms at frame " + run.frame + "/" + sampler.frames());
                } catch (ExecutionException e) {
                    throw unwrap(e);
                }
                double took = seconds(run.endNanos - run.startNanos);
                captureSeconds += took;
                frames += sampler.frames();
                waited += run.waitedFrames;
                forced += run.forcedFrames;
                report.put("frames", sampler.frames());
                report.put("lift", lift);
                report.put("preloadSeconds", round(preloadTook));
                report.put("captureSeconds", round(took));
                report.put("captureFps", took > 0 ? round(sampler.frames() / took) : null);
                report.put("waitedFrames", run.waitedFrames);
                report.put("waitSeconds", round(seconds(run.waitNanos)));
                report.put("forcedFrames", run.forcedFrames);
                report.put("maxSpeed", round(run.maxSpeed));
                report.put("maxTurnRate", round(run.maxTurnRate));
                shotReports.add(report);
                if (run.forcedFrames > 0) warnings.add(shot.name() + ": " + run.forcedFrames + " frame(s) captured before the world around the camera was ready (frameTimeoutMs " + options.frameTimeoutMs() + ")");
            }
            long encodeStart = System.nanoTime();
            Map<String, Object> encoded = t.encoder.finish(settings.contactSheetPath());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.putAll(encoded);
            result.put("renderSize", Map.of("width", size[0], "height", size[1]));
            result.put("draft", settings.draft());
            Map<String, Object> timing = new LinkedHashMap<>();
            timing.put("wallSeconds", round(seconds(System.nanoTime() - started)));
            timing.put("preloadSeconds", round(preloadSeconds));
            timing.put("captureSeconds", round(captureSeconds));
            timing.put("captureFps", captureSeconds > 0 ? round(frames / captureSeconds) : null);
            timing.put("encodeTailSeconds", round(seconds(System.nanoTime() - encodeStart)));
            timing.put("waitedFrames", waited);
            timing.put("forcedFrames", forced);
            result.put("timing", timing);
            result.put("shots", shotReports);
            result.put("warnings", warnings);
            return result;
        } catch (Throwable e) {
            FilmEncoder encoder = t.encoder;
            if (encoder != null) encoder.abort();
            throw e;
        } finally {
            onRender(() -> { end(t); return null; });
        }
    }

    Map<String, Object> stop() {
        Take t = take;
        if (t == null) return Map.of("status", "idle");
        t.stopRequested = true;
        return Map.of("status", "stopping");
    }

    private Take begin(PlayOptions options, FilmSettings settings, List<String> warnings) {
        if (mc.level == null || mc.player == null) throw new IllegalStateException("not in a world; join one with mc.world.join first");
        if (take != null || recording != null || ClientSetup.active()) throw new IllegalStateException("a camera shot or recording is already running");
        if (mc.screen != null) {
            warnings.add("closed screen " + mc.screen.getClass().getName());
            mc.setScreen(null);
        }
        if (options.movePlayer() && mc.gameMode != null && mc.gameMode.getPlayerMode() != GameType.SPECTATOR) {
            warnings.add("the player is in " + mc.gameMode.getPlayerMode().getName() + " mode; use spectator so moving it along with the camera cannot hurt it or be blocked");
        }
        ClientSetup setup = ClientSetup.apply(mc, options.hideHud(), options.resize() ? settings.width() : 0, options.resize() ? settings.height() : 0, options.freezeWorld(), UNCAPPED);
        Take t = new Take(options, setup);
        SectionRenderDispatcher dispatcher = mc.levelRenderer.getSectionRenderDispatcher();
        t.maxFreeBuffers = dispatcher == null ? 0 : dispatcher.getFreeBufferCount();
        take = t;
        return t;
    }

    private void end(Take t) {
        if (take == t) take = null;
        FilmCamera.clear();
        t.setup.restore(mc);
    }

    private int[] awaitRenderSize(FilmSettings settings, List<String> warnings) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        int[] size;
        while (true) {
            size = onRender(() -> new int[] {mc.getMainRenderTarget().width, mc.getMainRenderTarget().height});
            if (settings.width() == 0 || (size[0] == settings.width() && size[1] == settings.height())) return size;
            if (System.currentTimeMillis() > deadline) break;
            Thread.sleep(50);
        }
        warnings.add("rendering at " + size[0] + "x" + size[1] + " (the window could not be resized to " + settings.width() + "x" + settings.height() + "); frames are scaled");
        return size;
    }

    // ---- preload and clearance (calling thread) ----

    private int preload(Take t, Shot shot, ShotSampler sampler, double[] target, Map<String, Object> report, List<String> warnings) throws Exception {
        if (!shot.preload() && shot.clearance() == 0) return 0;
        List<double[]> fine = sampler.cameraSamples(200, target);
        double length = 0;
        for (int i = 1; i < fine.size(); i++) length += CameraMath.distance(fine.get(i - 1), fine.get(i));
        int readyRadius = onRender(() -> effectiveRadius(t.options.readyRadius()));
        int preloadRadius = onRender(() -> effectiveRadius(t.options.preloadRadius()));
        List<double[]> stops = stops(fine, Math.max(16, readyRadius * 16));
        List<double[]> clearance = shot.clearance() > 0 ? sampler.cameraSamples((int) CameraMath.clamp(length / 2, 40, 400), target) : List.of();
        boolean[] checked = new boolean[clearance.size()];
        int maxRise = 0, timeouts = 0;
        for (int i = 0; i < stops.size(); i++) {
            if (t.stopRequested) throw new CancellationException("stopped by mc.camera.stop");
            double[] stop = stops.get(i);
            double[] next = stops.get(Math.min(i + 1, stops.size() - 1));
            double yaw = i + 1 < stops.size() ? CameraMath.anglesOf(stop, next)[0] : 0;
            CameraPose pose = new CameraPose(stop[0], stop[1], stop[2], (float) yaw, 0, 0, Float.NaN);
            onRender(() -> { FilmCamera.set(pose); return null; });
            long deadline = System.currentTimeMillis() + t.options.preloadTimeoutMs();
            while (!onRender(() -> chunksLoaded(stop, preloadRadius) && renderIdle(t))) {
                if (t.stopRequested) throw new CancellationException("stopped by mc.camera.stop");
                if (System.currentTimeMillis() > deadline) { timeouts++; break; }
                Thread.sleep(50);
            }
            if (!clearance.isEmpty()) {
                int rise = onRender(() -> checkClearance(clearance, checked, shot.clearance()));
                maxRise = Math.max(maxRise, rise);
            }
        }
        int unchecked = 0;
        for (boolean c : checked) if (!c) unchecked++;
        if (unchecked > 0) warnings.add(shot.name() + ": " + unchecked + " clearance sample(s) were in chunks that never loaded");
        if (timeouts > 0) warnings.add(shot.name() + ": " + timeouts + " preload stop(s) timed out after " + t.options.preloadTimeoutMs() + " ms");
        report.put("pathLength", round(length));
        report.put("preloadStops", stops.size());
        if (timeouts > 0) report.put("preloadTimeouts", timeouts);
        return Clearance.finish(maxRise);
    }

    /** The first sample, then one every {@code spacing} blocks along the path, then the last. */
    static List<double[]> stops(List<double[]> samples, double spacing) {
        List<double[]> stops = new ArrayList<>();
        stops.add(samples.get(0));
        double travelled = 0;
        for (int i = 1; i < samples.size(); i++) {
            travelled += CameraMath.distance(samples.get(i - 1), samples.get(i));
            if (travelled >= spacing || i == samples.size() - 1) {
                if (i == samples.size() - 1 && travelled < spacing / 4 && stops.size() > 1) stops.remove(stops.size() - 1);
                stops.add(samples.get(i));
                travelled = 0;
            }
        }
        return stops;
    }

    private int checkClearance(List<double[]> samples, boolean[] checked, int clearBlocks) {
        ClientLevel level = mc.level;
        if (level == null) return 0;
        int maxRise = 0;
        for (int i = 0; i < samples.size(); i++) {
            if (checked[i]) continue;
            double[] c = samples.get(i);
            if (!level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(c[0]), SectionPos.blockToSectionCoord(c[2]))) continue;
            checked[i] = true;
            maxRise = Math.max(maxRise, Clearance.rise(c, clearBlocks, (x, y, z) -> level.getBlockState(new BlockPos(x, y, z)).isAir()));
        }
        return maxRise;
    }

    // ---- render thread ----

    private void preFrame(DeltaTracker delta) {
        Take t = take;
        if (t == null) return;
        ShotRun s = t.shot;
        if (s != null && s.pose == null && !s.done.isDone()) {
            try {
                double[] target = s.targetId == null ? null : targetPosition(s.targetId, delta.getGameTimeDeltaPartialTick(true), s);
                s.pose = s.sampler.sample(s.frame, target);
                track(s);
                FilmCamera.set(s.pose);
            } catch (RuntimeException e) {
                t.shot = null;
                s.done.completeExceptionally(e);
            }
        }
        CameraPose pose = FilmCamera.pose();
        if (pose != null && t.options.movePlayer()) movePlayer(pose);
    }

    private void postFrame() {
        Recording r = recording;
        if (r != null) recordFrame(r);
        Take t = take;
        if (t == null) return;
        ShotRun s = t.shot;
        if (s == null || s.pose == null || s.done.isDone()) return;
        if (t.stopRequested) {
            t.shot = null;
            s.done.completeExceptionally(new CancellationException("stopped by mc.camera.stop"));
            return;
        }
        long now = System.nanoTime();
        if (!ready(t, s.pose)) {
            if (s.waitStart == 0) s.waitStart = now;
            s.settled = false;
            if (now - s.waitStart < t.options.frameTimeoutMs() * 1_000_000L) return;
            s.forcedFrames++;
        } else if (s.waitStart != 0 && !s.settled) {
            // after waiting, render once more at the same pose so sections uploaded this frame are in the picture
            s.settled = true;
            return;
        }
        if (s.waitStart != 0) {
            s.waitedFrames++;
            s.waitNanos += now - s.waitStart;
            s.waitStart = 0;
            s.settled = false;
        }
        if (s.frame == 0) s.startNanos = now;
        try {
            capture(t.encoder, s.cut);
        } catch (Exception e) {
            t.shot = null;
            s.done.completeExceptionally(e);
            return;
        }
        s.cut = false;
        s.frame++;
        s.previous = s.pose;
        s.pose = null;
        if (s.frame >= s.sampler.frames()) {
            s.endNanos = System.nanoTime();
            t.shot = null;
            s.done.complete(null);
        }
    }

    private void track(ShotRun s) {
        if (s.previous == null) return;
        int fps = s.sampler.fps();
        double speed = CameraMath.distance(s.previous.position(), s.pose.position()) * fps;
        double turn = Math.hypot(CameraMath.wrapDegrees(s.pose.yaw() - s.previous.yaw()), s.pose.pitch() - s.previous.pitch()) * fps;
        s.maxSpeed = Math.max(s.maxSpeed, speed);
        s.maxTurnRate = Math.max(s.maxTurnRate, turn);
    }

    private boolean ready(Take t, CameraPose pose) {
        return chunksLoaded(pose.position(), effectiveRadius(t.options.readyRadius())) && renderIdle(t);
    }

    /** A radius in chunks no larger than what the server sends around the player. */
    private int effectiveRadius(int wanted) {
        return Math.max(1, Math.min(wanted, mc.options.getEffectiveRenderDistance() - 1));
    }

    private boolean chunksLoaded(double[] position, int radius) {
        ClientLevel level = mc.level;
        if (level == null) return false;
        int cx = SectionPos.blockToSectionCoord(position[0]), cz = SectionPos.blockToSectionCoord(position[2]);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                if (!level.getChunkSource().hasChunk(cx + dx, cz + dz)) return false;
            }
        }
        return true;
    }

    /** No section compiles queued, running or waiting to upload, and no visibility update pending. */
    private boolean renderIdle(Take t) {
        SectionRenderDispatcher dispatcher = mc.levelRenderer.getSectionRenderDispatcher();
        if (dispatcher == null) return true;
        int free = dispatcher.getFreeBufferCount();
        t.maxFreeBuffers = Math.max(t.maxFreeBuffers, free);
        if (dispatcher.getToBatchCount() > 0 || dispatcher.getToUpload() > 0 || free < t.maxFreeBuffers) return false;
        SectionOcclusionGraphAccessor graph = (SectionOcclusionGraphAccessor) ((LevelRendererAccessor) mc.levelRenderer).minecraftMcp$sectionOcclusionGraph();
        Future<?> update = graph.minecraftMcp$fullUpdateTask();
        return !graph.minecraftMcp$needsFullUpdate() && (update == null || update.isDone());
    }

    private void movePlayer(CameraPose pose) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        double y = pose.y() - player.getEyeHeight();
        player.setPos(pose.x(), y, pose.z());
        player.xo = player.xOld = pose.x();
        player.yo = player.yOld = y;
        player.zo = player.zOld = pose.z();
        player.setDeltaMovement(Vec3.ZERO);
    }

    private Integer resolveTarget(Shot shot) {
        if (mc.level == null || mc.player == null) throw new IllegalStateException("not in a world");
        EntitySelector.Query query = EntitySelector.Query.from(shot.target());
        Entity entity = NeoForgeEntities.resolve(mc, query).orElseThrow(() -> new IllegalArgumentException(shot.name() + ": target entity not found: " + query.describe()));
        return entity.getId();
    }

    /** The centre of the target entity's bounding box, or its last known one if it has gone. */
    private double[] targetPosition(int id, float partialTick, ShotRun s) {
        Entity entity = mc.level == null ? null : mc.level.getEntity(id);
        if (entity == null) {
            if (s != null && s.lastTarget != null) return s.lastTarget;
            throw new IllegalStateException("target entity " + id + " is gone");
        }
        Vec3 p = entity.getPosition(partialTick);
        double[] center = {p.x, p.y + entity.getBbHeight() / 2.0, p.z};
        if (s != null) s.lastTarget = center;
        return center;
    }

    /** Copies the main framebuffer (bottom row first, RGBA) into a pooled encoder buffer and queues it. */
    void capture(FilmEncoder encoder, boolean cut) throws Exception {
        RenderTarget target = mc.getMainRenderTarget();
        int width = target.width, height = target.height, bytes = width * height * 4;
        if (readback == null || readback.capacity() < bytes) {
            if (readback != null) MemoryUtil.memFree(readback);
            readback = MemoryUtil.memAlloc(bytes);
        }
        readback.clear();
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readback);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousAlignment);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
        byte[] buffer = encoder.buffer();
        if (width == encoder.width() && height == encoder.height()) {
            readback.get(0, buffer, 0, bytes);
        } else {
            byte[] frame = new byte[bytes];
            readback.get(0, frame, 0, bytes);
            Frames.resize(frame, width, height, encoder.width(), encoder.height(), buffer);
        }
        encoder.submit(buffer, cut);
    }

    // ---- real-time recording ----

    /**
     * A real-time recording: output frame {@code k} is whatever was rendered last at wall-clock time {@code k / fps}.
     * When rendering falls behind, the previous frame is repeated so the video keeps real-time pace.
     */
    private static final class Recording {
        final FilmSettings settings;
        final ClientSetup setup;
        final FilmEncoder encoder;
        final int[] size;
        final long maxNanos;
        final List<String> warnings;
        long startNanos;
        long written;
        long captured;
        long duplicated;
        long rendered;

        Recording(FilmSettings settings, ClientSetup setup, FilmEncoder encoder, int[] size, double maxSeconds, List<String> warnings) {
            this.settings = settings;
            this.setup = setup;
            this.encoder = encoder;
            this.size = size;
            this.maxNanos = (long) (maxSeconds * 1e9);
            this.warnings = warnings;
        }

        double seconds() {
            return startNanos == 0 ? 0 : (System.nanoTime() - startNanos) / 1e9;
        }
    }

    Map<String, Object> recordStart(Map<String, Object> args) throws Exception {
        FilmSettings settings = FilmSettings.from(args, FMLPaths.GAMEDIR.get(), String.valueOf(args.getOrDefault("name", "recording")));
        boolean hideHud = bool(args.get("hideHud"), true);
        boolean resize = bool(args.get("resize"), true);
        double maxSeconds = args.get("maxSeconds") instanceof Number n ? n.doubleValue() : 600;
        List<String> warnings = new ArrayList<>(settings.warnings());
        ClientSetup setup = onRender(() -> {
            if (take != null || recording != null || ClientSetup.active()) throw new IllegalStateException("a camera shot or recording is already running");
            // render at least twice the output rate so every output frame is a fresh one
            return ClientSetup.apply(mc, hideHud, resize ? settings.width() : 0, resize ? settings.height() : 0, false, Math.min(UNCAPPED, Math.max(60, settings.fps() * 2)));
        });
        try {
            int[] size = awaitRenderSize(settings, warnings);
            FrameSink sink = settings.format().equals("mp4")
                    ? new FfmpegSink(settings.ffmpeg(), settings.output(), size[0], size[1], settings.fps(), settings.width(), settings.height(), settings.crf(), settings.preset())
                    : new PngSequenceSink(settings.output(), size[0], size[1]);
            FilmEncoder encoder = new FilmEncoder(sink, size[0], size[1], settings.fps(), settings.contactSheetFrames());
            Recording r = new Recording(settings, setup, encoder, size, maxSeconds, warnings);
            onRender(() -> { recording = r; finishedRecording = null; return null; });
        } catch (Exception e) {
            onRender(() -> { setup.restore(mc); return null; });
            throw e;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "recording");
        result.put("output", settings.output().toAbsolutePath().toString());
        result.put("format", settings.format());
        result.put("fps", settings.fps());
        result.put("maxSeconds", maxSeconds);
        result.put("warnings", warnings);
        return result;
    }

    Map<String, Object> recordStop() throws Exception {
        Recording r = onRender(() -> {
            Recording current = recording;
            recording = null;
            return current;
        });
        if (r == null) {
            Map<String, Object> finished = finishedRecording;
            if (finished != null) return finished;
            return Map.of("status", "idle");
        }
        return finishRecording(r, "stopped");
    }

    Map<String, Object> recordState() {
        Recording r = recording;
        if (r == null) return Map.of("recording", false);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("recording", true);
        state.put("output", r.settings.output().toAbsolutePath().toString());
        state.put("seconds", round(r.seconds()));
        state.put("frames", r.written);
        state.put("duplicatedFrames", r.duplicated);
        return state;
    }

    private Map<String, Object> finishRecording(Recording r, String status) throws Exception {
        double seconds = r.seconds();
        try {
            Map<String, Object> encoded = r.encoder.finish(r.settings.contactSheetPath());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status);
            result.putAll(encoded);
            result.put("renderSize", Map.of("width", r.size[0], "height", r.size[1]));
            Map<String, Object> timing = new LinkedHashMap<>();
            timing.put("wallSeconds", round(seconds));
            timing.put("renderedFps", seconds > 0 ? round(r.rendered / seconds) : null);
            timing.put("capturedFrames", r.captured);
            timing.put("duplicatedFrames", r.duplicated);
            result.put("timing", timing);
            if (r.duplicated > r.written / 20) r.warnings.add(r.duplicated + " of " + r.written + " frames repeat the previous one because rendering was slower than " + r.settings.fps() + " fps");
            result.put("warnings", r.warnings);
            return result;
        } catch (Exception e) {
            r.encoder.abort();
            throw e;
        } finally {
            onRender(() -> { r.setup.restore(mc); return null; });
        }
    }

    private void recordFrame(Recording r) {
        long now = System.nanoTime();
        if (r.startNanos == 0) r.startNanos = now;
        r.rendered++;
        long elapsed = now - r.startNanos;
        long due = elapsed * r.settings.fps() / 1_000_000_000L + 1;
        try {
            if (r.written < due) {
                capture(r.encoder, false);
                r.written++;
                r.captured++;
                while (r.written < due) {
                    r.encoder.duplicate();
                    r.written++;
                    r.duplicated++;
                }
            }
        } catch (Exception e) {
            recording = null;
            r.warnings.add("recording stopped: " + e.getMessage());
            finishInBackground(r, "failed");
            return;
        }
        if (elapsed >= r.maxNanos) {
            recording = null;
            r.warnings.add("stopped after maxSeconds " + r.maxNanos / 1e9);
            finishInBackground(r, "stopped_at_max_seconds");
        }
    }

    /** Finishes an automatically stopped recording off the render thread; {@code mc.record.stop} then returns its report. */
    private void finishInBackground(Recording r, String status) {
        Thread thread = new Thread(() -> {
            try {
                finishedRecording = finishRecording(r, status);
            } catch (Exception e) {
                finishedRecording = Map.of("status", "failed", "error", String.valueOf(e.getMessage()), "warnings", r.warnings);
            }
        }, "minecraft-mcp-record-finish");
        thread.setDaemon(true);
        thread.start();
    }

    // ---- helpers ----

    private <T> T onRender(Supplier<T> supplier) throws Exception {
        try {
            return mc.submit(supplier).get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    private static Exception unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception exception) return exception;
        return e;
    }

    private static double seconds(long nanos) {
        return nanos / 1e9;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    static boolean bool(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    static int integer(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(value).trim());
    }

    /**
     * Client settings changed for the duration of a take and restored afterwards: HUD hidden, frame-rate cap and vsync
     * off so capture runs as fast as the GPU allows, optionally a window size for the output resolution and a frozen world.
     */
    static final class ClientSetup {
        private static volatile ClientSetup current;

        private final boolean hideGui;
        private final int framerateLimit;
        private final boolean resized;
        private final int windowWidth;
        private final int windowHeight;
        private final boolean froze;

        private ClientSetup(boolean hideGui, int framerateLimit, boolean resized, int windowWidth, int windowHeight, boolean froze) {
            this.hideGui = hideGui;
            this.framerateLimit = framerateLimit;
            this.resized = resized;
            this.windowWidth = windowWidth;
            this.windowHeight = windowHeight;
            this.froze = froze;
        }

        static boolean active() {
            return current != null;
        }

        static ClientSetup apply(Minecraft mc, boolean hideHud, int width, int height, boolean freezeWorld, int framerateLimit) {
            var window = mc.getWindow();
            boolean resize = width > 0 && height > 0 && (window.getWidth() != width || window.getHeight() != height);
            ClientSetup setup = new ClientSetup(mc.options.hideGui, window.getFramerateLimit(), resize, window.getScreenWidth(), window.getScreenHeight(), freezeWorld);
            if (hideHud) mc.options.hideGui = true;
            mc.getToasts().clear();
            window.setFramerateLimit(framerateLimit);
            window.updateVsync(false);
            if (resize) GLFW.glfwSetWindowSize(window.getWindow(), width, height);
            if (freezeWorld && mc.player != null) mc.player.connection.sendCommand("tick freeze");
            current = setup;
            return setup;
        }

        void restore(Minecraft mc) {
            var window = mc.getWindow();
            mc.options.hideGui = hideGui;
            window.setFramerateLimit(framerateLimit);
            window.updateVsync(mc.options.enableVsync().get());
            if (resized) GLFW.glfwSetWindowSize(window.getWindow(), windowWidth, windowHeight);
            if (froze && mc.player != null) mc.player.connection.sendCommand("tick unfreeze");
            if (current == this) current = null;
        }
    }
}
