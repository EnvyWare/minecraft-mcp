package io.izzel.minecraftmcp.screen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WidgetPathTest {
    @Test
    void topLevelIdsKeepHistoricalFormat() {
        assertEquals("widget-3", WidgetPath.id(List.of(3)));
        assertEquals(List.of(3), WidgetPath.parse("widget-3"));
    }

    @Test
    void nestedIdsRoundTrip() {
        assertEquals("widget-3.0.12", WidgetPath.id(List.of(3, 0, 12)));
        assertEquals(List.of(3, 0, 12), WidgetPath.parse("widget-3.0.12"));
    }

    @Test
    void invalidIdsParseToEmptyPath() {
        assertEquals(List.of(), WidgetPath.parse(null));
        assertEquals(List.of(), WidgetPath.parse("widget-"));
        assertEquals(List.of(), WidgetPath.parse("button-1"));
        assertEquals(List.of(), WidgetPath.parse("widget-1.x"));
        assertEquals(List.of(), WidgetPath.parse("widget-1..2"));
        assertEquals(List.of(), WidgetPath.parse("widget--1"));
    }
}
