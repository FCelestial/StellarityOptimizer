package top.miragedge.stellarityoptimizer;

import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches entities near each player, updated every N ticks.
 * Replaces the @p[distance=..256] check in datapack functions.
 */
public class PlayerProximityCache {

    private final StellarityOptimizer plugin;
    private final EntityRegistry registry;
    private BukkitTask cacheTask;

    // player UUID -> set of nearby entities (within proximity-distance)
    private final ConcurrentHashMap<UUID, Set<Entity>> nearbyEntities = new ConcurrentHashMap<>();

    // player UUID -> player location (for distance checks)
    private final ConcurrentHashMap<UUID, Location> playerLocations = new ConcurrentHashMap<>();

    // player UUID -> per-type existence flags (fast path: "is there a vindicator near player X?")
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<EntityType, Boolean>> nearbyTypeExists = new ConcurrentHashMap<>();

    // player UUID -> per-tag existence flags
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Boolean>> nearbyTagExists = new ConcurrentHashMap<>();

    public PlayerProximityCache(StellarityOptimizer plugin, EntityRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    public void start(int intervalTicks) {
        stop();
        cacheTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateCache, 20L, intervalTicks);
    }

    public void restart(int intervalTicks) {
        start(intervalTicks);
    }

    public void stop() {
        if (cacheTask != null) {
            cacheTask.cancel();
            cacheTask = null;
        }
    }

    private void updateCache() {
        double proximityDist = plugin.getConfig().getInt("proximity-distance", 256);
        double distSq = proximityDist * proximityDist;

        Set<UUID> onlineUuids = new HashSet<>();
        boolean debug = plugin.getConfig().getBoolean("debug", false);

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            onlineUuids.add(uuid);

            Location loc = player.getLocation();
            playerLocations.put(uuid, loc);

            // Build nearby entity set from registry (indexed, not world.getEntities)
            Set<Entity> nearby = new HashSet<>();
            ConcurrentHashMap<EntityType, Boolean> typeFlags = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, Boolean> tagFlags = new ConcurrentHashMap<>();

            // Use registry's type index instead of world.getEntities()
            // Only check entities in the same world
            for (var typeSet : registry.getByTypeValues()) {
                for (Entity e : typeSet) {
                    if (!e.getWorld().equals(player.getWorld())) continue;
                    if (e.getLocation().distanceSquared(loc) <= distSq) {
                        nearby.add(e);
                        typeFlags.put(e.getType(), true);
                        for (String tag : e.getScoreboardTags()) {
                            tagFlags.put(tag, true);
                        }
                    }
                }
            }

            nearbyEntities.put(uuid, nearby);
            nearbyTypeExists.put(uuid, typeFlags);
            nearbyTagExists.put(uuid, tagFlags);

            if (debug) {
                plugin.getLogger().info("Player " + player.getName() + " has " + nearby.size() + " nearby entities");
            }
        }

        // Clean up offline players
        nearbyEntities.keySet().retainAll(onlineUuids);
        playerLocations.keySet().retainAll(onlineUuids);
        nearbyTypeExists.keySet().retainAll(onlineUuids);
        nearbyTagExists.keySet().retainAll(onlineUuids);
    }

    /**
     * Check if player has any nearby entity of this type with this tag.
     * Checks the actual nearby entity set (not separate type/tag flags)
     * to avoid false positives from cross-contamination.
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
     * Checks actual nearby entity set to avoid cross-contamination.
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
     * Get nearby entities that have both the type and tag.
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
     * Get nearby entities by type without specific tags.
     */
    public List<Entity> getNearbyByTypeWithoutTags(Player player, EntityType type, String... excludeTags) {
        UUID uuid = player.getUniqueId();
        Set<Entity> nearby = nearbyEntities.get(uuid);
        if (nearby == null) return Collections.emptyList();

        // Build exclusion set
        Set<String> excludeSet = Set.of(excludeTags);

        List<Entity> result = new ArrayList<>();
        for (Entity e : nearby) {
            if (e.getType() != type) continue;
            boolean excluded = false;
            for (String tag : e.getScoreboardTags()) {
                if (excludeSet.contains(tag)) { excluded = true; break; }
            }
            if (!excluded) result.add(e);
        }
        return result;
    }

    public Location getPlayerLocation(Player player) {
        return playerLocations.get(player.getUniqueId());
    }
}
