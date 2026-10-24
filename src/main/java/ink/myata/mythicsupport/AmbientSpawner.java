package ink.myata.mythicsupport;

import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Фоновый спавнер: держит заданное число мобов в радиусе вокруг каждого игрока
 * и удаляет их через despawn-after тиков после того, как рядом не осталось игроков.
 */
public final class AmbientSpawner extends BukkitRunnable {

    private final MythicSupportPlugin plugin;

    // UUID наших мобов -> тик, когда рядом последний раз был игрок
    private final Map<UUID, Long> tracked = new HashMap<>();

    public AmbientSpawner(MythicSupportPlugin plugin) {
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
        int maxLight = Math.min(15, Math.max(0, cfg.getInt("ambient-spawner.max-light", 2)));
        int clearance = Math.min(3, Math.max(0, cfg.getInt("ambient-spawner.spawn-clearance", 1)));
        int maxPerCycle = Math.max(1, cfg.getInt("ambient-spawner.max-spawn-per-cycle", 5));
        long despawnAfter = Math.max(0, cfg.getLong("ambient-spawner.despawn-after", 200));
        double level = cfg.getDouble("level", 1);
        boolean debug = cfg.getBoolean("debug", false);
        List<String> worlds = cfg.getStringList("worlds");
        Set<World.Environment> environments = loadEnvironments(cfg.getStringList("ambient-spawner.environments"));
        List<String> mobs = cfg.getStringList("ambient-spawner.mobs");
        if (mobs.isEmpty()) return;
        Map<String, SpawnRule> rules = loadRules(cfg.getConfigurationSection("ambient-spawner.mob-rules"));

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
            if (!environments.contains(p.getWorld().getEnvironment())) continue;

            int near = countNearAndAdopt(p, radiusBlocks, now);
            int deficit = count - near;
            if (deficit <= 0) continue;

            int toSpawn = Math.min(deficit, maxPerCycle);
            for (int i = 0; i < toSpawn; i++) {
                Location loc = findSpawnLocation(p, radiusBlocks, minDistance, maxLight, clearance);
                if (loc == null) continue;
                List<String> candidates = allowedAt(mobs, rules, loc);
                if (candidates.isEmpty()) continue;
                spawnOne(candidates, loc, level, debug);
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

    private Location findSpawnLocation(Player player, int radiusBlocks, int minDistance, int maxLight, int clearance) {
        World w = player.getWorld();
        Location pl = player.getLocation();
        for (int attempt = 0; attempt < 24; attempt++) {
            double ang = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            double dist = minDistance + ThreadLocalRandom.current().nextDouble() * (radiusBlocks - minDistance);
            int x = (int) Math.floor(pl.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(pl.getZ() + Math.sin(ang) * dist);

            // Не грузим невыгруженные чанки
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;

            Integer y = findStandableY(w, x, pl.getBlockY(), z, maxLight, clearance);
            if (y != null) {
                return new Location(w, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    // Ищет пригодную для спавна высоту рядом с уровнем игрока (поддержка и поверхности, и пещер).
    // Точка подходит только если там темно: свет (небо с поправкой на время суток и блочные источники) <= maxLight.
    private Integer findStandableY(World w, int x, int baseY, int z, int maxLight, int clearance) {
        int min = w.getMinHeight() + 1;
        int max = w.getMaxHeight() - 2;
        for (int dy = 0; dy <= 12; dy++) {
            for (int s = 0; s < (dy == 0 ? 1 : 2); s++) {
                int y = baseY + (s == 0 ? dy : -dy);
                if (y < min || y > max) continue;
                Block below = w.getBlockAt(x, y - 1, z);
                Block feet = w.getBlockAt(x, y, z);
                Block head = w.getBlockAt(x, y + 1, z);
                if (below.getType().isSolid() && feet.isPassable() && head.isPassable()
                        && !isWet(below) && !isWet(feet) && !isWet(head)
                        && isDarkEnough(feet, maxLight)
                        && hasClearance(w, x, y, z, clearance)) {
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

    // Вокруг точки спавна должно быть свободно: (2*clearance+1)^2 блоков на уровне ног и головы.
    // Нужно для мобов с широким хитбоксом/моделью (пауки), иначе они задыхаются в стенах.
    private boolean hasClearance(World w, int x, int y, int z, int clearance) {
        if (clearance <= 0) return true;
        for (int dx = -clearance; dx <= clearance; dx++) {
            for (int dz = -clearance; dz <= clearance; dz++) {
                if (dx == 0 && dz == 0) continue;
                Block f = w.getBlockAt(x + dx, y, z + dz);
                Block h = w.getBlockAt(x + dx, y + 1, z + dz);
                if (!f.isPassable() || !h.isPassable() || isWet(f) || isWet(h)) return false;
            }
        }
        return true;
    }

    // Вода/лава/залитый водой блок: наши мобы не спавнятся в воде и под водой
    private boolean isWet(Block b) {
        if (b.isLiquid()) return true;
        return b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged();
    }

    // getLightLevel() = max(свет от блоков, свет неба с учётом времени суток), как у ванильного спавна.
    // Отдельно проверяем свет от блоков, чтобы факел/фонарь/лампа блокировали спавн и ночью.
    private boolean isDarkEnough(Block feet, int maxLight) {
        if (maxLight >= 15) return true;
        return feet.getLightFromBlocks() <= maxLight && feet.getLightLevel() <= maxLight;
    }

    // ---- Правила спавна по мобам (ambient-spawner.mob-rules) ----

    /** Условия, при которых моб может появиться в точке. Все заданные условия должны выполняться. */
    private static final class SpawnRule {
        Boolean underground;   // true = только без неба над головой (пещеры/данжи), false = только под открытым небом
        Integer minY, maxY;    // диапазон высот
        Set<String> worlds;    // имена миров
        Set<String> biomes;    // ключи биомов (DEEP_DARK, LUSH_CAVES, ...)

        boolean matches(Location loc) {
            World w = loc.getWorld();
            if (w == null) return false;
            int y = loc.getBlockY();
            if (minY != null && y < minY) return false;
            if (maxY != null && y > maxY) return false;
            if (worlds != null && !worlds.contains(w.getName().toUpperCase(Locale.ROOT))) return false;
            if (underground != null) {
                Block b = loc.getBlock();
                boolean isUnderground = b.getLightFromSky() == 0
                        && w.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ()) > y;
                if (underground != isUnderground) return false;
            }
            if (biomes != null) {
                String biome = loc.getBlock().getBiome().getKey().getKey().toUpperCase(Locale.ROOT);
                if (!biomes.contains(biome)) return false;
            }
            return true;
        }
    }

    private Map<String, SpawnRule> loadRules(ConfigurationSection section) {
        Map<String, SpawnRule> rules = new HashMap<>();
        if (section == null) return rules;
        for (String mob : section.getKeys(false)) {
            ConfigurationSection rs = section.getConfigurationSection(mob);
            if (rs == null) continue;
            SpawnRule r = new SpawnRule();
            if (rs.contains("underground")) r.underground = rs.getBoolean("underground");
            if (rs.contains("min-y")) r.minY = rs.getInt("min-y");
            if (rs.contains("max-y")) r.maxY = rs.getInt("max-y");
            if (rs.contains("worlds")) r.worlds = toSet(rs.getStringList("worlds"));
            if (rs.contains("biomes")) r.biomes = toSet(rs.getStringList("biomes"));
            rules.put(mob, r);
        }
        return rules;
    }

    private Set<String> toSet(List<String> list) {
        Set<String> set = new HashSet<>();
        for (String s : list) if (s != null && !s.isBlank()) set.add(s.trim().toUpperCase(Locale.ROOT));
        return set;
    }

    // Мобы из пула, которым разрешено появиться в этой точке (без правила = разрешено везде)
    private List<String> allowedAt(List<String> mobs, Map<String, SpawnRule> rules, Location loc) {
        List<String> out = new ArrayList<>(mobs.size());
        for (String mob : mobs) {
            SpawnRule r = rules.get(mob);
            if (r == null || r.matches(loc)) out.add(mob);
        }
        return out;
    }

    private boolean isOurMob(Entity e) {
        return e.getPersistentDataContainer().has(plugin.getAmbientKey(), PersistentDataType.BYTE);
    }

    // Типы миров, где работает спавнер: NORMAL (обычный), NETHER, THE_END. По умолчанию только NORMAL.
    private Set<World.Environment> loadEnvironments(List<String> list) {
        Set<World.Environment> set = new HashSet<>();
        if (list == null || list.isEmpty()) {
            set.add(World.Environment.NORMAL);
            return set;
        }
        for (String s : list) {
            try {
                set.add(World.Environment.valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Неизвестный тип мира в ambient-spawner.environments: " + s + " (NORMAL, NETHER, THE_END)");
            }
        }
        return set;
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
