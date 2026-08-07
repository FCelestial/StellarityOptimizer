package top.miragedge.stellarityoptimizer;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * Manages module enable/disable from config.yml.
 */
public class ModuleConfig {

    private final StellarityOptimizer plugin;
    private boolean debug;

    private static final List<String> ALL_MODULES = List.of(
            "pixies", "empress_of_light", "dragon", "flesh_piglin",
            "spawn_egg", "illusioner", "shulking", "animal_convert",
            "shulking_bossbar", "shulking_ray",
            "marker_loop", "item_loop", "end_city_crystal", "exit_portal",
            "kohara_status", "kohara_particles", "kohara_items", "kohara_heal",
            "dragonblade", "projectile_loop", "phantom_item_frame",
            "sandstorm_trident", "fluffy_hammer"
    );

    public ModuleConfig(StellarityOptimizer plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        // Reload from file
        plugin.reloadConfig();
        debug = plugin.getConfig().getBoolean("debug", false);
    }

    public void reload() {
        load();
    }

    public boolean isEnabled(String module) {
        return plugin.getConfig().getBoolean("modules." + module, true);
    }

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
        plugin.getConfig().set("debug", debug);
        plugin.saveConfig();
    }

    public List<String> getAllModules() {
        return ALL_MODULES;
    }
}
