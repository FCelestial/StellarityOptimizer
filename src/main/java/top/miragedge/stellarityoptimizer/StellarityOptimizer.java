package top.miragedge.stellarityoptimizer;

import org.bukkit.plugin.java.JavaPlugin;

public class StellarityOptimizer extends JavaPlugin {

    private EntityRegistry entityRegistry;
    private PlayerProximityCache proximityCache;
    private TickDispatcher tickDispatcher;
    private TimedLoopDispatcher timedLoopDispatcher;
    private ModuleConfig moduleConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        moduleConfig = new ModuleConfig(this);
        entityRegistry = new EntityRegistry(this);
        proximityCache = new PlayerProximityCache(this, entityRegistry);
        tickDispatcher = new TickDispatcher(this, entityRegistry, proximityCache, moduleConfig);

        // Register event listeners
        getServer().getPluginManager().registerEvents(entityRegistry, this);

        // Start cache update task
        int cacheInterval = getConfig().getInt("cache-tick-interval", 5);
        proximityCache.start(cacheInterval);

        // Start tick dispatcher
        tickDispatcher.start();

        // Start timed loop dispatcher (replaces datapack schedule-function self-loops)
        timedLoopDispatcher = new TimedLoopDispatcher(this, entityRegistry);
        timedLoopDispatcher.start();

        // Fix: Clean up residual creative_shock effects on online players
        // Previous versions had a bug where kohara_status wasn't dispatched correctly,
        // leaving players with permanent block_break_speed modifier (-1 = can't break blocks).
        // This runs once on enable to remove any stuck effects.
        getServer().getScheduler().runTaskLater(this, () -> {
            for (var p : getServer().getOnlinePlayers()) {
                if (p.getScoreboardTags().contains("stellarity.creative_shock")) {
                    getLogger().info("Cleaning up residual creative_shock on player: " + p.getName());
                    getServer().dispatchCommand(getServer().getConsoleSender(),
                        "execute as " + p.getUniqueId() + " run function stellarity:util/status_effects/creative_shock/remove");
                }
            }
        }, 40L); // 2 seconds after enable

        // Register command
        getCommand("stellarityoptimizer").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("stellarityoptimizer.admin")) {
                sender.sendMessage("cYou don't have permission.");
                return true;
            }
            if (args.length == 0) {
                sendStatus(sender);
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "reload" -> {
                    reloadConfig();
                    moduleConfig.reload();
                    int newInterval = getConfig().getInt("cache-tick-interval", 5);
                    proximityCache.restart(newInterval);
                    sender.sendMessage("aStellarityOptimizer reloaded.");
                }
                case "status" -> sendStatus(sender);
                case "debug" -> {
                    boolean debug = !moduleConfig.isDebug();
                    moduleConfig.setDebug(debug);
                    sender.sendMessage("aDebug mode: " + (debug ? "ON" : "OFF"));
                }
                default -> sender.sendMessage("eUsage: /so <reload|status|debug>");
            }
            return true;
        });

        getLogger().info("StellarityOptimizer enabled! Replacing @e scans with indexed lookups.");
    }

    private void sendStatus(org.bukkit.command.CommandSender sender) {
        sender.sendMessage("6=== StellarityOptimizer Status ===");
        sender.sendMessage("fTracked entities: a" + entityRegistry.getTotalTracked());
        sender.sendMessage("fCache interval: a" + getConfig().getInt("cache-tick-interval", 5) + " ticks");
        sender.sendMessage("fProximity distance: a" + getConfig().getInt("proximity-distance", 256) + " blocks");
        sender.sendMessage("fDebug: " + (moduleConfig.isDebug() ? "aON" : "cOFF"));
        sender.sendMessage("6--- Modules ---");
        for (String mod : moduleConfig.getAllModules()) {
            sender.sendMessage("f" + mod + ": " + (moduleConfig.isEnabled(mod) ? "aON" : "cOFF"));
        }
    }

    @Override
    public void onDisable() {
        if (timedLoopDispatcher != null) timedLoopDispatcher.stop();
        if (proximityCache != null) proximityCache.stop();
        if (tickDispatcher != null) tickDispatcher.stop();
        getLogger().info("StellarityOptimizer disabled.");
    }
}
