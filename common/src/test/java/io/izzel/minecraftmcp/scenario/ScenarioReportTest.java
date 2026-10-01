package io.izzel.minecraftmcp.scenario;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScenarioReportTest {
    @Test
    void summaryLinesListEveryScenarioAndTotals() {
        ScenarioReport report = new ScenarioReport(Instant.EPOCH);
        report.add("ok", "passed", List.of(), null);
        report.add("broken", "failed", List.of(), "Expectation failed at result.x");
        report.finish();

        assertEquals(List.of(
                "passed            ok",
                "failed            broken - Expectation failed at result.x",
                "passed=1 failed=1 skipped=0 expectedFailed=0 unexpectedPassed=0"
        ), report.summaryLines());
    }
}
