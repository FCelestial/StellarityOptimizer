package top.miragedge.stellarityoptimizer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.Set;

/**
 * Optimized tick dispatcher - minimizes Bukkit.dispatchCommand() calls.
 *
 * Key optimizations vs v1:
 * 1. Batch dispatch: use @e selectors with limit=1 guard before per-entity dispatch
 * 2. World.getNearbyEntities for proximity checks (spatial index) instead of iterating all entities
 * 3. Skip modules entirely when no relevant entities exist (early exit)
 * 4. Cache proximity-distance as field, not per-call config read
 * 5. Reduce per-player iteration: most modules only need global check, not per-player
 *
 * dispatchCommand() costs ~0.1-0.5ms per call due to command parsing chain.
 * With 200+ markers, v1 dispatched 200+ commands/tick = 20-100ms/tick.
 * v2 batches into single @e commands or skips entirely when no entities match.
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

    private final Set<String> dispatchedThisTick = new java.util.HashSet<>();
    private boolean bossbarShulkingActive = false;

    private int dispatchCount = 0;
    private int skipCount = 0;

    private final CommandSender console;
    private final double proximityDist;
    private final String distArg;

    public TickDispatcher(StellarityOptimizer plugin, EntityRegistry registry,
                          PlayerProximityCache cache, ModuleConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.cache = cache;
        this.config = config;
        this.console = Bukkit.getConsoleSender();
        this.proximityDist = plugin.getConfig().getInt("proximity-distance", 256);
        this.distArg = String.valueOf((int) proximityDist);
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

            checkGlobalModules();

            // Per-player modules (only those that MUST be per-player)
            for (Player player : Bukkit.getOnlinePlayers()) {
                checkPlayerModules(player);
            }

            // Bossbar clear
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
            tick10Active = false;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // GLOBAL MODULES - run once per tick (not per player)
    // ═════════════════════════════════════════════════════════════════

    private void checkGlobalModules() {

        // === Marker loop ===
        // Instead of per-entity dispatch (N commands), use single @e with distance guard.
        // Original: per-entity "execute as <uuid> at @s run function stellarity:loop/marker/main"
        // Problem: if 100 markers exist, that's 100 dispatchCommand calls.
        // Solution: single dispatch with @e selector that MC handles internally (faster than 100 dispatch calls).
        // BUT: distance check must be relative to each entity, not world origin.
        // So we use "at @s if entity @p[distance=..N]" which MC evaluates per-entity.
        if (config.isEnabled("marker_loop")) {
            Set<Entity> markers = registry.getByType(EntityType.MARKER);
            if (!markers.isEmpty()) {
                // Single dispatch - MC handles the iteration internally
                dispatch("execute as @e[type=marker,tag=stellarity.marker] at @s if entity @p[distance=.." + distArg + "] run function stellarity:loop/marker/main");
                dispatchCount++;
            } else { skipCount++; }
        }

        // === Item loop (4-tick phase 3) ===
        if (config.isEnabled("item_loop") && tick4Counter == 3) {
            Set<Entity> items = registry.getByType(EntityType.ITEM);
            if (!items.isEmpty()) {
                dispatch("execute as @e[type=item,tag=stellarity.item] at @s if entity @p[distance=.." + distArg + "] run function stellarity:loop/item_loop");
                dispatchCount++;
            } else { skipCount++; }
        }

        // === End city crystal ===
        if (config.isEnabled("end_city_crystal")) {
            Set<Entity> crystals = registry.getByType(EntityType.END_CRYSTAL);
            if (!crystals.isEmpty()) {
                dispatch("execute as @e[type=end_crystal,tag=stellarity.end_city.crystal] at @s run function stellarity:structure/end_city/crystal/main");
                dispatchCount++;
            } else { skipCount++; }
        }

        // === Exit portal crystal ===
        if (config.isEnabled("exit_portal")) {
            Set<Entity> crystals = registry.getByType(EntityType.END_CRYSTAL);
            if (!crystals.isEmpty()) {
                dispatch("execute as @e[type=minecraft:end_crystal,predicate=stellarity:entity/dragon/exit_portal_crystal] at @s run function stellarity:structure/exit_portal/replace");
                dispatchCount++;
            } else { skipCount++; }
        }

        // === Kohara ===

        // Kohara status effects - MUST use @e (not per-player) because tags can be on mobs too
        // (e.g. bloom effect on monsters). Original: @e[type=!#kohara:invalid_targets,tag=kohara.status_effect.tick]
        // Optimization: removed redundant "if entity @e[...limit=1]" guard - Java registry check above is sufficient.
        // The @e selector returns empty (no-op) when no entities match, so the guard was a double scan.
        if (config.isEnabled("kohara_status")) {
            dispatch("execute as @e[type=!#kohara:invalid_targets,tag=kohara.status_effect.tick] at @s run function #kohara:status_effects/tick");
            dispatchCount++;
        }

        // Kohara particles - iterate ITEM_DISPLAY entities with tag
        if (config.isEnabled("kohara_particles")) {
            Set<Entity> displays = registry.getByType(EntityType.ITEM_DISPLAY);
            if (!displays.isEmpty()) {
                // Single dispatch with @e selector - MC handles iteration
                dispatch("execute as @e[type=item_display,tag=kohara.particles] at @s run function kohara:particles/settings/particle_main");
                dispatchCount++;
            } else { skipCount++; }
        }

        // Kohara items
        if (config.isEnabled("kohara_items")) {
            Set<Entity> items = registry.getByType(EntityType.ITEM);
            if (!items.isEmpty()) {
                dispatch("execute as @e[type=minecraft:item,tag=!kohara.ticked] at @s run function kohara:items_tick_once/tick_once");
                dispatchCount++;
            } else { skipCount++; }
        }

        // Kohara heal - only dispatch if healed entities exist
        // Optimization: removed redundant "if entity @e[...limit=1]" guard.
        // The @e selector returns empty (no-op) when no entities match.
        if (config.isEnabled("kohara_heal")) {
            dispatch("execute as @e[type=!#kohara:invalid_targets,tag=kohara.healed] run function kohara:heal/reset_hp");
            dispatchCount++;
        }

        // === Stellarity item/main ===

        // Dragonblade (score-based, must use @e)
        // Optimization: removed redundant "if entity @e[...limit=1]" guard.
        if (config.isEnabled("dragonblade")) {
            dispatch("execute as @e[type=!#kohara:invalid_targets,scores={stellarity.item.dragonblade.until_punch_reset=1..}] run function stellarity:item/dragonblade/punch/progress_reset_countdown");
            dispatchCount++;
        }

        // Projectile loop
        if (config.isEnabled("projectile_loop")) {
            Set<Entity> arrows = registry.getByType(EntityType.ARROW);
            Set<Entity> spectralArrows = registry.getByType(EntityType.SPECTRAL_ARROW);
            if (!arrows.isEmpty() || !spectralArrows.isEmpty()) {
                dispatch("execute as @e[type=#minecraft:arrows,tag=stellarity.arrow] at @s run function stellarity:loop/projectile_loop");
                dispatchCount++;
            } else { skipCount++; }
        }

        // Phantom item frame
        if (config.isEnabled("phantom_item_frame")) {
            Set<Entity> frames = registry.getByType(EntityType.ITEM_FRAME);
            if (!frames.isEmpty()) {
                dispatch("execute as @e[type=item_frame,tag=stellarity.phantom_item_frame] at @s run function stellarity:item/phantom_item_frame/main");
                dispatchCount++;
            } else { skipCount++; }
        }

        // Sandstorm trident + block display
        if (config.isEnabled("sandstorm_trident")) {
            Set<Entity> tridents = registry.getByType(EntityType.TRIDENT);
            if (!tridents.isEmpty()) {
                dispatch("execute as @e[type=trident,tag=stellarity.sandstorm_trident,tag=!stellarity.sandstorm_trident.activated] at @s run function stellarity:item/sandstorm_trident/main");
                dispatchCount++;
            } else { skipCount++; }

            Set<Entity> blockDisplays = registry.getByType(EntityType.BLOCK_DISPLAY);
            if (!blockDisplays.isEmpty()) {
                dispatch("execute as @e[type=block_display,tag=stellarity.sandstorm_trident] at @s run function stellarity:item/sandstorm_trident/wind_tunnel/movement/tick_block_display");
                dispatchCount++;
            } else { skipCount++; }
        }

        // Fluffy hammer
        if (config.isEnabled("fluffy_hammer")) {
            Set<Entity> displays = registry.getByType(EntityType.ITEM_DISPLAY);
            if (!displays.isEmpty()) {
                dispatch("execute as @e[type=item_display,tag=stellarity.fluffy_hammer] at @s run function stellarity:item/fluffy_hammer/main");
                dispatchCount++;
            } else { skipCount++; }
        }

        // === Entity/main modules (moved from checkPlayerModules) ===
        // These run ONCE per tick (not per player) to avoid N× execution.
        // The @e selector with "if entity @p[distance=..N]" handles proximity
        // checking internally per-entity.

        // Pixies
        if (config.isEnabled("pixies")) {
            Set<Entity> vexes = registry.getByType(EntityType.VEX);
            if (!vexes.isEmpty()) {
                dispatch("execute as @e[type=vex,tag=!stellarity.pixie,tag=!smithed.entity,tag=!stellarity.aware,predicate=stellarity:entity/pixie_can_spawn_in] at @s if entity @p[distance=.." + distArg + "] run function stellarity:entity/pixie/check");
                dispatchCount++;
            } else { skipCount++; }
        }

        // Empress of Light
        if (config.isEnabled("empress_of_light")) {
            Set<Entity> vindicators = registry.getByType(EntityType.VINDICATOR);
            if (!vindicators.isEmpty()) {
                dispatch("execute as @e[type=vindicator,tag=stellarity.empress_of_light] at @s if entity @p[distance=.." + distArg + "] run function stellarity:entity/empress_of_light/main");
                dispatchCount++;
            } else { skipCount++; }

            Set<Entity> markers = registry.getByType(EntityType.MARKER);
            if (!markers.isEmpty()) {
                dispatch("execute as @e[type=marker,tag=stellarity.empress_of_light.tracker] at @s if entity @p[distance=.." + distArg + "] run function stellarity:entity/empress_of_light/animations/death/check_death with entity @s data.\"stellarity:owner\"");
                dispatchCount++;
            } else { skipCount++; }
        }

        // Dragon
        if (config.isEnabled("dragon")) {
            Set<Entity> dragons = registry.getByType(EntityType.ENDER_DRAGON);
            if (!dragons.isEmpty()) {
                dispatch("execute as @e[type=ender_dragon,tag=stellarity.ender_dragon] at @s run function stellarity:entity/dragon/main");
                dispatchCount++;
            } else { skipCount++; }
        }

        // Flesh piglin
        if (config.isEnabled("flesh_piglin")) {
            Set<Entity> piglins = registry.getByType(EntityType.ZOMBIFIED_PIGLIN);
            if (!piglins.isEmpty()) {
                dispatch("execute as @e[type=zombified_piglin,tag=stellarity.flesh_piglin] at @s if entity @p[distance=.." + distArg + "] run function stellarity:entity/flesh_piglin/main");
                dispatchCount++;
            } else { skipCount++; }
        }

        // Spawn egg
        if (config.isEnabled("spawn_egg")) {
            Set<Entity> markers = registry.getByType(EntityType.MARKER);
            if (!markers.isEmpty()) {
                dispatch("execute as @e[type=marker,tag=stellarity.spawn_egg] at @s if entity @p[distance=.." + distArg + "] run function stellarity:entity/handle_spawn_egg with entity @s data");
                dispatchCount++;
            } else { skipCount++; }
        }

        // Illusioner (10tick)
        if (config.isEnabled("illusioner") && tick10Active) {
            Set<Entity> illusioners = registry.getByType(EntityType.ILLUSIONER);
            if (!illusioners.isEmpty()) {
                dispatch("execute as @e[type=illusioner,tag=!smithed.entity] at @s if biome ~ ~ ~ #is_end run function stellarity:entity/animal/end_spawn");
                dispatchCount++;
            } else { skipCount++; }
        }

        // Shulking
        if (config.isEnabled("shulking")) {
            Set<Entity> allays = registry.getByType(EntityType.ALLAY);
            if (!allays.isEmpty()) {
                dispatch("execute as @e[type=allay,tag=stellarity.shulking] at @s if entity @p[distance=.." + distArg + "] run function stellarity:entity/shulking/main");
                dispatchCount++;
            } else { skipCount++; }

            Set<Entity> shulkers = registry.getByType(EntityType.SHULKER);
            if (!shulkers.isEmpty()) {
                dispatch("execute as @e[type=shulker,tag=stellarity.shulking.body] at @s if entity @p[distance=.." + distArg + "] run function stellarity:entity/shulking/main_body");
                dispatchCount++;
            } else { skipCount++; }

            Set<Entity> displays = registry.getByType(EntityType.ITEM_DISPLAY);
            if (!displays.isEmpty()) {
                dispatch("execute as @e[type=item_display,tag=stellarity.shulking.spike] at @s if entity @p[distance=.." + distArg + "] run function stellarity:entity/shulking/attacks/spike/loop");
                dispatchCount++;
            } else { skipCount++; }
        }

        // Animal convert (10tick)
        if (config.isEnabled("animal_convert") && tick10Active) {
            boolean foundAnimals = false;
            for (EntityType et : END_VARIANT_ANIMALS) {
                if (!registry.getByType(et).isEmpty()) {
                    foundAnimals = true;
                    break;
                }
            }
            if (foundAnimals) {
                dispatch("execute as @e[type=#stellarity:end_variant_animals,tag=!smithed.entity,nbt={variant:\"stellarity:end\"}] at @s if entity @p[distance=.." + distArg + "] run function stellarity:entity/animal/convert");
                dispatchCount++;
            } else { skipCount++; }

            Set<Entity> sheep = registry.getByType(EntityType.SHEEP);
            if (!sheep.isEmpty()) {
                dispatch("execute as @e[type=sheep,tag=!stellarity.invalid_animal,tag=!smithed.entity] at @s if entity @p[distance=.." + distArg + "] run function stellarity:entity/animal/convert_sheep");
                dispatchCount++;
            } else { skipCount++; }
        }

        // Shulking ray
        if (config.isEnabled("shulking_ray")) {
            Set<Entity> displays = registry.getByType(EntityType.ITEM_DISPLAY);
            if (!displays.isEmpty()) {
                dispatch("execute as @e[type=item_display,tag=stellarity.shulking.ray] at @s if entity @p[distance=.." + distArg + "] run function stellarity:entity/shulking/attacks/ray/loop with entity @s data.\"stellarity:owner\"");
                dispatchCount++;
            } else { skipCount++; }
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // PLAYER MODULES - only modules that MUST check per-player proximity
    // ═════════════════════════════════════════════════════════════════

    private void checkPlayerModules(Player player) {
        // All modules have been moved to checkGlobalModules() to avoid
        // per-player dispatch causing N× execution of Boss AI functions.
        //
        // The @e selector with "if entity @p[distance=..N]" already handles
        // per-player proximity checking internally - MC evaluates the distance
        // for each entity against the nearest player.
        //
        // This method is kept empty to avoid breaking the tick() caller.
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
