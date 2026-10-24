package ink.myata.mythsupport;

import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Фоновый спавнер: держит заданное число мобов в радиусе вокруг каждого игрока
 * и удаляет их через despawn-after тиков после того, как рядом не осталось игроков.
 */
public final class AmbientSpawner extends BukkitRunnable {

    private final MythSupportPlugin plugin;

    // UUID наших мобов -> тик, когда рядом последний раз был игрок
    private final Map<UUID, Long> tracked = new HashMap<>();

    public AmbientSpawner(MythSupportPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("ambient-spawner.enabled", true);
    }

    @Override
    public void run() {
        if (!enabled()) return;

        var cfg = plugin.getConfig();
        int radiusBlocks = Math.max(16, cfg.getInt("ambient-spawner.radius-chunks", 4) * 16);
        int count = Math.max(0, cfg.getInt("ambient-spawner.count", 20));
        int minDistance = Math.max(0, cfg.getInt("ambient-spawner.min-distance", 12));
        int maxPerCycle = Math.max(1, cfg.getInt("ambient-spawner.max-spawn-per-cycle", 5));
        long despawnAfter = Math.max(0, cfg.getLong("ambient-spawner.despawn-after", 200));
        double level = cfg.getDouble("level", 1);
        boolean debug = cfg.getBoolean("debug", false);
        List<String> worlds = cfg.getStringList("worlds");
        List<String> mobs = cfg.getStringList("ambient-spawner.mobs");
        if (mobs.isEmpty()) return;

        long now = Bukkit.getCurrentTick();

        // 1) Деспавн/очистка
        Iterator<Map.Entry<UUID, Long>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            Entity ent = Bukkit.getEntity(entry.getKey());
            if (ent == null) {
                // выгружен или уже удалён: если давно не видели — забываем (MythicMobs Despawn добьёт)
                if (now - entry.getValue() >= despawnAfter) it.remove();
                continue;
            }
            if (ent.isDead() || !ent.isValid()) {
                it.remove();
                continue;
            }
            if (anyPlayerWithin(ent.getLocation(), radiusBlocks)) {
                entry.setValue(now);
            } else if (now - entry.getValue() >= despawnAfter) {
                ent.remove();
                it.remove();
                if (debug) plugin.getLogger().info("Деспавн моба " + entry.getKey());
            }
        }

        // 2) Спавн до count вокруг каждого игрока
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!worldAllowed(p.getWorld(), worlds)) continue;

            int near = countNearAndAdopt(p, radiusBlocks, now);
            int deficit = count - near;
            if (deficit <= 0) continue;

            int toSpawn = Math.min(deficit, maxPerCycle);
            for (int i = 0; i < toSpawn; i++) {
                Location loc = findSpawnLocation(p, radiusBlocks, minDistance);
                if (loc == null) continue;
                spawnOne(mobs, loc, level, debug);
            }
        }
    }

    // Считает наших мобов в радиусе игрока и заодно «усыновляет» помеченных, но не отслеживаемых
    private int countNearAndAdopt(Player player, int radiusBlocks, long now) {
        int count = 0;
        List<Entity> nearby = player.getNearbyEntities(radiusBlocks, 256, radiusBlocks);
        double r2 = (double) radiusBlocks * radiusBlocks;
        Location pl = player.getLocation();
        for (Entity e : nearby) {
            if (!isOurMob(e)) continue;
            if (horizontalDistSq(pl, e.getLocation()) > r2) continue;
            count++;
            tracked.putIfAbsent(e.getUniqueId(), now);
        }
        return count;
    }

    private boolean anyPlayerWithin(Location loc, int radiusBlocks) {
        World w = loc.getWorld();
        if (w == null) return false;
        double r2 = (double) radiusBlocks * radiusBlocks;
        for (Player p : w.getPlayers()) {
            if (horizontalDistSq(p.getLocation(), loc) <= r2) return true;
        }
        return false;
    }

    private Location findSpawnLocation(Player player, int radiusBlocks, int minDistance) {
        World w = player.getWorld();
        Location pl = player.getLocation();
        for (int attempt = 0; attempt < 24; attempt++) {
            double ang = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            double dist = minDistance + ThreadLocalRandom.current().nextDouble() * (radiusBlocks - minDistance);
            int x = (int) Math.floor(pl.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(pl.getZ() + Math.sin(ang) * dist);

            // Не грузим невыгруженные чанки
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;

            Integer y = findStandableY(w, x, pl.getBlockY(), z);
            if (y != null) {
                return new Location(w, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    // Ищет пригодную для спавна высоту рядом с уровнем игрока (поддержка и поверхности, и пещер)
    private Integer findStandableY(World w, int x, int baseY, int z) {
        int min = w.getMinHeight() + 1;
        int max = w.getMaxHeight() - 2;
        for (int dy = 0; dy <= 12; dy++) {
            for (int s = 0; s < (dy == 0 ? 1 : 2); s++) {
                int y = baseY + (s == 0 ? dy : -dy);
                if (y < min || y > max) continue;
                Block below = w.getBlockAt(x, y - 1, z);
                Block feet = w.getBlockAt(x, y, z);
                Block head = w.getBlockAt(x, y + 1, z);
                if (below.getType().isSolid() && feet.isPassable() && head.isPassable()) {
                    return y;
                }
            }
        }
        return null;
    }

    private void spawnOne(List<String> mobs, Location loc, double level, boolean debug) {
        MythicBukkit mythic = MythicBukkit.inst();
        if (mythic == null) return;

        String name = mobs.size() == 1
                ? mobs.get(0)
                : mobs.get(ThreadLocalRandom.current().nextInt(mobs.size()));

        Optional<MythicMob> opt = mythic.getMobManager().getMythicMob(name);
        if (opt.isEmpty()) {
            plugin.getLogger().warning("MythicMob '" + name + "' не найден. Проверь ambient-spawner.mobs в config.yml");
            return;
        }

        ActiveMob am = opt.get().spawn(BukkitAdapter.adapt(loc), level);
        if (am == null) return;

        Entity entity = am.getEntity().getBukkitEntity();
        if (entity == null) return;

        // Метим наших мобов и берём на учёт
        entity.getPersistentDataContainer().set(plugin.getAmbientKey(), PersistentDataType.BYTE, (byte) 1);
        tracked.put(entity.getUniqueId(), (long) Bukkit.getCurrentTick());

        if (debug) {
            plugin.getLogger().info("Спавн " + name + " в " + loc.getWorld().getName()
                    + " [" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + "]");
        }
    }

    private boolean isOurMob(Entity e) {
        return e.getPersistentDataContainer().has(plugin.getAmbientKey(), PersistentDataType.BYTE);
    }

    private boolean worldAllowed(World w, List<String> worlds) {
        return worlds == null || worlds.isEmpty() || worlds.contains(w.getName());
    }

    private double horizontalDistSq(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
