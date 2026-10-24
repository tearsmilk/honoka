package ink.myata.mythicsupport;

import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class MythicSupportPlugin extends JavaPlugin {

    private NamespacedKey ambientKey;
    private AmbientSpawner spawner;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ambientKey = new NamespacedKey(this, "ambient");

        getServer().getPluginManager().registerEvents(new EggSuppressListener(this), this);

        startSpawner();
        getLogger().info("MythicSupport enabled.");
    }

    @Override
    public void onDisable() {
        stopSpawner();
        getLogger().info("MythicSupport disabled.");
    }

    public NamespacedKey getAmbientKey() {
        return ambientKey;
    }

    private void startSpawner() {
        spawner = new AmbientSpawner(this);
        long interval = Math.max(1, getConfig().getLong("ambient-spawner.tick-interval", 20));
        spawner.runTaskTimer(this, interval, interval);
    }

    private void stopSpawner() {
        if (spawner != null) {
            try {
                spawner.cancel();
            } catch (IllegalStateException ignored) {
            }
            spawner = null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mythicsupport.admin")) {
            sender.sendMessage("Нет прав.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            stopSpawner();
            startSpawner();
            sender.sendMessage("MythicSupport: config reloaded.");
            return true;
        }
        sender.sendMessage("Использование: /mythicsupport reload");
        return true;
    }
}
