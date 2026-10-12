package io.izzel.minecraftmcp.film;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cheap quality checks on a take, from small luma thumbnails: black frames, frozen (repeated) frames, and how evenly
 * the picture changes from frame to frame. A smooth camera move changes the picture by a similar amount every frame;
 * a stutter shows up as a near-zero change followed by a double-sized one.
 */
public final class FrameAnalyzer {
    /** Mean absolute luma change (0-255) below which a frame counts as a repeat of the previous one. */
    public static final double FROZEN_THRESHOLD = 0.25;
    /** Mean luma (0-255) below which a frame counts as black. */
    public static final double BLACK_THRESHOLD = 8;
    static final double SPIKE_HIGH = 2.5;
    static final double SPIKE_LOW = 0.4;
    static final double SPIKE_MIN_MOTION = 1.0;

    private final List<Double> means = new ArrayList<>();
    private final List<Double> diffs = new ArrayList<>();
    private float[] previous;

    /** Adds the next frame's luma grid. {@code cut} marks the first frame of a new shot, which is not compared with the frame before it. */
    public void accept(float[] luma, boolean cut) {
        double sum = 0;
        for (float v : luma) sum += v;
        means.add(sum / luma.length);
        if (previous == null || cut || previous.length != luma.length) {
            diffs.add(Double.NaN);
        } else {
            double d = 0;
            for (int i = 0; i < luma.length; i++) d += Math.abs(luma[i] - previous[i]);
            diffs.add(d / luma.length);
        }
        previous = luma;
    }

    public Map<String, Object> report() {
        return summarize(means.stream().mapToDouble(Double::doubleValue).toArray(), diffs.stream().mapToDouble(Double::doubleValue).toArray());
    }

    /** Quality figures from per-frame mean luma and per-frame change ({@code NaN} where a frame has nothing to compare with). */
    public static Map<String, Object> summarize(double[] means, double[] diffs) {
        int frames = means.length;
        int black = 0;
        List<Integer> blackAt = new ArrayList<>();
        for (int i = 0; i < frames; i++) {
            if (means[i] < BLACK_THRESHOLD) { black++; if (blackAt.size() < 10) blackAt.add(i); }
        }
        int frozen = 0, run = 0, longestRun = 0, compared = 0;
        List<Integer> frozenAt = new ArrayList<>();
        double motionSum = 0;
        for (int i = 0; i < diffs.length; i++) {
            if (Double.isNaN(diffs[i])) { run = 0; continue; }
            compared++;
            motionSum += diffs[i];
            if (diffs[i] < FROZEN_THRESHOLD) {
                frozen++;
                run++;
                longestRun = Math.max(longestRun, run);
                if (frozenAt.size() < 10) frozenAt.add(i);
            } else {
                run = 0;
            }
        }
        double meanMotion = compared == 0 ? 0 : motionSum / compared;
        double jitterSum = 0;
        int pairs = 0;
        for (int i = 1; i < diffs.length; i++) {
            if (Double.isNaN(diffs[i]) || Double.isNaN(diffs[i - 1])) continue;
            jitterSum += Math.abs(diffs[i] - diffs[i - 1]);
            pairs++;
        }
        double jitter = pairs == 0 || meanMotion < FROZEN_THRESHOLD ? 0 : jitterSum / pairs / meanMotion;
        // a stutter is a frame whose change is far below or above both neighbours'; ease-in/out ramps are not
        int spikes = 0;
        List<Integer> spikesAt = new ArrayList<>();
        for (int i = 1; i + 1 < diffs.length; i++) {
            double before = diffs[i - 1], here = diffs[i], after = diffs[i + 1];
            if (Double.isNaN(before) || Double.isNaN(here) || Double.isNaN(after)) continue;
            if ((before + after) / 2 < SPIKE_MIN_MOTION) continue;
            if (here < SPIKE_LOW * Math.min(before, after) || here > SPIKE_HIGH * Math.max(before, after)) {
                spikes++;
                if (spikesAt.size() < 10) spikesAt.add(i);
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("frames", frames);
        report.put("blackFrames", black);
        if (!blackAt.isEmpty()) report.put("blackFrameIndexes", blackAt);
        report.put("frozenFrames", frozen);
        report.put("longestFrozenRun", longestRun);
        if (!frozenAt.isEmpty()) report.put("frozenFrameIndexes", frozenAt);
        report.put("meanMotion", CameraPose.round(meanMotion, 3));
        report.put("jitter", CameraPose.round(jitter, 3));
        report.put("motionSpikes", spikes);
        if (!spikesAt.isEmpty()) report.put("motionSpikeIndexes", spikesAt);
        List<String> warnings = new ArrayList<>();
        if (black > 0) warnings.add(black + " black frame(s)");
        if (frozen > 0) warnings.add(frozen + " frozen frame(s), longest run " + longestRun);
        if (spikes > Math.max(1, compared / 100)) warnings.add(spikes + " motion spike(s): uneven motion or pop-in");
        if (jitter > 0.5) warnings.add("high jitter " + CameraPose.round(jitter, 2));
        report.put("verdict", warnings.isEmpty() ? "ok" : "check");
        report.put("warnings", warnings);
        return report;
    }
}
