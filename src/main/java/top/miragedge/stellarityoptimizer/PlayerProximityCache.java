package top.miragedge.stellarityoptimizer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches entities near each player using World.getNearbyEntities (spatial index).
 *
 * v2 optimization: uses World.getNearbyEntities() which uses MC's internal
 * spatial index (chunk-based) instead of iterating all registered entities.
 * This is O(chunks_in_range) instead of O(total_entities).
 *
 * For 256-block radius with simulation-distance 6, this scans ~25 chunks
 * instead of 4000+ entities.
 */
public class PlayerProximityCache {

    private final StellarityOptimizer plugin;
    private final EntityRegistry registry;
    private BukkitTask cacheTask;

    // Per-player nearby entity set (cached, refreshed every N ticks)
    private final Map<UUID, Set<Entity>> nearbyEntities = new ConcurrentHashMap<>();
    private final Map<UUID, Location> playerLocations = new ConcurrentHashMap<>();

    private final double proximityDist;

    public PlayerProximityCache(StellarityOptimizer plugin, EntityRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.proximityDist = plugin.getConfig().getInt("proximity-distance", 256);
    }

    public void start(int intervalTicks) {
        cacheTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateCache, 20L, intervalTicks);
    }

    public void restart(int intervalTicks) {
        stop();
        start(intervalTicks);
    }

    public void stop() {
        if (cacheTask != null) {
            cacheTask.cancel();
            cacheTask = null;
        }
    }

    private void updateCache() {
        Set<UUID> onlineUuids = new HashSet<>();
        boolean debug = plugin.getConfig().getBoolean("debug", false);

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            onlineUuids.add(uuid);

            Location loc = player.getLocation();
            playerLocations.put(uuid, loc);

            // Use World.getNearbyEntities - MC's internal spatial index
            // This is much faster than iterating all registered entities
            Collection<Entity> nearby = loc.getWorld().getNearbyEntities(loc, proximityDist, proximityDist, proximityDist);

            Set<Entity> nearbySet = new HashSet<>(nearby);
            nearbyEntities.put(uuid, nearbySet);

            if (debug) {
                plugin.getLogger().info("Player " + player.getName() + " has " + nearbySet.size() + " nearby entities");
            }
        }

        // Clean up offline players
        nearbyEntities.keySet().retainAll(onlineUuids);
        playerLocations.keySet().retainAll(onlineUuids);
    }

    /**
     * Check if player has any nearby entity of this type with this tag.
     */
    public boolean hasNearby(Player player, EntityType type, String tag) {
        UUID uuid = player.getUniqueId();
        Set<Entity> nearby = nearbyEntities.get(uuid);
        if (nearby == null) return false;
        for (Entity e : nearby) {
            if (e.getType() == type && e.getScoreboardTags().contains(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if player has any nearby entity of this type.
     */
    public boolean hasNearbyType(Player player, EntityType type) {
        UUID uuid = player.getUniqueId();
        Set<Entity> nearby = nearbyEntities.get(uuid);
        if (nearby == null) return false;
        for (Entity e : nearby) {
            if (e.getType() == type) return true;
        }
        return false;
    }

    /**
     * Get all nearby entities of a specific type for a player.
     */
    public List<Entity> getNearbyByType(Player player, EntityType type) {
        UUID uuid = player.getUniqueId();
        Set<Entity> nearby = nearbyEntities.get(uuid);
        if (nearby == null) return Collections.emptyList();

        List<Entity> result = new ArrayList<>();
        for (Entity e : nearby) {
            if (e.getType() == type) result.add(e);
        }
        return result;
    }

    /**
     * Get nearby entities of a type with a specific tag.
     */
    public List<Entity> getNearbyByTypeAndTag(Player player, EntityType type, String tag) {
        UUID uuid = player.getUniqueId();
        Set<Entity> nearby = nearbyEntities.get(uuid);
        if (nearby == null) return Collections.emptyList();

        List<Entity> result = new ArrayList<>();
        for (Entity e : nearby) {
            if (e.getType() == type && e.getScoreboardTags().contains(tag)) {
                result.add(e);
            }
        }
        return result;
    }

    /**
     * Get nearby entities of a type that do NOT have any of the specified tags.
     */
    public List<Entity> getNearbyByTypeWithoutTags(Player player, EntityType type, String... excludeTags) {
        UUID uuid = player.getUniqueId();
        Set<Entity> nearby = nearbyEntities.get(uuid);
        if (nearby == null) return Collections.emptyList();

        Set<String> excludeSet = Set.of(excludeTags);
        List<Entity> result = new ArrayList<>();
        for (Entity e : nearby) {
            if (e.getType() != type) continue;
            boolean excluded = false;
            for (String tag : e.getScoreboardTags()) {
                if (excludeSet.contains(tag)) {
                    excluded = true;
                    break;
                }
            }
            if (!excluded) result.add(e);
        }
        return result;
    }
}
