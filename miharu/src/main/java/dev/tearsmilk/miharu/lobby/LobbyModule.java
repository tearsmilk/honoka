package dev.tearsmilk.miharu.lobby;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.arena.ArenaModule;
import dev.tearsmilk.miharu.bots.BotsModule;
import dev.tearsmilk.miharu.bots.game.ClickTestGame;
import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.core.module.MiharuModule;
import dev.tearsmilk.miharu.core.util.Items;
import dev.tearsmilk.miharu.core.util.Keys;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.duels.DuelQueue;
import dev.tearsmilk.miharu.duels.DuelsModule;
import dev.tearsmilk.miharu.game.GameBoard;
import dev.tearsmilk.miharu.game.GameModule;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import dev.tearsmilk.miharu.pvp.PvpModule;
import io.papermc.paper.command.brigadier.BasicCommand;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Лобби: предметы (дуэли, наблюдение, выход из очереди), своё табло и таб, защита, спавн. */
public final class LobbyModule extends MiharuModule {

    private final Map<UUID, GameBoard> boards = new HashMap<>();
    private final Map<UUID, String> itemState = new HashMap<>();
    private BukkitTask task;
    private final AdminMode adminMode = new AdminMode(this);

    public AdminMode adminMode() {
        return adminMode;
    }

    public LobbyModule(Miharu plugin) {
        super(plugin, "lobby");
    }

    @Override
    public List<Class<? extends MiharuModule>> dependencies() {
        return List.of(GameModule.class);
    }

    @Override
    protected void onEnable() {
        listen(new LobbyListener(this));
        GameModule games = plugin.module(GameModule.class);
        games.games().onJoin(p -> adminMode.exit(p, false));
        games.games().onReturn(p -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && isLobby(p)) {
                apply(p, config().getBoolean("teleport-on-return", false));
            }
        }));
        DuelsModule duels = plugin.module(DuelsModule.class);
        if (duels != null) {
            duels.onChange(p -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && isLobby(p)) {
                    giveItems(p);
                }
            }));
        }
        restartTask();
        BasicCommand lobby = (source, args) -> {
            if (source.getSender() instanceof Player p) {
                if (games.games().busy(p)) {
                    games.games().leave(p, false);
                }
                Location spawn = spawn();
                if (spawn != null) {
                    p.teleport(spawn);
                }
                apply(p, false);
            }
        };
        plugin.registerCommand("lobby", "В лобби", List.of("spawn"), lobby);
        plugin.registerCommand("setlobby", "Поставить спавн лобби", List.of(), new BasicCommand() {
            @Override
            public void execute(io.papermc.paper.command.brigadier.CommandSourceStack source, String[] args) {
                if (source.getSender() instanceof Player p) {
                    setSpawn(p.getLocation());
                    plugin.messages().send(p, "lobby.spawn-set");
                }
            }

            @Override
            public String permission() {
                return "miharu.admin";
            }
        });
        Bukkit.getOnlinePlayers().forEach(p -> {
            if (isLobby(p)) {
                apply(p, false);
            }
        });
    }

    @Override
    protected void onReload() {
        restartTask();
        itemState.clear();
        Bukkit.getOnlinePlayers().forEach(p -> {
            if (isLobby(p)) {
                apply(p, false);
            }
        });
    }

    @Override
    protected void onDisable() {
        adminMode.exitAll();
        if (task != null) {
            task.cancel();
        }
        for (UUID id : boards.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            }
        }
        boards.clear();
    }

    private void restartTask() {
        if (task != null) {
            task.cancel();
        }
        long period = Math.max(5, config().getLong("scoreboard.update-ticks", 20));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    // --- состояние ---

    /** В лобби = не в игре, не зритель, не в мире игры, и мир подходит под worlds. */
    public boolean isLobby(Player p) {
        GameModule games = plugin.module(GameModule.class);
        if (games != null && games.games().busy(p)) {
            return false;
        }
        ArenaModule arenas = plugin.module(ArenaModule.class);
        if (arenas != null && arenas.worlds().isManaged(p.getWorld())) {
            return false;
        }
        List<String> worlds = config().getStringList("worlds");
        return worlds.isEmpty() || worlds.contains(p.getWorld().getName());
    }

    public Location spawn() {
        ConfigurationSection s = config().getConfigurationSection("spawn");
        if (s == null || !s.getBoolean("set", false)) {
            return null;
        }
        World w = Bukkit.getWorld(s.getString("world", "world"));
        return w == null ? null : new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    void setSpawn(Location l) {
        config().set("spawn.set", true);
        config().set("spawn.world", l.getWorld().getName());
        config().set("spawn.x", l.getX());
        config().set("spawn.y", l.getY());
        config().set("spawn.z", l.getZ());
        config().set("spawn.yaw", (double) l.getYaw());
        config().set("spawn.pitch", (double) l.getPitch());
        try {
            config().save(new java.io.File(folder(), "config.yml"));
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Не удалось сохранить спавн лобби: " + e.getMessage());
        }
    }

    /** Готовит игрока к лобби: режим, здоровье, предметы, табло. */
    public void apply(Player p, boolean teleport) {
        if (adminMode.active(p)) {
            return;
        }
        if (teleport) {
            Location spawn = spawn();
            if (spawn != null) {
                p.teleport(spawn);
            }
        }
        ConfigurationSection j = config().getConfigurationSection("on-join");
        if (j != null) {
            try {
                if (!p.hasPermission("miharu.lobby.bypass")) {
                    p.setGameMode(GameMode.valueOf(j.getString("gamemode", "ADVENTURE").toUpperCase(Locale.ROOT)));
                }
            } catch (IllegalArgumentException ignored) {
            }
            if (j.getBoolean("heal", true)) {
                var max = p.getAttribute(Attribute.MAX_HEALTH);
                p.setHealth(max == null ? 20 : max.getValue());
                p.setFoodLevel(20);
                p.setSaturation(5);
                p.setFireTicks(0);
                for (PotionEffect e : p.getActivePotionEffects()) {
                    p.removePotionEffect(e.getType());
                }
            }
        }
        PvpModule pvp = plugin.module(PvpModule.class);
        if (pvp != null) {
            pvp.combat().force(p, (CombatVersion) null);
        }
        itemState.remove(p.getUniqueId());
        giveItems(p);
        showBoard(p);
    }

    private String state(Player p) {
        DuelsModule duels = plugin.module(DuelsModule.class);
        return duels != null && duels.queue().inQueue(p) ? "queue" : "idle";
    }

    private boolean visible(String show, String state) {
        return switch (show == null ? "always" : show.toLowerCase(Locale.ROOT)) {
            case "in-queue" -> state.equals("queue");
            case "not-in-queue" -> !state.equals("queue");
            default -> true;
        };
    }

    public void giveItems(Player p) {
        DuelsModule dm = plugin.module(DuelsModule.class);
        if (adminMode.active(p) || (dm != null && dm.builder().active(p))) {
            return;
        }
        String state = state(p);
        itemState.put(p.getUniqueId(), state);
        ConfigurationSection items = config().getConfigurationSection("items");
        if (config().getBoolean("on-join.clear-inventory", true) && !p.hasPermission("miharu.lobby.bypass")) {
            p.getInventory().clear();
        } else {
            for (int i = 0; i < p.getInventory().getSize(); i++) {
                if (Keys.get(p.getInventory().getItem(i), Keys.LOBBY_ITEM) != null) {
                    p.getInventory().setItem(i, null);
                }
            }
        }
        if (items == null) {
            return;
        }
        for (String id : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(id);
            if (s == null || !s.getBoolean("enabled", true) || !visible(s.getString("show"), state)) {
                continue;
            }
            ItemStack item = Items.parse(s, placeholders(p));
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(Keys.LOBBY_ITEM, PersistentDataType.STRING, s.getString("action", "none"));
            item.setItemMeta(meta);
            p.getInventory().setItem(s.getInt("slot", 0), item);
        }
        p.updateInventory();
    }

    /** Действие предмета лобби. */
    void runAction(Player p, String action) {
        if (action == null || action.equals("none")) {
            return;
        }
        DuelsModule duels = plugin.module(DuelsModule.class);
        BotsModule bots = plugin.module(BotsModule.class);
        GameModule games = plugin.module(GameModule.class);
        switch (action.toLowerCase(Locale.ROOT)) {
            case "duels", "duels-1.8" -> {
                if (duels != null) {
                    duels.menus().openKits(p, duels.kits());
                }
            }
            case "minigames" -> {
                if (duels != null) {
                    duels.menus().openKits(p, duels.minigameKits());
                }
            }
            case "duels-1.9", "duels-modern" -> {
                if (duels != null) {
                    duels.menus().openKits(p, duels.modernKits());
                }
            }
            case "stats" -> {
                if (duels != null) {
                    duels.statsMenu().open(p, p);
                }
            }
            case "ranked" -> {
                if (duels != null) {
                    duels.ranked().open(p);
                }
            }
            case "leave-queue" -> {
                if (duels != null) {
                    duels.queue().leave(p, false);
                }
            }
            case "spectate" -> games.spectateMenu().open(p);
            case "meetups" -> openMeetups(p);
            case "bots" -> {
                if (bots != null) {
                    bots.menu().openModes(p);
                }
            }
            case "clicktest" -> {
                if (bots != null) {
                    bots.menu().openVersions(p, ClickTestGame.MODE);
                }
            }
            default -> {
                if (action.startsWith("command:")) {
                    p.performCommand(action.substring(8).trim());
                }
            }
        }
    }

    /** Меню митапов (1x4 … 2x8). Пока только витрина — режимы появятся позже. */
    private void openMeetups(Player p) {
        ConfigurationSection m = config().getConfigurationSection("meetups-menu");
        if (m == null) {
            return;
        }
        dev.tearsmilk.miharu.core.menu.Menu menu = new dev.tearsmilk.miharu.core.menu.Menu(m.getInt("rows", 3), Text.mm(m.getString("title", "Митапы")));
        ConfigurationSection modes = m.getConfigurationSection("modes");
        if (modes != null) {
            for (String id : modes.getKeys(false)) {
                ConfigurationSection it = modes.getConfigurationSection(id);
                if (it == null || !it.getBoolean("enabled", true)) {
                    continue;
                }
                String name = it.getString("display", id);
                String queueKit = it.getString("queue", "");
                if (queueKit.equals("mg-meetup")) {
                    queueKit = "mup-12";   // карточка 1x2 из 0.2.11 → настоящий митап
                }
                String finalQueue = queueKit;
                menu.set(it.getInt("slot"), Items.parse(it, Map.of("mode", name)), pl -> {
                    pl.playSound(pl.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
                    DuelsModule duels = plugin.module(DuelsModule.class);
                    KitSpec spec = finalQueue.isEmpty() || duels == null ? null : duels.spec(finalQueue);
                    if (spec != null) {
                        pl.closeInventory();
                        duels.queue().join(pl, spec);
                    } else {
                        plugin.messages().send(pl, "lobby.meetups-soon", "mode", name);
                    }
                });
            }
        }
        ConfigurationSection f = m.getConfigurationSection("filler");
        if (f != null && f.getBoolean("enabled", true)) {
            menu.fill(Items.parse(f, Map.of()));
        }
        menu.open(p);
    }

    // --- табло и таб ---

    private void showBoard(Player p) {
        if (!config().getBoolean("scoreboard.enabled", true)) {
            return;
        }
        GameBoard board = boards.computeIfAbsent(p.getUniqueId(), k -> new GameBoard(Text.mm(config().getString("scoreboard.title", "<b>MIHARU"))));
        board.show(p);
        update(p, board);
    }

    void forget(Player p) {
        boards.remove(p.getUniqueId());
        itemState.remove(p.getUniqueId());
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isLobby(p)) {
                continue;
            }
            GameBoard board = boards.get(p.getUniqueId());
            if (board != null && config().getBoolean("scoreboard.enabled", true)) {
                if (p.getScoreboard() != board.scoreboard()) {
                    board.show(p);
                }
                update(p, board);
            } else if (board == null) {
                showBoard(p);
            }
            if (adminMode.active(p)) {
                plugin.messages().actionBar(p, "lobby.adm-actionbar");
                continue;
            }
            if (!state(p).equals(itemState.get(p.getUniqueId()))) {
                giveItems(p);
            }
        }
    }

    private void update(Player p, GameBoard board) {
        Map<String, Object> ph = placeholders(p);
        List<Component> lines = new ArrayList<>();
        for (String line : config().getStringList("scoreboard.lines")) {
            lines.add(Text.mm(p, line, ph));
        }
        board.title(Text.mm(p, config().getString("scoreboard.title", "<b>MIHARU"), ph));
        board.lines(lines);
        if (config().getBoolean("tab.enabled", true)) {
            p.sendPlayerListHeaderAndFooter(
                    Text.mm(p, String.join("\n", config().getStringList("tab.header")), ph),
                    Text.mm(p, String.join("\n", config().getStringList("tab.footer")), ph));
        }
    }

    Map<String, Object> placeholders(Player p) {
        Map<String, Object> ph = new HashMap<>();
        GameModule games = plugin.module(GameModule.class);
        DuelsModule duels = plugin.module(DuelsModule.class);
        BotsModule bots = plugin.module(BotsModule.class);
        ph.put("player", p.getName());
        ph.put("online", Bukkit.getOnlinePlayers().size());
        ph.put("ping", p.getPing());
        ph.put("in_game", games == null ? 0 : games.games().playersInGames());
        ph.put("games", games == null ? 0 : games.games().games().size());
        ph.put("queue_total", duels == null ? 0 : duels.queue().total());
        DuelQueue.Entry e = duels == null ? null : duels.queue().entry(p);
        KitSpec spec = e == null ? null : duels.spec(e.kit());
        ph.put("queue_kit", spec == null ? plugin.messages().raw("lobby.not-in-queue") : spec.display());
        ph.put("queue_time", e == null ? "-" : Text.time((System.currentTimeMillis() - e.since()) / 1000));
        if (duels != null) {
            java.util.List<KitSpec> fast = duels.ranked().kits(dev.tearsmilk.miharu.pvp.CombatVersion.LEGACY);
            java.util.List<KitSpec> slow = duels.ranked().kits(dev.tearsmilk.miharu.pvp.CombatVersion.MODERN);
            ph.put("elo_fast", fast.isEmpty() ? 0 : duels.elo().elo(p.getUniqueId(), fast.getFirst().id()));
            ph.put("elo_slow", slow.isEmpty() ? 0 : duels.elo().elo(p.getUniqueId(), slow.getFirst().id()));
            ph.put("tier_fast", fast.isEmpty() ? "" : duels.tiers().display(p.getUniqueId(), fast.getFirst().id()));
            ph.put("tier_slow", slow.isEmpty() ? "" : duels.tiers().display(p.getUniqueId(), slow.getFirst().id()));
        } else {
            ph.put("elo_fast", 0);
            ph.put("elo_slow", 0);
            ph.put("tier_fast", "");
            ph.put("tier_slow", "");
        }
        ph.put("queue_mode", e != null && e.ranked() ? plugin.messages().raw("ranked.label") : "");
        ph.put("duel_wins", duels == null ? 0 : duels.stats().wins(p.getUniqueId(), null));
        ph.put("duel_losses", duels == null ? 0 : duels.stats().losses(p.getUniqueId(), null));
        ph.put("bot_wins", bots == null ? 0 : bots.stats().summary(p.getUniqueId()).wins(null));
        ph.put("clicktest_best", bots == null ? 0 : bots.stats().summary(p.getUniqueId()).bestClickTest);
        return ph;
    }
}
