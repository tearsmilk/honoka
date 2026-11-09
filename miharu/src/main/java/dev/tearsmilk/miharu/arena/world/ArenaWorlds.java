package dev.tearsmilk.miharu.arena.world;

import dev.tearsmilk.miharu.arena.Arena;
import dev.tearsmilk.miharu.arena.ArenaModule;
import net.kyori.adventure.util.TriState;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Создаёт временный пустой мир под игру, вставляет туда схематику арены
 * и удаляет мир (выгрузка + папка) через delete-delay-seconds после конца.
 */
public final class ArenaWorlds {

    private final ArenaModule module;
    private final AtomicInteger counter = new AtomicInteger();

    public ArenaWorlds(ArenaModule module) {
        this.module = module;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = module.config().getConfigurationSection("instance");
        return s != null ? s : module.config().createSection("instance");
    }

    public String gamePrefix() {
        return cfg().getString("world-prefix", "miharu_game_");
    }

    public String editPrefix() {
        return cfg().getString("edit-prefix", "miharu_edit_");
    }

    public boolean isManaged(World world) {
        String n = world.getName();
        return n.startsWith(gamePrefix()) || n.startsWith(editPrefix());
    }

    public Location pasteOrigin(World world) {
        return new Location(world, cfg().getInt("paste-origin.x", 0), cfg().getInt("paste-origin.y", 100), cfg().getInt("paste-origin.z", 0));
    }

    /** Новый пустой мир с правилами из конфига. Вызывать в основном потоке. */
    public World createWorld(String name) {
        Location o = pasteOrigin(null);
        WorldCreator wc = new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .type(WorldType.NORMAL)
                .generateStructures(false)
                .generator(new VoidGenerator(o.getX() + 0.5, o.getY() + 1, o.getZ() + 0.5))
                .keepSpawnLoaded(TriState.FALSE);
        World world = wc.createWorld();
        if (world == null) {
            throw new IllegalStateException("Не удалось создать мир " + name);
        }
        world.setAutoSave(false);
        world.setKeepSpawnInMemory(false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.SPAWN_MONSTERS, false);
        world.setGameRule(GameRules.SHOW_DEATH_MESSAGES, false);
        world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        world.setGameRule(GameRules.KEEP_INVENTORY, true);
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRules.MOB_GRIEFING, false);
        world.setGameRule(GameRules.SPAWN_PATROLS, false);
        world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        world.setGameRule(GameRules.SPAWN_PHANTOMS, false);
        world.setGameRule(GameRules.LOCATOR_BAR, false);
        applyGameRules(world, cfg().getConfigurationSection("gamerules"));
        world.setTime(cfg().getLong("time", 6000));
        world.setStorm(false);
        world.setThundering(false);
        try {
            world.setDifficulty(Difficulty.valueOf(cfg().getString("difficulty", "NORMAL").toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            world.setDifficulty(Difficulty.NORMAL);
        }
        return world;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyGameRules(World world, ConfigurationSection rules) {
        if (rules == null) {
            return;
        }
        for (String key : rules.getKeys(false)) {
            NamespacedKey nk = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
            GameRule rule = nk == null ? null : Registry.GAME_RULE.get(nk);
            if (rule == null) {
                rule = GameRule.getByName(key);
            }
            if (rule == null) {
                module.plugin().getLogger().warning("Неизвестное правило игры: " + key);
                continue;
            }
            if (rule.getType() == Boolean.class) {
                world.setGameRule(rule, rules.getBoolean(key));
            } else if (rule.getType() == Integer.class) {
                world.setGameRule(rule, rules.getInt(key));
            }
        }
    }

    /** Мир + вставленная арена. */
    public ArenaInstance create(Arena arena) throws Exception {
        String name = gamePrefix() + counter.incrementAndGet() + "_" + Long.toString(System.currentTimeMillis() % 100000, 36);
        return createIn(arena, name);
    }

    public ArenaInstance createIn(Arena arena, String worldName) throws Exception {
        File file = new File(module.arenas().schematicsDir(), arena.schematic());
        if (!file.exists()) {
            throw new IllegalStateException("Нет файла схематики " + file.getName());
        }
        World world = createWorld(worldName);
        Location origin = pasteOrigin(world);
        try {
            module.schematics().paste(file, origin);
        } catch (Exception e) {
            delete(world, 0);
            throw e;
        }
        world.setSpawnLocation(origin.clone().add(0, 1, 0));
        return new ArenaInstance(world, origin, arena);
    }

    public long deleteDelayTicks() {
        return Math.max(0, cfg().getLong("delete-delay-seconds", 5)) * 20L;
    }

    /** Через delayTicks выгружает мир и удаляет папку. Оставшихся игроков переносит в главный мир. */
    public void delete(World world, long delayTicks) {
        Runnable task = () -> {
            if (Bukkit.getWorld(world.getUID()) == null) {
                return;
            }
            World main = Bukkit.getWorlds().getFirst();
            for (Player p : world.getPlayers()) {
                p.teleport(main.getSpawnLocation());
            }
            File folder = world.getWorldFolder();
            if (!Bukkit.unloadWorld(world, false)) {
                module.plugin().getLogger().warning("Не удалось выгрузить мир " + world.getName());
                return;
            }
            if (module.plugin().isEnabled()) {
                Bukkit.getScheduler().runTaskAsynchronously(module.plugin(), () -> deleteFolder(folder.toPath()));
            } else {
                deleteFolder(folder.toPath());
            }
        };
        if (delayTicks <= 0 || !module.plugin().isEnabled()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTaskLater(module.plugin(), task, delayTicks);
        }
    }

    /** На старте сервера удаляет миры, оставшиеся после краша. */
    public void cleanupLeftovers() {
        File container = Bukkit.getWorldContainer();
        File[] dirs = container.listFiles(File::isDirectory);
        if (dirs == null) {
            return;
        }
        for (File dir : dirs) {
            String n = dir.getName();
            if ((n.startsWith(gamePrefix()) || n.startsWith(editPrefix())) && Bukkit.getWorld(n) == null) {
                deleteFolder(dir.toPath());
                module.plugin().getLogger().info("Удалён старый мир игры: " + n);
            }
        }
    }

    private void deleteFolder(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            module.plugin().getLogger().warning("Не удалось удалить " + path + ": " + e.getMessage());
        }
    }
}
