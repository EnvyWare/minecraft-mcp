package io.izzel.minecraftmcp.neoforge;

import io.izzel.minecraftmcp.entity.EntitySelector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Entity listing, look-at and interaction for the NeoForge client bridge. Render thread only. */
final class NeoForgeEntities {
    private NeoForgeEntities() {}

    static Map<String, Object> list(Minecraft mc, String type, double radius, int limit, boolean includeSelf) {
        if (mc.player == null || mc.level == null) return Map.of("status", "not_in_world", "count", 0, "entities", List.of());
        List<Map<String, Object>> entities = new ArrayList<>();
        for (EntitySelector.Candidate candidate : EntitySelector.filter(candidates(mc), type, radius, limit, includeSelf)) {
            Entity entity = mc.level.getEntity(candidate.id());
            if (entity != null) entities.add(describe(mc.player, entity));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("count", entities.size());
        result.put("radius", radius);
        if (type != null && !type.isBlank()) result.put("type", EntitySelector.normalizeType(type));
        result.put("entities", entities);
        return result;
    }

    static Map<String, Object> lookAt(Minecraft mc, EntitySelector.Query query) {
        if (mc.player == null || mc.level == null) return Map.of("status", "not_in_world");
        Optional<Entity> target = resolve(mc, query);
        if (target.isEmpty()) return notFound(query);
        Entity entity = target.get();
        float[] rotation = rotateTowards(mc.player, entity.getBoundingBox().getCenter());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "looked_at_entity");
        result.put("entity", describe(mc.player, entity));
        result.put("yaw", rotation[0]);
        result.put("pitch", rotation[1]);
        return result;
    }

    static Map<String, Object> interact(Minecraft mc, EntitySelector.Query query, String action, String hand, boolean look) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return Map.of("status", "not_in_world", "action", action);
        Optional<Entity> target = resolve(mc, query);
        if (target.isEmpty()) return notFound(query);
        Entity entity = target.get();
        LocalPlayer player = mc.player;
        Vec3 center = entity.getBoundingBox().getCenter();
        if (look) rotateTowards(player, center);
        boolean inRange = player.canInteractWithEntity(entity, 0.0D);
        Map<String, Object> result = new LinkedHashMap<>();
        if ("attack".equals(action)) {
            mc.gameMode.attack(player, entity);
            player.swing(InteractionHand.MAIN_HAND);
            result.put("status", "attacked");
        } else {
            InteractionHand interactionHand = "offhand".equals(hand) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            Vec3 eye = player.getEyePosition();
            Vec3 location = entity.getBoundingBox().clip(eye, center).orElse(center);
            EntityHitResult hit = new EntityHitResult(entity, location);
            InteractionResult interaction = mc.gameMode.interactAt(player, entity, hit, interactionHand);
            if (!interaction.consumesAction()) interaction = mc.gameMode.interact(player, entity, interactionHand);
            if (interaction.consumesAction() && interaction.shouldSwing()) player.swing(interactionHand);
            result.put("status", "interacted");
            result.put("result", interaction.toString());
            result.put("consumesAction", interaction.consumesAction());
            result.put("hand", interactionHand.name().toLowerCase(Locale.ROOT));
        }
        result.put("action", action);
        result.put("inRange", inRange);
        result.put("entity", describe(player, entity));
        return result;
    }

    private static Optional<Entity> resolve(Minecraft mc, EntitySelector.Query query) {
        return EntitySelector.select(query, candidates(mc)).map(candidate -> mc.level.getEntity(candidate.id()));
    }

    private static List<EntitySelector.Candidate> candidates(Minecraft mc) {
        List<EntitySelector.Candidate> candidates = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            candidates.add(new EntitySelector.Candidate(entity.getId(), entity.getStringUUID(), typeId(entity), entity.distanceTo(mc.player), entity == mc.player));
        }
        return candidates;
    }

    private static Map<String, Object> notFound(EntitySelector.Query query) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "not_found");
        result.putAll(query.describe());
        return result;
    }

    static Map<String, Object> describe(Player player, Entity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("uuid", entity.getStringUUID());
        map.put("type", typeId(entity));
        map.put("name", entity.getName().getString());
        map.put("customName", entity.hasCustomName() ? entity.getCustomName().getString() : null);
        map.put("position", Map.of("x", entity.getX(), "y", entity.getY(), "z", entity.getZ()));
        map.put("distance", entity.distanceTo(player));
        map.put("alive", entity.isAlive());
        map.put("isPlayer", entity instanceof Player);
        return map;
    }

    private static String typeId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    /** Same rotation maths as mc.player.look_at. Returns {yaw, pitch}. */
    private static float[] rotateTowards(LocalPlayer player, Vec3 target) {
        Vec3 eye = player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = Math.max(-90.0F, Math.min(90.0F, (float) (-Math.toDegrees(Math.atan2(dy, horizontal)))));
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yHeadRot = yaw;
        player.yBodyRot = yaw;
        return new float[] {yaw, pitch};
    }
}
