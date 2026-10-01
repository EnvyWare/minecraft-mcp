package io.izzel.minecraftmcp.entity;

import io.izzel.minecraftmcp.entity.EntitySelector.Candidate;
import io.izzel.minecraftmcp.entity.EntitySelector.Query;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EntitySelectorTest {
    private static final Candidate SELF = new Candidate(1, "00000000-0000-0000-0000-000000000001", "minecraft:player", 0.0, true);
    private static final Candidate FAR_PIG = new Candidate(2, "00000000-0000-0000-0000-000000000002", "minecraft:pig", 20.0, false);
    private static final Candidate NEAR_PIG = new Candidate(3, "00000000-0000-0000-0000-000000000003", "minecraft:pig", 3.0, false);
    private static final Candidate COW = new Candidate(4, "00000000-0000-0000-0000-000000000004", "minecraft:cow", 5.0, false);
    private static final Candidate MOD_MOB = new Candidate(5, "00000000-0000-0000-0000-000000000005", "pixelmon:pixelmon", 8.0, false);
    private static final List<Candidate> ALL = List.of(SELF, FAR_PIG, NEAR_PIG, COW, MOD_MOB);

    @Test
    void filterSortsNearestFirstAndExcludesSelfByDefault() {
        assertEquals(List.of(NEAR_PIG, COW, MOD_MOB, FAR_PIG), EntitySelector.filter(ALL, null, 32, 100, false));
        assertEquals(List.of(SELF, NEAR_PIG), EntitySelector.filter(ALL, null, 32, 2, true));
    }

    @Test
    void filterAppliesTypeRadiusAndLimit() {
        assertEquals(List.of(NEAR_PIG, FAR_PIG), EntitySelector.filter(ALL, "pig", 32, 100, false));
        assertEquals(List.of(NEAR_PIG), EntitySelector.filter(ALL, "minecraft:pig", 10, 100, false));
        assertEquals(List.of(MOD_MOB), EntitySelector.filter(ALL, "pixelmon:pixelmon", 32, 100, false));
        assertEquals(List.of(NEAR_PIG), EntitySelector.filter(ALL, null, 32, 1, false));
    }

    @Test
    void nonPositiveRadiusMeansAnyDistance() {
        assertEquals(List.of(NEAR_PIG, FAR_PIG), EntitySelector.filter(ALL, "pig", 0, 100, false));
    }

    @Test
    void selectPrefersIdThenUuidThenNearestOfType() {
        assertEquals(FAR_PIG, EntitySelector.select(new Query(2, null, "cow", 32), ALL).orElseThrow());
        assertEquals(COW, EntitySelector.select(new Query(null, COW.uuid().toUpperCase(), null, 32), ALL).orElseThrow());
        assertEquals(NEAR_PIG, EntitySelector.select(new Query(null, null, "pig", 32), ALL).orElseThrow());
        assertTrue(EntitySelector.select(new Query(null, null, "pig", 1), ALL).isEmpty());
        assertTrue(EntitySelector.select(new Query(99, null, null, 32), ALL).isEmpty());
    }

    @Test
    void queryRequiresASelector() {
        assertThrows(IllegalArgumentException.class, () -> Query.from(Map.of("radius", 5)));
        Query query = Query.from(Map.of("type", "Pig", "radius", 5));
        assertEquals(Map.of("type", "minecraft:pig", "radius", 5.0), query.describe());
    }
}
