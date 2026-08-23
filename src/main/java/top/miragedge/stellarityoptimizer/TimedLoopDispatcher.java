package top.miragedge.stellarityoptimizer;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;

/**
 * TimedLoopDispatcher - replaces Stellarity's schedule-function-based timed loops
 * with Bukkit scheduler tasks.
 *
 * The original datapack used self-scheduling functions:
 *   schedule function stellarity:loop/timed/2_tick 2t
 *   (function executes, then re-schedules itself)
 *
 * This created TimerQueue overhead (27.87% of tick time in Spark profiling).
 *
 * This class replaces them with BukkitScheduler tasks that:
 * 1. Check registry for matching entities (O(1) type lookup)
 * 2. Only dispatch @e commands when entities exist (skip = zero cost)
 * 3. Execute at the same frequency as the original schedule functions
 *
 * The datapack function files are kept intact (only the self-scheduling line
 * and init.mcfunction schedule calls were removed).
 */
public class TimedLoopDispatcher {

    private final StellarityOptimizer plugin;
    private final EntityRegistry registry;
    private final CommandSender console;
    private BukkitTask task2t, task3t, task5t, task1s, task5s;

    public TimedLoopDispatcher(StellarityOptimizer plugin, EntityRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.console = Bukkit.getConsoleSender();
    }

    public void start() {
        // 2-tick loop: vex/pixie sounds, particles
        task2t = Bukkit.getScheduler().runTaskTimer(plugin, this::tick2, 2L, 2L);

        // 3-tick loop: cooldown tickdown + shulker bullet targeting
        task3t = Bukkit.getScheduler().runTaskTimer(plugin, this::tick3, 3L, 3L);

        // 5-tick loop: villager main + block light update
        task5t = Bukkit.getScheduler().runTaskTimer(plugin, this::tick5, 5L, 5L);

        // 1-second loop: phantoms, dragon ash, tridents, villagers, stat buffs, awareness, end variants, shulking
        task1s = Bukkit.getScheduler().runTaskTimer(plugin, this::tick1s, 20L, 20L);

        // 5-second loop: raider buff + migrations
        task5s = Bukkit.getScheduler().runTaskTimer(plugin, this::tick5s, 100L, 100L);
    }

    public void stop() {
        if (task2t != null) { task2t.cancel(); task2t = null; }
        if (task3t != null) { task3t.cancel(); task3t = null; }
        if (task5t != null) { task5t.cancel(); task5t = null; }
        if (task1s != null) { task1s.cancel(); task1s = null; }
        if (task5s != null) { task5s.cancel(); task5s = null; }
    }

    private void dispatch(String command) {
        Bukkit.dispatchCommand(console, command);
    }

    /**
     * 2-tick loop: Pixie sounds and particles
     * Original: execute as @e[type=vex,tag=stellarity.pixie] at @s run function stellarity:entity/pixie/loop
     */
    private void tick2() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return;
        Set<Entity> vexes = registry.getByType(EntityType.VEX);
        if (!vexes.isEmpty()) {
            // Check if any vex has stellarity.pixie tag
            for (Entity e : vexes) {
                if (e.getScoreboardTags().contains("stellarity.pixie")) {
                    dispatch("execute as @e[type=vex,tag=stellarity.pixie] at @s run function stellarity:entity/pixie/loop");
                    return;
                }
            }
        }
    }

    /**
     * 3-tick loop: Cooldown tickdown + shulker bullet targeting
     */
    private void tick3() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return;

        // Cooldown tickdown - any entity with stellarity.cooldown_tickdown tag
        // Can't use registry byTag (unreliable), use @e directly
        dispatch("execute as @e[type=!#kohara:invalid_targets,tag=stellarity.cooldown_tickdown] at @s run function stellarity:item/entity_cooldowns");

        // Shulker bullet targeting (shulker_bullet is a projectile, not SHULKER type - no registry guard possible)
        dispatch("execute as @e[type=minecraft:shulker_bullet,tag=stellarity.defensive_shulker_bullet] at @s run function stellarity:item/armor/shulker/shulker_bullets/seek");
        // Remove shulker armor attacker tag - guard to avoid "No entity was found" when no player has the tag
        dispatch("execute if entity @a[tag=stellarity.item.shulker_armor.attacker] run tag @a[tag=stellarity.item.shulker_armor.attacker] remove stellarity.item.shulker_armor.attacker");
    }

    /**
     * 5-tick loop: Villager main + block light update
     */
    private void tick5() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return;

        // Villagers - check if any villager exists
        Set<Entity> villagers = registry.getByType(EntityType.VILLAGER);
        if (!villagers.isEmpty()) {
            dispatch("execute as @e[type=villager,tag=stellarity.villager,tag=!stellarity.villager.nitwit,tag=!stellarity.villager.level_5] at @s run function stellarity:entity/villager/main");
        }

        // Block display light update
        Set<Entity> displays = registry.getByType(EntityType.ITEM_DISPLAY);
        if (!displays.isEmpty()) {
            // Check if any has stellarity.block tag
            for (Entity e : displays) {
                if (e.getScoreboardTags().contains("stellarity.block")) {
                    dispatch("execute as @e[type=item_display,tag=stellarity.block] at @s run function stellarity:block/update_light");
                    break;
                }
            }
        }
    }

    /**
     * 1-second loop: Phantoms, dragon ash, tridents, villagers, stat buffs, awareness, end variants, shulking
     */
    private void tick1s() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return;

        // Phantoms (overworld only)
        Set<Entity> phantoms = registry.getByType(EntityType.PHANTOM);
        if (!phantoms.isEmpty()) {
            dispatch("execute in minecraft:overworld as @e[type=phantom,tag=!stellarity.phantom.aware,distance=0..] at @s run function stellarity:entity/phantom/enlarge");
            dispatch("execute as @e[type=phantom] unless score @s stellarity.phantom.size matches 1.. run function stellarity:entity/phantom/score");
        }

        // Dragon's Ashes tickdown (scoreboard only, no @e)
        dispatch("execute if score #stellarity.dragon.ash_duration stellarity.misc matches 1.. run scoreboard players remove #stellarity.dragon.ash_duration stellarity.misc 1");

        // Tridents return when in Void (end only)
        Set<Entity> tridents = registry.getByType(EntityType.TRIDENT);
        if (!tridents.isEmpty()) {
            dispatch("execute as @e[type=trident,predicate=stellarity:location/below_y_0,predicate=stellarity:location/in_the_end] run data merge entity @s {DealtDamage:1b}");
        }

        // Villagers in end village
        Set<Entity> villagers = registry.getByType(EntityType.VILLAGER);
        if (!villagers.isEmpty()) {
            dispatch("execute as @e[type=villager,tag=!stellarity.aware,tag=!stellarity.villager,predicate=stellarity:location/in_structure/end_village] at @s run function stellarity:entity/villager/check");
        }

        // Stat buff entities
        dispatch("execute as @e[type=#stellarity:stat_buff,tag=!stellarity.aware,tag=!stellarity.buffed,predicate=stellarity:location/in_the_end,tag=!smithed.entity] run function stellarity:entity/convert_to_end_variants");

        // Awareness checks
        dispatch("execute as @e[type=#stellarity:entity_awareness_checks,tag=!stellarity.aware] run tag @s add stellarity.aware");

        // End variant animal aura
        dispatch("execute as @e[type=#stellarity:end_variant_animals,tag=stellarity.animal] at @s run function stellarity:entity/animal/effects/aura");

        // Shulking 1s
        Set<Entity> allays = registry.getByType(EntityType.ALLAY);
        if (!allays.isEmpty()) {
            for (Entity e : allays) {
                if (e.getScoreboardTags().contains("stellarity.shulking")) {
                    dispatch("execute as @e[type=allay,tag=stellarity.shulking] at @s run function stellarity:entity/shulking/main_1s");
                    break;
                }
            }
        }
    }

    /**
     * 5-second loop: Raider buff + migrations
     */
    private void tick5s() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return;

        // Raider buff
        dispatch("execute as @e[type=#minecraft:raiders,tag=!stellarity.stronghold.buffed,predicate=stellarity:location/in_structure/stronghold] run function stellarity:entity/stronghold/buff_illagers");

        // Migrations
        dispatch("execute if score #stellarity.config stellarity.config.migrations matches 1 run function stellarity:migrations/main");
    }
}
