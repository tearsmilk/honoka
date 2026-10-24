package ink.myata.mythicsupport;

import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 1) Яйца призыва: 1 яйцо = 1 спавн мифик-моба (рандом из списка), трата яйца в выживании/приключении.
 * 2) Подавление ванильного спавна перечисленных типов (кроме яиц).
 * 3) Замена ванильного моба мифик-мобом прямо в момент его спавна (replace-vanilla):
 *    ванильный триггер сохраняется целиком, подменяется только сущность.
 */
public final class EggSuppressListener implements Listener {

    private final MythicSupportPlugin plugin;

    public EggSuppressListener(MythicSupportPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- Подавление ванильного спавна ----
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVanillaSpawn(CreatureSpawnEvent event) {
        SpawnReason reason = event.getSpawnReason();
        // Наши спавны (CUSTOM) и яйца не трогаем
        if (reason == SpawnReason.CUSTOM
                || reason == SpawnReason.SPAWNER_EGG
                || reason == SpawnReason.DISPENSE_EGG) {
            return;
        }
        // Замена ванильного моба на мифик-моба в момент его спавна (replace-vanilla).
        // Весь ванильный триггер остаётся родным: например, Варден появляется по счётчику крикунов,
        // а мы лишь подменяем сущность в той же точке.
        List<String> replacement = replaceMobsFor(event.getEntityType());
        if (!replacement.isEmpty()) {
            event.setCancelled(true);
            String mobName = replacement.size() == 1
                    ? replacement.get(0)
                    : replacement.get(ThreadLocalRandom.current().nextInt(replacement.size()));
            spawnMythic(mobName, event.getLocation());
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("Замена " + event.getEntityType() + " (" + reason + ") -> " + mobName
                        + " в " + event.getLocation().getWorld().getName() + " [" + event.getLocation().getBlockX()
                        + "," + event.getLocation().getBlockY() + "," + event.getLocation().getBlockZ() + "]");
            }
            return;
        }

        Set<EntityType> suppress = loadSuppress();
        if (suppress.contains(event.getEntityType())) {
            event.setCancelled(true);
            return;
        }

        // Запрет ванильного спавна только в указанных типах миров (suppress-vanilla-in-environments)
        if (suppressedInEnvironment(event.getEntityType(), event.getLocation().getWorld().getEnvironment())) {
            event.setCancelled(true);
        }
    }

    private boolean suppressedInEnvironment(EntityType type, World.Environment env) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("suppress-vanilla-in-environments");
        if (section == null) return false;
        for (String s : section.getStringList(type.name())) {
            if (s != null && s.trim().equalsIgnoreCase(env.name())) return true;
        }
        return false;
    }

    // ---- Яйца призыва ----
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEggUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null) return;
        Material mat = item.getType();
        if (!mat.name().endsWith("_SPAWN_EGG")) return;

        EntityType type = spawnEggEntityType(mat);
        if (type == null) return;

        List<String> candidates = eggMobsFor(type);
        if (candidates.isEmpty()) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        event.setCancelled(true);

        Location loc = clicked.getRelative(event.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        String mobName = candidates.size() == 1
                ? candidates.get(0)
                : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));

        spawnMythic(mobName, loc);

        Player p = event.getPlayer();
        GameMode gm = p.getGameMode();
        if (gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE) {
            item.setAmount(item.getAmount() - 1);
        }
    }

    private Set<EntityType> loadSuppress() {
        Set<EntityType> set = EnumSet.noneOf(EntityType.class);
        for (String s : plugin.getConfig().getStringList("suppress-vanilla")) {
            try {
                set.add(EntityType.valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Неизвестный тип в suppress-vanilla: " + s);
            }
        }
        return set;
    }

    private List<String> replaceMobsFor(EntityType type) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("replace-vanilla");
        if (section == null) return List.of();
        List<String> mobs = new ArrayList<>(section.getStringList(type.name()));
        mobs.removeIf(s -> s == null || s.isBlank());
        return mobs;
    }

    private List<String> eggMobsFor(EntityType type) {
        FileConfiguration cfg = plugin.getConfig();
        ConfigurationSection section = cfg.getConfigurationSection("egg-replacements");
        if (section == null) return List.of();
        List<String> mobs = new ArrayList<>(section.getStringList(type.name()));
        mobs.removeIf(s -> s == null || s.isBlank());
        return mobs;
    }

    private void spawnMythic(String mobName, Location loc) {
        MythicBukkit mythic = MythicBukkit.inst();
        if (mythic == null) return;
        double level = plugin.getConfig().getDouble("level", 1);
        Optional<MythicMob> opt = mythic.getMobManager().getMythicMob(mobName);
        if (opt.isEmpty()) {
            plugin.getLogger().warning("MythicMob '" + mobName + "' не найден. Проверь egg-replacements в config.yml");
            return;
        }
        opt.get().spawn(BukkitAdapter.adapt(loc), level);
    }

    private EntityType spawnEggEntityType(Material eggMaterial) {
        String name = eggMaterial.name();
        if (!name.endsWith("_SPAWN_EGG")) return null;
        String typeName = name.substring(0, name.length() - "_SPAWN_EGG".length());
        try {
            return EntityType.valueOf(typeName);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
