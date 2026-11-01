package dev.tearsmilk.miharu.game;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.arena.ArenaModule;
import dev.tearsmilk.miharu.arena.world.ArenaInstance;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import dev.tearsmilk.miharu.pvp.PvpModule;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/** Запуск/остановка игр, вход/выход игроков, общий тик. */
public final class GameManager {

    private final GameModule module;
    private final Miharu plugin;
    private final Map<Integer, Game> games = new LinkedHashMap<>();
    private final Map<UUID, Game> byPlayer = new HashMap<>();
    private final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private BukkitTask task;

    GameManager(GameModule module) {
        this.module = module;
        this.plugin = module.plugin();
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void shutdown() {
        if (task != null) {
            task.cancel();
        }
        for (Game game : List.copyOf(games.values())) {
            stop(game, true);
        }
    }

    public Collection<Game> games() {
        return games.values();
    }

    public Game get(int id) {
        return games.get(id);
    }

    public Game of(Player p) {
        return byPlayer.get(p.getUniqueId());
    }

    public boolean inGame(Player p) {
        return byPlayer.containsKey(p.getUniqueId());
    }

    /**
     * Создаёт мир из схематики арены, переносит туда игроков и запускает отсчёт.
     *
     * @return false, если не получилось (сообщение игрокам уже отправлено)
     */
    public boolean start(Game game, List<Player> list) {
        for (Player p : list) {
            if (inGame(p)) {
                plugin.messages().send(p, "game.already-in-game");
                return false;
            }
        }
        ArenaModule arenas = plugin.module(ArenaModule.class);
        ArenaInstance instance;
        try {
            instance = arenas.worlds().create(game.arena());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось создать мир для арены " + game.arena().id(), e);
            list.forEach(p -> plugin.messages().send(p, "game.world-failed", "error", String.valueOf(e.getMessage())));
            return false;
        }
        game.id = ids.incrementAndGet();
        game.instance = instance;
        game.board = new GameBoard(Text.mm(module.config().getString("scoreboard.title", "<b>MIHARU")));
        games.put(game.id, game);
        for (Player p : list) {
            join(game, p);
        }
        try {
            game.onSetup();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Ошибка подготовки игры #" + game.id, t);
            stop(game, true);
            return false;
        }
        refreshBoard(game);
        game.startCountdown();
        return true;
    }

    private void join(Game game, Player p) {
        PlayerSnapshot snap = PlayerSnapshot.capture(p);
        snapshots.put(p.getUniqueId(), snap);
        try {
            snap.save(snapshotFile(p.getUniqueId()));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось сохранить снимок игрока " + p.getName(), e);
        }
        byPlayer.put(p.getUniqueId(), game);
        game.players.add(p);

        p.teleport(game.spawnFor(p));
        resetState(p);
        CombatVersion v = game.combatVersion();
        PvpModule pvp = plugin.module(PvpModule.class);
        if (pvp != null) {
            pvp.combat().force(p, v);
        }
        game.board.show(p);
        module.visibility().update(p);
    }

    private void resetState(Player p) {
        p.getInventory().clear();
        for (PotionEffect e : p.getActivePotionEffects()) {
            p.removePotionEffect(e.getType());
        }
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(5);
        p.setExhaustion(0);
        p.setLevel(0);
        p.setExp(0);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setAllowFlight(false);
        p.setFlying(false);
        try {
            p.setGameMode(GameMode.valueOf(module.config().getString("gamemode", "ADVENTURE").toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            p.setGameMode(GameMode.ADVENTURE);
        }
    }

    /** Игрок покидает игру (/leave или выход). */
    public void leave(Player p, boolean quit) {
        Game game = byPlayer.get(p.getUniqueId());
        if (game == null) {
            return;
        }
        if (game.state() == Game.State.COUNTDOWN || game.state() == Game.State.RUNNING || game.state() == Game.State.STARTING) {
            try {
                game.onPlayerLeave(p, quit);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Ошибка onPlayerLeave", t);
            }
        }
        remove(game, p);
        if (game.players.isEmpty() && game.state() != Game.State.CLOSED) {
            if (game.state() != Game.State.ENDING) {
                game.state = Game.State.ENDING;
                safe(game::onEnd);
            }
            close(game);
        }
    }

    /** Возвращает игрока в исходное состояние и место. */
    private void remove(Game game, Player p) {
        game.players.remove(p);
        byPlayer.remove(p.getUniqueId());
        PvpModule pvp = plugin.module(PvpModule.class);
        if (pvp != null) {
            pvp.combat().force(p, null);
        }
        PlayerSnapshot snap = snapshots.remove(p.getUniqueId());
        Location back = returnLocation(snap);
        p.teleport(back);
        if (snap != null) {
            snap.restore(p);
        } else {
            p.getInventory().clear();
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        snapshotFile(p.getUniqueId()).delete();
        p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        module.visibility().update(p);
        sendToProxy(p);
    }

    private Location returnLocation(PlayerSnapshot snap) {
        ConfigurationSection r = module.config().getConfigurationSection("return");
        String mode = r == null ? "previous" : r.getString("mode", "previous");
        if ("location".equalsIgnoreCase(mode) && r != null) {
            World w = Bukkit.getWorld(r.getString("location.world", "world"));
            if (w != null) {
                return new Location(w, r.getDouble("location.x"), r.getDouble("location.y"), r.getDouble("location.z"),
                        (float) r.getDouble("location.yaw"), (float) r.getDouble("location.pitch"));
            }
        }
        Location prev = snap == null ? null : snap.location();
        if (prev != null && !plugin.module(ArenaModule.class).worlds().isManaged(prev.getWorld())) {
            return prev;
        }
        return Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    /** Режим return.mode: velocity — после игры отправить на сервер-лобби через Velocity/BungeeCord. */
    private void sendToProxy(Player p) {
        ConfigurationSection r = module.config().getConfigurationSection("return");
        if (r == null || !"velocity".equalsIgnoreCase(r.getString("mode")) || !p.isOnline()) {
            return;
        }
        String server = r.getString("velocity-server", "lobby");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(server);
            p.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось отправить " + p.getName() + " на " + server + ": " + e.getMessage());
        }
    }

    /** Завершение игры: через end-delay-seconds игроки возвращаются, мир удаляется через delete-delay. */
    void end(Game game) {
        if (game.state() == Game.State.ENDING || game.state() == Game.State.CLOSED) {
            return;
        }
        game.state = Game.State.ENDING;
        safe(game::onEnd);
        refreshBoard(game);
        long delay = Math.max(0, module.config().getLong("end-delay-seconds", 3)) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player p : List.copyOf(game.players)) {
                remove(game, p);
            }
            close(game);
        }, delay);
    }

    /** Принудительная остановка (админ, выключение сервера). */
    public void stop(Game game, boolean immediate) {
        if (game.state() == Game.State.CLOSED) {
            return;
        }
        if (game.state() != Game.State.ENDING) {
            game.state = Game.State.ENDING;
            safe(game::onEnd);
        }
        for (Player p : List.copyOf(game.players)) {
            plugin.messages().send(p, "game.stopped");
            remove(game, p);
        }
        close(game, immediate);
    }

    private void close(Game game) {
        close(game, false);
    }

    private void close(Game game, boolean immediate) {
        if (game.state() == Game.State.CLOSED) {
            return;
        }
        game.state = Game.State.CLOSED;
        safe(game::onClose);
        games.remove(game.id);
        ArenaModule arenas = plugin.module(ArenaModule.class);
        if (game.instance != null && arenas != null) {
            arenas.worlds().delete(game.instance.world(), immediate ? 0 : arenas.worlds().deleteDelayTicks());
        }
    }

    private void tick() {
        for (Game game : List.copyOf(games.values())) {
            try {
                game.tick();
                if (game.ticks % Math.max(1, module.config().getInt("scoreboard.update-ticks", 10)) == 0) {
                    refreshBoard(game);
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка в игре #" + game.id + ", останавливаю", t);
                stop(game, false);
            }
        }
    }

    void refreshBoard(Game game) {
        if (game.board == null || game.players.isEmpty()) {
            return;
        }
        Player viewer = game.players.getFirst();
        Map<String, Object> ph = game.placeholders(viewer);
        if (module.config().getBoolean("scoreboard.enabled", true)) {
            List<String> raw = module.config().getStringList("scoreboard.modes." + game.mode());
            if (raw.isEmpty()) {
                raw = module.config().getStringList("scoreboard.modes.default");
            }
            List<Component> lines = new ArrayList<>();
            for (String line : raw) {
                lines.add(Text.mm(viewer, line, ph));
            }
            game.board.lines(lines);
        }
        if (module.config().getBoolean("tab.enabled", true)) {
            Component header = Text.mm(viewer, String.join("\n", module.config().getStringList("tab.header")), ph);
            Component footer = Text.mm(viewer, String.join("\n", module.config().getStringList("tab.footer")), ph);
            for (Player p : game.players) {
                p.sendPlayerListHeaderAndFooter(header, footer);
            }
        }
    }

    File snapshotFile(UUID id) {
        return new File(module.folder(), "snapshots/" + id + ".yml");
    }

    /** Если сервер упал посреди игры — вернуть игроку вещи при следующем входе. */
    void restoreAfterCrash(Player p) {
        File f = snapshotFile(p.getUniqueId());
        if (!f.exists() || inGame(p)) {
            return;
        }
        try {
            PlayerSnapshot snap = PlayerSnapshot.load(f);
            Location loc = returnLocation(snap);
            p.teleport(loc);
            snap.restore(p);
            plugin.messages().send(p, "game.restored");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось вернуть вещи " + p.getName(), e);
        }
        f.delete();
    }

    private void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Ошибка в игре", t);
        }
    }
}
