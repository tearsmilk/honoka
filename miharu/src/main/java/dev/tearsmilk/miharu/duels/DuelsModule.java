package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.arena.Arena;
import dev.tearsmilk.miharu.arena.ArenaMode;
import dev.tearsmilk.miharu.arena.ArenaModule;
import dev.tearsmilk.miharu.bots.BotsModule;
import dev.tearsmilk.miharu.core.kit.KitGame;
import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.core.module.MiharuModule;
import dev.tearsmilk.miharu.game.Game;
import dev.tearsmilk.miharu.game.GameModule;
import dev.tearsmilk.miharu.pvp.PvpModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Дуэли 1 на 1 по китам: очередь, раунды, статистика. Киты — modules/duels/kits/. */
public final class DuelsModule extends MiharuModule implements Listener {

    private final DuelKits kits = new DuelKits(this, "kits", "", dev.tearsmilk.miharu.pvp.CombatVersion.LEGACY, DuelKits.LEGACY_DEFAULTS);
    private final DuelKits modernKits = new DuelKits(this, "kits-1.9", "m-", dev.tearsmilk.miharu.pvp.CombatVersion.MODERN, DuelKits.MODERN_DEFAULTS);
    private final List<Consumer<Player>> changeHooks = new ArrayList<>();
    private DuelQueue queue;
    private DuelStats stats;
    private DuelMenus menus;
    private EloStore elo;
    private Ranked ranked;
    private Tiers tiers;
    private StatsMenu statsMenu;
    private BukkitTask task;

    public DuelsModule(Miharu plugin) {
        super(plugin, "duels");
    }

    @Override
    public List<Class<? extends MiharuModule>> dependencies() {
        return List.of(PvpModule.class, ArenaModule.class, GameModule.class);
    }

    @Override
    protected void onEnable() {
        kits.load(plugin.module(PvpModule.class).combat());
        modernKits.load(plugin.module(PvpModule.class).combat());
        stats = new DuelStats(plugin.database());
        stats.createTables();
        elo = new EloStore(this, plugin.database());
        elo.createTables();
        ranked = new Ranked(this);
        tiers = new Tiers(this);
        statsMenu = new StatsMenu(this);
        plugin.registerCommand("tier", "PvP-тиры по режимам", List.of("tiers"), new TierCommand(plugin, this));
        plugin.registerCommand("stats", "Статистика игрока", List.of("stat"), new StatsMenu.Command(this));
        queue = new DuelQueue(this);
        menus = new DuelMenus(this);
        ArenaModule arenas = plugin.module(ArenaModule.class);
        arenas.registerMode(new ArenaMode("duel", "Дуэль", List.of("p1", "p2")));
        arenas.registerMode(new ArenaMode("sumo", "Сумо", List.of("p1", "p2")));
        arenas.registerMode(new ArenaMode("bridge", "Бридж", List.of("p1", "p2", "goal1", "goal2")));
        arenas.registerMode(new ArenaMode("mlg", "МЛГ-Раш", List.of("p1", "p2", "bed1", "bed2")));
        listen(this);
        listen(new KitMechanics(this));
        task = Bukkit.getScheduler().runTaskTimer(plugin, queue::tick, 20L, 20L);
        plugin.registerCommand("duel", "Дуэли по китам", List.of("duels", "kits"), new DuelCommand(plugin, this));
        plugin.registerCommand("ranked", "Рейтинговые дуэли", List.of("rank"), new RankedCommand(plugin, this));
        plugin.registerCommand("elo", "Рейтинг игрока", List.of(), new RankedCommand.EloCommand(plugin, this));
        Bukkit.getOnlinePlayers().forEach(p -> {
            stats.load(p.getUniqueId());
            elo.load(p.getUniqueId());
        });
        plugin.hooks().placeholder("tier", (p, rest) -> {
            if (p == null) {
                return "";
            }
            String kit = rest.isEmpty() ? (ranked.allIds().isEmpty() ? "diamond" : ranked.allIds().getFirst()) : rest;
            Tiers.Tier t = tiers.of(p.getUniqueId(), kit);
            return t == null ? "" : t.id();
        });
        plugin.hooks().placeholder("elo", (p, rest) -> {
            if (p == null) {
                return "0";
            }
            String kit = rest.isEmpty() ? (ranked.allIds().isEmpty() ? "diamond" : ranked.allIds().getFirst()) : rest;
            return String.valueOf(elo.elo(p.getUniqueId(), kit));
        });

        plugin.hooks().placeholder("duel_wins", (p, rest) -> p == null ? "0" : String.valueOf(stats.wins(p.getUniqueId(), rest)));
        plugin.hooks().placeholder("duel_losses", (p, rest) -> p == null ? "0" : String.valueOf(stats.losses(p.getUniqueId(), rest)));
        plugin.hooks().placeholder("queue_kit", (p, rest) -> {
            Player pl = p == null ? null : p.getPlayer();
            DuelQueue.Entry e = pl == null ? null : queue.entry(pl);
            KitSpec s = e == null ? null : spec(e.kit());
            return s == null ? "" : s.display();
        });
        plugin.hooks().placeholder("queue_size", (p, rest) -> String.valueOf(rest.isEmpty() ? queue.total() : queue.size(rest)));
    }

    @Override
    protected void onReload() {
        kits.load(plugin.module(PvpModule.class).combat());
        modernKits.load(plugin.module(PvpModule.class).combat());
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
        }
        queue.clear();
    }

    /** Киты 1.8 (быстрое PvP). */
    public DuelKits kits() {
        return kits;
    }

    /** Киты 1.9 (медленное PvP). */
    public DuelKits modernKits() {
        return modernKits;
    }

    /** Категория по версии: "1.8" / "1.9". */
    public DuelKits category(String version) {
        return dev.tearsmilk.miharu.pvp.CombatVersion.parse(version) == dev.tearsmilk.miharu.pvp.CombatVersion.MODERN ? modernKits : kits;
    }

    /** Кит по id из любой категории (только включённые). */
    public KitSpec spec(String id) {
        KitSpec s = kits.spec(id);
        return s != null ? s : modernKits.spec(id);
    }

    public List<String> enabledIds() {
        List<String> out = new ArrayList<>(kits.enabledIds());
        out.addAll(modernKits.enabledIds());
        return out;
    }

    public List<DuelKits.Entry> allEntries() {
        List<DuelKits.Entry> out = new ArrayList<>(kits.all());
        out.addAll(modernKits.all());
        return out;
    }

    public DuelQueue queue() {
        return queue;
    }

    public DuelStats stats() {
        return stats;
    }

    public DuelMenus menus() {
        return menus;
    }

    public EloStore elo() {
        return elo;
    }

    public Ranked ranked() {
        return ranked;
    }

    public Tiers tiers() {
        return tiers;
    }

    public StatsMenu statsMenu() {
        return statsMenu;
    }

    public BotsModule bots() {
        return plugin.module(BotsModule.class);
    }

    /** Лобби подписывается, чтобы обновлять предметы при входе/выходе из очереди. */
    public void onChange(Consumer<Player> hook) {
        changeHooks.add(hook);
    }

    void changed(Player p) {
        changeHooks.forEach(h -> h.accept(p));
    }

    /** Сколько людей сейчас играет этим китом. */
    public int playing(String kit) {
        GameModule games = plugin.module(GameModule.class);
        int n = 0;
        for (Game g : games.games().games()) {
            if (g instanceof KitGame kg && kg.kitSpec().id().equals(kit)) {
                n += g.players().size();
            }
        }
        return n;
    }

    /** Свободная (случайная подходящая) арена под кит. */
    public Arena arenaFor(KitSpec spec) {
        ArenaModule arenas = plugin.module(ArenaModule.class);
        List<Arena> list = arenas.arenas().forMode(spec.arenaMode()).stream()
                .filter(a -> spec.arenas().isEmpty() || spec.arenas().contains(a.id()))
                .toList();
        return list.isEmpty() ? null : list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    boolean startDuel(Player a, Player b, KitSpec spec) {
        return startDuel(a, b, spec, false);
    }

    boolean startDuel(Player a, Player b, KitSpec spec, boolean rankedMatch) {
        Arena arena = arenaFor(spec);
        if (arena == null) {
            plugin.messages().send(a, "duels.no-arena", "kit", spec.display());
            plugin.messages().send(b, "duels.no-arena", "kit", spec.display());
            return false;
        }
        GameModule games = plugin.module(GameModule.class);
        DuelGame game = new DuelGame(this, games, arena, spec, plugin.module(PvpModule.class).combat(), a, b, rankedMatch);
        return games.games().start(game, List.of(a, b));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        stats.load(e.getPlayer().getUniqueId());
        elo.load(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        queue.leave(e.getPlayer(), true);
        stats.unload(e.getPlayer().getUniqueId());
        elo.unload(e.getPlayer().getUniqueId());
    }
}
