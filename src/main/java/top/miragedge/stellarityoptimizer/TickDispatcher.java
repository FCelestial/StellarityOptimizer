package top.miragedge.stellarityoptimizer;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Set;

/**
 * Replaces Stellarity/True-Ending/Kohara datapack @e scans with indexed lookups.
 *
 * CRITICAL DESIGN NOTE:
 * Minecraft datapacks frequently add tags to entities AFTER spawn via
 * "tag @s add xxx" or "data merge entity @s {Tags:[...]}". This means
 * the EntityRegistry's byTag index (built at spawn time) is unreliable
 * for most Stellarity tags. Instead, we use byType index (which is stable
 * - entity type doesn't change) and check tags at query time via
 * e.getScoreboardTags().contains(tag).
 *
 * This is slightly slower than a pure index lookup but guarantees correctness.
 * The byType index still provides O(1) type filtering, eliminating the
 * 4000+ entity full-scan of @e selectors.
 */
public class TickDispatcher {

    private final StellarityOptimizer plugin;
    private final EntityRegistry registry;
    private final PlayerProximityCache cache;
    private final ModuleConfig config;
    private BukkitTask tickTask;

    private int tick10Counter = 0;
    private boolean tick10Active = false;

    private int tick4Counter = 0;

    private final java.util.Set<String> dispatchedThisTick = new java.util.HashSet<>();

    private boolean bossbarShulkingActive = false;

    private int dispatchCount = 0;
    private int skipCount = 0;

    private CommandSender console;

    public TickDispatcher(StellarityOptimizer plugin, EntityRegistry registry,
                          PlayerProximityCache cache, ModuleConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.cache = cache;
        this.config = config;
        this.console = Bukkit.getConsoleSender();
    }

    public void start() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 1L);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    private void tick() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return;

        try {
            dispatchedThisTick.clear();

            tick10Counter++;
            tick10Active = (tick10Counter >= 10);
            if (tick10Active) tick10Counter = 0;

            tick4Counter++;
            if (tick4Counter > 3) tick4Counter = 0;

            for (Player player : Bukkit.getOnlinePlayers()) {
                checkModules(player);
            }

            checkGlobalModules();

            // Bossbar clear: if no shulking body near any player, clear bossbar
            if (config.isEnabled("shulking_bossbar")) {
                boolean anyNearby = false;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (cache.hasNearby(p, EntityType.SHULKER, "stellarity.shulking.body")) {
                        anyNearby = true;
                        break;
                    }
                }
                if (anyNearby) {
                    bossbarShulkingActive = true;
                } else if (bossbarShulkingActive) {
                    bossbarShulkingActive = false;
                    dispatch("bossbar set stellarity:shulking players");
                    dispatchCount++;
                }
            }
        } finally {
            // Ensure dispatchedThisTick is always cleared even if exception occurs
            tick10Active = false;
        }
    }

    /**
     * Helper: get entities of a type that have a specific tag.
     * Uses byType index then checks tags at query time (not byTag index)
     * because tags are frequently added after spawn via tag/data commands.
     */
    private void dispatchByTypeAndTag(EntityType type, String tag, String functionPath) {
        Set<Entity> entities = registry.getByType(type);
        for (Entity e : entities) {
            if (e.getScoreboardTags().contains(tag)) {
                dispatchEntity(e, functionPath);
            }
        }
        if (entities.isEmpty()) skipCount++;
    }

    /**
     * Helper: get entities of a type that have a tag, with player distance check.
     */
    private void dispatchByTypeAndTagWithDist(EntityType type, String tag, String functionPath, String distArg) {
        Set<Entity> entities = registry.getByType(type);
        for (Entity e : entities) {
            if (e.getScoreboardTags().contains(tag)) {
                if (isNearAnyPlayer(e, getProximityDist())) {
                    dispatchEntityWithDist(e, functionPath, distArg);
                }
            }
        }
        if (entities.isEmpty()) skipCount++;
    }

    private double getProximityDist() {
        return plugin.getConfig().getInt("proximity-distance", 256);
    }

    private String getDistArg() {
        return String.valueOf((int) getProximityDist());
    }

    private void checkGlobalModules() {
        double proximityDist = getProximityDist();
        String distArg = getDistArg();

        // === 16. Marker loop ===
        // Tags added at summon time via NBT Tags:[...] - byType + tag check
        if (config.isEnabled("marker_loop")) {
            Set<Entity> markers = registry.getByType(EntityType.MARKER);
            for (Entity e : markers) {
                if (e.getScoreboardTags().contains("stellarity.marker")) {
                    if (isNearAnyPlayer(e, proximityDist)) {
                        dispatchEntity(e, "stellarity:loop/marker/main");
                    }
                }
            }
            if (markers.isEmpty()) skipCount++;
        }

        // === 17. Item loop (4-tick phase 3) ===
        if (config.isEnabled("item_loop") && tick4Counter == 3) {
            Set<Entity> items = registry.getByType(EntityType.ITEM);
            for (Entity e : items) {
                if (e.getScoreboardTags().contains("stellarity.item")) {
                    if (isNearAnyPlayer(e, proximityDist)) {
                        dispatchEntity(e, "stellarity:loop/item_loop");
                    }
                }
            }
            if (items.isEmpty()) skipCount++;
        }

        // === 18. End city crystal ===
        if (config.isEnabled("end_city_crystal")) {
            Set<Entity> crystals = registry.getByType(EntityType.END_CRYSTAL);
            for (Entity e : crystals) {
                if (e.getScoreboardTags().contains("stellarity.end_city.crystal")) {
                    dispatchEntity(e, "stellarity:structure/end_city/crystal/main");
                }
            }
            if (crystals.isEmpty()) skipCount++;
        }

        // === 19. Exit portal crystal ===
        if (config.isEnabled("exit_portal")) {
            boolean anyCrystalNear = false;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (cache.hasNearbyType(p, EntityType.END_CRYSTAL)) {
                    anyCrystalNear = true;
                    break;
                }
            }
            if (anyCrystalNear) {
                String dedupKey = "exit_portal";
                if (!dispatchedThisTick.contains(dedupKey)) {
                    dispatchedThisTick.add(dedupKey);
                    dispatch("execute as @e[type=minecraft:end_crystal,predicate=stellarity:entity/dragon/exit_portal_crystal] at @s run function stellarity:structure/exit_portal/replace");
                    dispatchCount++;
                }
            } else { skipCount++; }
        }

        // === Kohara ===

        // 20. Status effects - CRITICAL: kohara.status_effect.tick tag is added via
        // "tag @s add" AFTER spawn (by apply.mcfunction), so byTag index won't have it.
        // Status effects are on players, so we iterate all online players.
        // Original: @e[type=!#kohara:invalid_targets,tag=kohara.status_effect.tick]
        if (config.isEnabled("kohara_status")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getScoreboardTags().contains("kohara.status_effect.tick")) {
                    String key = p.getUniqueId() + "|#kohara:status_effects/tick";
                    if (!dispatchedThisTick.contains(key)) {
                        dispatchedThisTick.add(key);
                        dispatch("execute as " + p.getUniqueId() + " at @s run function #kohara:status_effects/tick");
                        dispatchCount++;
                    }
                }
            }
        }

        // 21. Kohara particles - tags added via data merge after spawn
        if (config.isEnabled("kohara_particles")) {
            Set<Entity> displays = registry.getByType(EntityType.ITEM_DISPLAY);
            for (Entity e : displays) {
                if (e.getScoreboardTags().contains("kohara.particles")) {
                    dispatchEntity(e, "kohara:particles/settings/particle_main");
                }
            }
            if (displays.isEmpty()) skipCount++;
        }

        // 22. Kohara items - tag=!kohara.ticked (items WITHOUT the ticked tag)
        if (config.isEnabled("kohara_items")) {
            Set<Entity> untickedItems = registry.getByTypeWithoutTag(EntityType.ITEM, "kohara.ticked");
            if (!untickedItems.isEmpty()) {
                String dedupKey = "kohara_items";
                if (!dispatchedThisTick.contains(dedupKey)) {
                    dispatchedThisTick.add(dedupKey);
                    dispatch("execute as @e[type=minecraft:item,tag=!kohara.ticked] at @s run function kohara:items_tick_once/tick_once");
                    dispatchCount++;
                }
            } else { skipCount++; }
        }

        // 23. Kohara heal - tag added post-spawn, use @e scan (healed entities are rare)
        if (config.isEnabled("kohara_heal")) {
            dispatch("execute as @e[type=!#kohara:invalid_targets,tag=kohara.healed] run function kohara:heal/reset_hp");
            dispatchCount++;
        }

        // === Stellarity item/main ===

        // 24. Dragonblade (score-based, must use @e scan)
        if (config.isEnabled("dragonblade")) {
            dispatch("execute if entity @e[type=!#kohara:invalid_targets,scores={stellarity.item.dragonblade.until_punch_reset=1..},limit=1] as @e[type=!#kohara:invalid_targets,scores={stellarity.item.dragonblade.until_punch_reset=1..}] run function stellarity:item/dragonblade/punch/progress_reset_countdown");
            dispatchCount++;
        }

        // 25. Projectile loop - tag added after spawn
        if (config.isEnabled("projectile_loop")) {
            // Use @e scan since arrows tag is added post-spawn and arrow types vary
            // But we can check if any arrows exist first as a guard
            boolean hasArrows = !registry.getByType(EntityType.ARROW).isEmpty()
                    || !registry.getByType(EntityType.SPECTRAL_ARROW).isEmpty();
            if (hasArrows) {
                String dedupKey = "projectile_loop";
                if (!dispatchedThisTick.contains(dedupKey)) {
                    dispatchedThisTick.add(dedupKey);
                    dispatch("execute as @e[type=#minecraft:arrows,tag=stellarity.arrow] at @s run function stellarity:loop/projectile_loop");
                    dispatchCount++;
                }
            } else { skipCount++; }
        }

        // 26. Phantom item frame - tag added after spawn
        if (config.isEnabled("phantom_item_frame")) {
            Set<Entity> frames = registry.getByType(EntityType.ITEM_FRAME);
            for (Entity e : frames) {
                if (e.getScoreboardTags().contains("stellarity.phantom_item_frame")) {
                    dispatchEntity(e, "stellarity:item/phantom_item_frame/main");
                }
            }
            if (frames.isEmpty()) skipCount++;
        }

        // 27-28. Sandstorm trident + block display
        if (config.isEnabled("sandstorm_trident")) {
            Set<Entity> tridents = registry.getByType(EntityType.TRIDENT);
            for (Entity e : tridents) {
                if (e.getScoreboardTags().contains("stellarity.sandstorm_trident")
                        && !e.getScoreboardTags().contains("stellarity.sandstorm_trident.activated")) {
                    dispatchEntity(e, "stellarity:item/sandstorm_trident/main");
                }
            }
            if (tridents.isEmpty()) skipCount++;

            Set<Entity> displays = registry.getByType(EntityType.BLOCK_DISPLAY);
            for (Entity e : displays) {
                if (e.getScoreboardTags().contains("stellarity.sandstorm_trident")) {
                    dispatchEntity(e, "stellarity:item/sandstorm_trident/wind_tunnel/movement/tick_block_display");
                }
            }
            if (displays.isEmpty()) skipCount++;
        }

        // 29. Fluffy hammer - tag added after spawn
        if (config.isEnabled("fluffy_hammer")) {
            Set<Entity> displays = registry.getByType(EntityType.ITEM_DISPLAY);
            for (Entity e : displays) {
                if (e.getScoreboardTags().contains("stellarity.fluffy_hammer")) {
                    dispatchEntity(e, "stellarity:item/fluffy_hammer/main");
                }
            }
            if (displays.isEmpty()) skipCount++;
        }

        // === True-Ending ===
        // 30. True-Ending 1tick markers: RESTORED to datapack tick.mcfunction
        // The plugin should NOT handle these because:
        // - True-Ending tick has its own clock-based scheduling
        // - Removing 1tick @e scans from tick.mcfunction broke dragon AI, bossbar, fireball
        // - The original tick.mcfunction is now fully restored
    }

    private void checkModules(Player player) {
        double proximityDist = getProximityDist();
        String distArg = getDistArg();

        // 1. Pixies (predicate-based, must use @e scan)
        if (config.isEnabled("pixies")) {
            // Check if any vex without pixie tags exists near player
            boolean hasVex = !registry.getByType(EntityType.VEX).isEmpty();
            if (hasVex) {
                String dedupKey = "pixies";
                if (!dispatchedThisTick.contains(dedupKey)) {
                    dispatchedThisTick.add(dedupKey);
                    dispatch("execute as @e[type=vex,tag=!stellarity.pixie,tag=!smithed.entity,tag=!stellarity.aware,predicate=stellarity:entity/pixie_can_spawn_in] at @s if entity @p[distance=.." + distArg + "] run function stellarity:entity/pixie/check");
                    dispatchCount++;
                }
            } else { skipCount++; }
        }

        // 2. Empress of Light
        if (config.isEnabled("empress_of_light")) {
            Set<Entity> vindicators = registry.getByType(EntityType.VINDICATOR);
            for (Entity e : vindicators) {
                if (e.getScoreboardTags().contains("stellarity.empress_of_light")) {
                    if (isNear(player, e, proximityDist)) {
                        dispatchEntityWithDist(e, "stellarity:entity/empress_of_light/main", distArg);
                    }
                }
            }
            if (vindicators.isEmpty()) skipCount++;

            // 3. Empress tracker
            Set<Entity> markers = registry.getByType(EntityType.MARKER);
            for (Entity e : markers) {
                if (e.getScoreboardTags().contains("stellarity.empress_of_light.tracker")) {
                    if (isNear(player, e, proximityDist)) {
                        dispatchEntityWithDistAndOwner(e, "stellarity:entity/empress_of_light/animations/death/check_death", distArg);
                    }
                }
            }
            if (markers.isEmpty()) skipCount++;
        }

        // 4. Dragon
        if (config.isEnabled("dragon")) {
            Set<Entity> dragons = registry.getByType(EntityType.ENDER_DRAGON);
            for (Entity e : dragons) {
                if (e.getScoreboardTags().contains("stellarity.ender_dragon")) {
                    dispatchEntity(e, "stellarity:entity/dragon/main");
                }
            }
            if (dragons.isEmpty()) skipCount++;
        }

        // 5. Flesh piglin
        if (config.isEnabled("flesh_piglin")) {
            Set<Entity> piglins = registry.getByType(EntityType.ZOMBIFIED_PIGLIN);
            for (Entity e : piglins) {
                if (e.getScoreboardTags().contains("stellarity.flesh_piglin")) {
                    if (isNear(player, e, proximityDist)) {
                        dispatchEntityWithDist(e, "stellarity:entity/flesh_piglin/main", distArg);
                    }
                }
            }
            if (piglins.isEmpty()) skipCount++;
        }

        // 6. Spawn egg
        if (config.isEnabled("spawn_egg")) {
            Set<Entity> markers = registry.getByType(EntityType.MARKER);
            boolean dispatched = false;
            for (Entity e : markers) {
                if (e.getScoreboardTags().contains("stellarity.spawn_egg")) {
                    if (isNear(player, e, proximityDist)) {
                        dispatch("execute as " + e.getUniqueId() + " at @s run function stellarity:entity/handle_spawn_egg with entity @s data");
                        dispatchCount++;
                        dispatched = true;
                    }
                }
            }
            if (!dispatched) skipCount++;
        }

        // 7. Illusioner (10tick)
        if (config.isEnabled("illusioner") && tick10Active) {
            Set<Entity> illusioners = registry.getByType(EntityType.ILLUSIONER);
            for (Entity e : illusioners) {
                if (!e.getScoreboardTags().contains("smithed.entity")) {
                    String key = e.getUniqueId() + "|stellarity:entity/animal/end_spawn";
                    if (!dispatchedThisTick.contains(key)) {
                        dispatchedThisTick.add(key);
                        dispatch("execute as " + e.getUniqueId() + " at @s if biome ~ ~ ~ #is_end run function stellarity:entity/animal/end_spawn");
                        dispatchCount++;
                    }
                }
            }
            if (illusioners.isEmpty()) skipCount++;
        }

        // 8-10. Shulking
        if (config.isEnabled("shulking")) {
            // Allay
            Set<Entity> allays = registry.getByType(EntityType.ALLAY);
            for (Entity e : allays) {
                if (e.getScoreboardTags().contains("stellarity.shulking")) {
                    if (isNear(player, e, proximityDist)) {
                        dispatchEntityWithDist(e, "stellarity:entity/shulking/main", distArg);
                    }
                }
            }
            if (allays.isEmpty()) skipCount++;

            // Body
            Set<Entity> shulkers = registry.getByType(EntityType.SHULKER);
            for (Entity e : shulkers) {
                if (e.getScoreboardTags().contains("stellarity.shulking.body")) {
                    if (isNear(player, e, proximityDist)) {
                        dispatchEntityWithDist(e, "stellarity:entity/shulking/main_body", distArg);
                    }
                }
            }
            if (shulkers.isEmpty()) skipCount++;

            // Spike (item_display)
            Set<Entity> displays = registry.getByType(EntityType.ITEM_DISPLAY);
            for (Entity e : displays) {
                if (e.getScoreboardTags().contains("stellarity.shulking.spike")) {
                    if (isNear(player, e, proximityDist)) {
                        dispatchEntityWithDist(e, "stellarity:entity/shulking/attacks/spike/loop", distArg);
                    }
                }
            }
            if (displays.isEmpty()) skipCount++;
        }

        // 11. Animal convert (10tick)
        if (config.isEnabled("animal_convert") && tick10Active) {
            boolean foundAnimals = false;
            for (EntityType et : END_VARIANT_ANIMALS) {
                if (!registry.getByType(et).isEmpty()) {
                    foundAnimals = true;
                    break;
                }
            }
            if (foundAnimals) {
                String dedupKey = "animal_convert";
                if (!dispatchedThisTick.contains(dedupKey)) {
                    dispatchedThisTick.add(dedupKey);
                    dispatch("execute as @e[type=#stellarity:end_variant_animals,tag=!smithed.entity,nbt={variant:\"stellarity:end\"}] at @s if entity @p[distance=.." + distArg + "] run function stellarity:entity/animal/convert");
                    dispatchCount++;
                }
            } else { skipCount++; }
        }

        // 12. Sheep convert (10tick)
        if (config.isEnabled("animal_convert") && tick10Active) {
            Set<Entity> sheep = registry.getByType(EntityType.SHEEP);
            for (Entity e : sheep) {
                if (!e.getScoreboardTags().contains("stellarity.invalid_animal")
                        && !e.getScoreboardTags().contains("smithed.entity")) {
                    if (isNear(player, e, proximityDist)) {
                        dispatchEntityWithDist(e, "stellarity:entity/animal/convert_sheep", distArg);
                    }
                }
            }
            if (sheep.isEmpty()) skipCount++;
        }

        // 13. Shulking bossbar (state change only)
        if (config.isEnabled("shulking_bossbar")) {
            Set<Entity> shulkers = registry.getByType(EntityType.SHULKER);
            for (Entity e : shulkers) {
                if (e.getScoreboardTags().contains("stellarity.shulking.body")) {
                    if (isNear(player, e, proximityDist) && !bossbarShulkingActive) {
                        bossbarShulkingActive = true;
                        dispatch("execute as " + e.getUniqueId() + " at @s run bossbar set stellarity:shulking players @a[distance=..64]");
                        dispatchCount++;
                    }
                }
            }
        }

        // 15. Shulking ray
        if (config.isEnabled("shulking_ray")) {
            Set<Entity> displays = registry.getByType(EntityType.ITEM_DISPLAY);
            for (Entity e : displays) {
                if (e.getScoreboardTags().contains("stellarity.shulking.ray")) {
                    if (isNear(player, e, proximityDist)) {
                        dispatchEntityWithDistAndOwner(e, "stellarity:entity/shulking/attacks/ray/loop", distArg);
                    }
                }
            }
            if (displays.isEmpty()) skipCount++;
        }
    }

    private boolean isNearAnyPlayer(Entity entity, double distance) {
        double distSq = distance * distance;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(entity.getWorld())
                    && entity.getLocation().distanceSquared(p.getLocation()) <= distSq) {
                return true;
            }
        }
        return false;
    }

    private void dispatchEntity(Entity entity, String functionPath) {
        String key = entity.getUniqueId() + "|" + functionPath;
        if (dispatchedThisTick.contains(key)) return;
        dispatchedThisTick.add(key);
        dispatch("execute as " + entity.getUniqueId() + " at @s run function " + functionPath);
        dispatchCount++;
    }

    private void dispatchEntityWithDist(Entity entity, String functionPath, String distArg) {
        String key = entity.getUniqueId() + "|" + functionPath;
        if (dispatchedThisTick.contains(key)) return;
        dispatchedThisTick.add(key);
        dispatch("execute as " + entity.getUniqueId() + " at @s if entity @p[distance=.." + distArg + "] run function " + functionPath);
        dispatchCount++;
    }

    private void dispatchEntityWithDistAndOwner(Entity entity, String functionPath, String distArg) {
        String key = entity.getUniqueId() + "|" + functionPath;
        if (dispatchedThisTick.contains(key)) return;
        dispatchedThisTick.add(key);
        dispatch("execute as " + entity.getUniqueId() + " at @s if entity @p[distance=.." + distArg + "] run function " + functionPath + " with entity @s data.\"stellarity:owner\"");
        dispatchCount++;
    }

    private boolean isNear(Player player, Entity entity, double distance) {
        if (!entity.getWorld().equals(player.getWorld())) return false;
        return entity.getLocation().distanceSquared(player.getLocation()) <= distance * distance;
    }

    private void dispatch(String command) {
        Bukkit.dispatchCommand(console, command);
    }

    private static final EntityType[] END_VARIANT_ANIMALS = {
        EntityType.HORSE, EntityType.DONKEY, EntityType.MULE, EntityType.LLAMA,
        EntityType.TRADER_LLAMA, EntityType.COW, EntityType.PIG, EntityType.SHEEP,
        EntityType.CHICKEN, EntityType.RABBIT, EntityType.CAT, EntityType.WOLF,
        EntityType.FOX, EntityType.FROG
    };

    public int getDispatchCount() { return dispatchCount; }
    public int getSkipCount() { return skipCount; }
}
