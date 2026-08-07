package top.miragedge.stellarityoptimizer;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe entity registry that indexes entities by type and tag.
 * Replaces @e[type=xxx,tag=yyy] full-scan with O(1) lookup.
 */
public class EntityRegistry implements Listener {

    private final StellarityOptimizer plugin;

    // type -> set of entities (replaces @e[type=xxx])
    private final ConcurrentHashMap<EntityType, Set<Entity>> byType = new ConcurrentHashMap<>();

    // tag -> set of entities (replaces @e[tag=xxx])
    private final ConcurrentHashMap<String, Set<Entity>> byTag = new ConcurrentHashMap<>();

    // all tracked entities (for counting)
    private final Set<Entity> allEntities = ConcurrentHashMap.newKeySet();

    // Stats
    private final AtomicInteger totalTracked = new AtomicInteger(0);

    public EntityRegistry(StellarityOptimizer plugin) {
        this.plugin = plugin;
        // Initial scan of all loaded entities
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            int count = 0;
            for (var world : plugin.getServer().getWorlds()) {
                for (var entity : world.getEntities()) {
                    register(entity);
                    count++;
                }
            }
            plugin.getLogger().info("EntityRegistry initialized with " + count + " entities.");
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (var entity : event.getEntities()) {
            register(entity);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (var entity : event.getEntities()) {
            unregister(entity);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        // EntitySpawnEvent fires for ALL entities (marker, item, trident, item_display, mobs, etc.)
        // CreatureSpawnEvent only fires for Mob entities - misses markers/items/displays
        // which the datapack uses heavily for weapon effects (prismatic punch, etc.)
        register(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        unregister(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        unregister(event.getEntity());
    }

    // Paper-specific event: fires for ALL entities including non-living (marker, item_display, etc.)
    // when they are removed from the world for ANY reason (kill, despawn, chunk unload).
    // This is the critical cleanup path for non-living entities that don't trigger EntityDeathEvent.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoveFromWorld(EntityRemoveFromWorldEvent event) {
        unregister(event.getEntity());
    }

    public void register(Entity entity) {
        if (entity == null) return;

        allEntities.add(entity);
        totalTracked.set(allEntities.size());

        // Index by type
        byType.computeIfAbsent(entity.getType(), k -> ConcurrentHashMap.newKeySet()).add(entity);

        // Index by tags
        for (String tag : entity.getScoreboardTags()) {
            byTag.computeIfAbsent(tag, k -> ConcurrentHashMap.newKeySet()).add(entity);
        }
    }

    public void unregister(Entity entity) {
        if (entity == null) return;

        try {
            allEntities.remove(entity);
            totalTracked.set(allEntities.size());

            // Remove from type index
            Set<Entity> typeSet = byType.get(entity.getType());
            if (typeSet != null) {
                typeSet.remove(entity);
            }

            // Remove from tag index - wrap in try-catch because getScoreboardTags()
            // may fail on dead/removed entities in some Paper versions
            try {
                for (String tag : entity.getScoreboardTags()) {
                    Set<Entity> tagSet = byTag.get(tag);
                    if (tagSet != null) {
                        tagSet.remove(entity);
                    }
                }
            } catch (Exception ignored) {
                // Entity may already be invalid, tags unavailable
            }
        } catch (Exception ignored) {
            // Best-effort cleanup
        }
    }

    /**
     * Get all entities of a specific type. O(1) lookup.
     */
    public Set<Entity> getByType(EntityType type) {
        return byType.getOrDefault(type, Collections.emptySet());
    }

    /**
     * Get all entities with a specific tag. O(1) lookup.
     */
    public Set<Entity> getByTag(String tag) {
        return byTag.getOrDefault(tag, Collections.emptySet());
    }

    /**
     * Get entities that have BOTH the specified type AND tag.
     */
    public Set<Entity> getByTypeAndTag(EntityType type, String tag) {
        Set<Entity> typeSet = byType.get(type);
        if (typeSet == null || typeSet.isEmpty()) return Collections.emptySet();

        Set<Entity> tagSet = byTag.get(tag);
        if (tagSet == null || tagSet.isEmpty()) return Collections.emptySet();

        Set<Entity> result = new LinkedHashSet<>();
        // Iterate the smaller set
        Set<Entity> smaller = typeSet.size() <= tagSet.size() ? typeSet : tagSet;
        for (Entity e : smaller) {
            if (smaller == typeSet) {
                if (tagSet.contains(e)) result.add(e);
            } else {
                if (typeSet.contains(e)) result.add(e);
            }
        }
        return result;
    }

    /**
     * Get entities of a type with a tag, within distance of a location.
     * Uses squared distance for performance.
     */
    public Set<Entity> getNearbyByTypeAndTag(org.bukkit.Location loc, double distance, EntityType type, String tag) {
        double distSq = distance * distance;
        Set<Entity> result = new LinkedHashSet<>();
        for (Entity e : getByTypeAndTag(type, tag)) {
            if (e.getWorld().equals(loc.getWorld()) && e.getLocation().distanceSquared(loc) <= distSq) {
                result.add(e);
            }
        }
        return result;
    }

    /**
     * Check if any entity of type+tag exists within distance of location.
     * Short-circuits on first match.
     */
    public boolean hasNearbyByTypeAndTag(org.bukkit.Location loc, double distance, EntityType type, String tag) {
        double distSq = distance * distance;
        for (Entity e : getByTypeAndTag(type, tag)) {
            if (e.getWorld().equals(loc.getWorld()) && e.getLocation().distanceSquared(loc) <= distSq) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if any entity of type+tag exists (anywhere).
     */
    public boolean hasAny(EntityType type, String tag) {
        Set<Entity> typeSet = byType.get(type);
        if (typeSet == null || typeSet.isEmpty()) return false;

        Set<Entity> tagSet = byTag.get(tag);
        if (tagSet == null || tagSet.isEmpty()) return false;

        // Check intersection
        Set<Entity> smaller = typeSet.size() <= tagSet.size() ? typeSet : tagSet;
        for (Entity e : smaller) {
            if (smaller == typeSet) {
                if (tagSet.contains(e)) return true;
            } else {
                if (typeSet.contains(e)) return true;
            }
        }
        return false;
    }

    /**
     * Get entities by type that do NOT have a specific tag.
     */
    public Set<Entity> getByTypeWithoutTag(EntityType type, String tag) {
        Set<Entity> typeSet = byType.get(type);
        if (typeSet == null || typeSet.isEmpty()) return Collections.emptySet();

        Set<Entity> tagSet = byTag.getOrDefault(tag, Collections.emptySet());
        Set<Entity> result = new LinkedHashSet<>();
        for (Entity e : typeSet) {
            if (!tagSet.contains(e)) result.add(e);
        }
        return result;
    }

    /**
     * Get entities by type that do NOT have any of the specified tags.
     */
    public Set<Entity> getByTypeWithoutTags(EntityType type, String... tags) {
        Set<Entity> typeSet = byType.get(type);
        if (typeSet == null || typeSet.isEmpty()) return Collections.emptySet();

        List<Set<Entity>> excludeSets = new ArrayList<>();
        for (String tag : tags) {
            Set<Entity> s = byTag.get(tag);
            if (s != null && !s.isEmpty()) excludeSets.add(s);
        }

        Set<Entity> result = new LinkedHashSet<>();
        outer:
        for (Entity e : typeSet) {
            for (Set<Entity> exclude : excludeSets) {
                if (exclude.contains(e)) continue outer;
            }
            result.add(e);
        }
        return result;
    }

    public int getTotalTracked() {
        return totalTracked.get();
    }

    public int getTypeCount(EntityType type) {
        Set<Entity> s = byType.get(type);
        return s != null ? s.size() : 0;
    }

    public int getTagCount(String tag) {
        Set<Entity> s = byTag.get(tag);
        return s != null ? s.size() : 0;
    }

    /**
     * Get all type-indexed sets (for iteration without world.getEntities).
     */
    public java.util.Collection<Set<Entity>> getByTypeValues() {
        return byType.values();
    }
}
