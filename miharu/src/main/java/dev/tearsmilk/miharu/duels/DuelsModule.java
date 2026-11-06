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
    private final DuelKits minigameKits = new DuelKits(this, "minigames", "mg-", dev.tearsmilk.miharu.pvp.CombatVersion.LEGACY, DuelKits.MINIGAME_DEFAULTS).minigames();
    private final Loot loot = new Loot(this);
    private final DuelKits modernKits = new DuelKits(this, "kits-1.9", "m-", dev.tearsmilk.miharu.pvp.CombatVersion.MODERN, DuelKits.MODERN_DEFAULTS);
    private final List<Consumer<Player>> changeHooks = new ArrayList<>();
    private DuelQueue queue;
    private DuelStats stats;
    private DuelMenus menus;
    private KitLayouts layouts;
    private KitEditor editor;
    private CustomKits customKits;
    private KitBuilder builder;
    private Meetups meetups;
    /** Какой свой кит игрок выбрал для очереди «Свой кит». */
    private final java.util.Map<java.util.UUID, dev.tearsmilk.miharu.core.kit.Kit> customSelection = new java.util.HashMap<>();
    private final java.util.Map<dev.tearsmilk.miharu.pvp.CombatVersion, KitSpec> customSpecs = new java.util.EnumMap<>(dev.tearsmilk.miharu.pvp.CombatVersion.class);
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
        migrateNoRanked();
        kits.load(plugin.module(PvpModule.class).combat());
        modernKits.load(plugin.module(PvpModule.class).combat());
        minigameKits.load(plugin.module(PvpModule.class).combat());
        stats = new DuelStats(plugin.database());
        stats.createTables();
        layouts = new KitLayouts(plugin.database());
        layouts.createTables();
        editor = new KitEditor(this);
        listen(editor);
        customKits = new CustomKits(plugin.database());
        customKits.createTables();
        builder = new KitBuilder(this);
        listen(builder);
        meetups = new Meetups(this);
        meetups.load();
        listen(meetups);
        plugin.registerCommand("force-start", "Принудительный старт митапа (OP)", List.of("forcestart"), new io.papermc.paper.command.brigadier.BasicCommand() {
            @Override
            public void execute(io.papermc.paper.command.brigadier.CommandSourceStack source, String[] args) {
                forceStart(source.getSender(), args.length > 0 ? args[0] : null);
            }

            @Override
            public java.util.Collection<String> suggest(io.papermc.paper.command.brigadier.CommandSourceStack source, String[] args) {
                return meetups.ids();
            }

            @Override
            public boolean canUse(org.bukkit.command.CommandSender sender) {
                return sender.isOp();
            }
        });
        buildCustomSpecs();
        dev.tearsmilk.miharu.core.kit.Kit.layouts = (uuid, kit) -> layouts.get(uuid, kit);
        plugin.registerCommand("kit", "Раскладка китов: /kit edit", List.of("kiteditor", "layout"), new io.papermc.paper.command.brigadier.BasicCommand() {
            @Override
            public void execute(io.papermc.paper.command.brigadier.CommandSourceStack source, String[] args) {
                if (source.getSender() instanceof Player p) {
                    if (args.length > 0 && args[0].equalsIgnoreCase("create")) {
                        builder.openSlots(p);
                    } else {
                        editor.openKits(p, kits);
                    }
                }
            }

            @Override
            public java.util.Collection<String> suggest(io.papermc.paper.command.brigadier.CommandSourceStack source, String[] args) {
                return List.of("edit", "create");
            }
        });
        statsMenu = new StatsMenu(this);
        plugin.registerCommand("stats", "Статистика игрока", List.of("stat"), new StatsMenu.Command(this));
        queue = new DuelQueue(this);
        menus = new DuelMenus(this);
        ArenaModule arenas = plugin.module(ArenaModule.class);
        arenas.registerMode(new ArenaMode("duel", "Дуэль", List.of("p1", "p2")));
        arenas.registerMode(new ArenaMode("sumo", "Сумо", List.of("p1", "p2")));
        arenas.registerMode(new ArenaMode("bridge", "Бридж", List.of("p1", "p2", "goal1", "goal2")));
        arenas.registerMode(new ArenaMode("mlg", "МЛГ-Раш", List.of("p1", "p2", "bed1", "bed2")));
        arenas.registerMode(new ArenaMode("bedwars", "BedWars", List.of("p1", "p2", "bed1", "bed2")));
        arenas.registerMode(new ArenaMode("fireball", "Fireball Fight", List.of("p1", "p2", "bed1", "bed2")));
        arenas.registerMode(new ArenaMode("skywars", "SkyWars", List.of("p1", "p2")));
        arenas.registerMode(new ArenaMode("meetup", "Митап", List.of("p1", "p2")));
        listen(this);
        listen(new KitMechanics(this));
        freeArenas = new FreeArenas(this);
        freeArenas.load();
        listen(freeArenas);
        plugin.registerCommand("ffa", "Свободные арены: Sword, SpearMace, ElytraMace", List.of("freearena"), freeArenas.command());
        task = Bukkit.getScheduler().runTaskTimer(plugin, queue::tick, 20L, 20L);
        plugin.registerCommand("duel", "Дуэли по китам", List.of("duels", "kits"), new DuelCommand(plugin, this));
        Bukkit.getOnlinePlayers().forEach(p -> {
            stats.load(p.getUniqueId());
            layouts.load(p.getUniqueId());
            customKits.load(p.getUniqueId());
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
        reloadKits();
        buildCustomSpecs();
        meetups.load();
    }

    public Meetups meetups() {
        return meetups;
    }

    /** /force-start [режим]: на подготовке — сразу бой; иначе запустить очередь митапа с теми, кто ждёт. */
    void forceStart(org.bukkit.command.CommandSender sender, String modeArg) {
        if (!sender.isOp()) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        if (sender instanceof Player p && modeArg == null) {
            GameModule games = plugin.module(GameModule.class);
            Game g = games.games().contextOf(p);
            if (g instanceof MeetupGame mg) {
                plugin.messages().send(sender, mg.skipPrep() ? "meetups.force-prep" : "meetups.force-not-prep");
                return;
            }
        }
        String mode = modeArg;
        if (mode == null && sender instanceof Player p) {
            DuelQueue.Entry e = queue.entry(p);
            if (e != null && meetups.mode(e.kit()) != null) {
                mode = e.kit();
            }
        }
        if (mode == null) {
            int best = 0;
            for (String id : meetups.ids()) {
                int n = queue.waiting(id);
                if (n > best) {
                    best = n;
                    mode = id;
                }
            }
        }
        KitSpec spec = mode == null ? null : meetups.spec(mode);
        if (spec == null) {
            plugin.messages().send(sender, "meetups.force-no-queue", "modes", String.join(", ", meetups.ids()));
            return;
        }
        int started = queue.forceStart(mode, spec);
        if (started == 0) {
            plugin.messages().send(sender, "meetups.force-not-enough", "mode", spec.display(), "count", queue.waiting(mode));
        } else {
            plugin.messages().send(sender, "meetups.force-started", "mode", spec.display(), "count", started);
        }
    }

    /** Спец-киты очереди «Свой кит» для 1.8 и 1.9 (у каждого игрока в игре — свой кит). */
    private void buildCustomSpecs() {
        customSpecs.clear();
        PvpModule pvp = plugin.module(PvpModule.class);
        for (dev.tearsmilk.miharu.pvp.CombatVersion v : dev.tearsmilk.miharu.pvp.CombatVersion.values()) {
            dev.tearsmilk.miharu.core.kit.KitRules rules = dev.tearsmilk.miharu.core.kit.KitRules.load(config().getConfigurationSection("custom-kits.rules"), 1);
            String display = config().getString("custom-kits.display", "<#c9a7ff>Свой кит <gray>{version}").replace("{version}", v.id());
            customSpecs.put(v, new KitSpec("custom-" + v.id().replace(".", ""), display, v, pvp.combat().profile(v),
                    new dev.tearsmilk.miharu.core.kit.Kit("custom-" + v.id(), java.util.Map.of(), java.util.Map.of()),
                    rules, "duel", List.of(), KitSpec.Bot.DISABLED));
        }
    }

    public CustomKits customKits() {
        return customKits;
    }

    public KitBuilder builder() {
        return builder;
    }

    /** Встать в очередь «Свой кит» со своим китом из слота. */
    public void queueCustom(Player p, int slot, dev.tearsmilk.miharu.pvp.CombatVersion version) {
        CustomKits.CustomKit kit = customKits.get(p.getUniqueId(), slot);
        if (kit == null) {
            return;
        }
        customSelection.put(p.getUniqueId(), kit.toKit(p.getUniqueId()));
        queue.join(p, customSpecs.get(version)); // общий кит — PvP по меню, из которого запущен
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
        }
        queue.clear();
        builder.endAll();
        dev.tearsmilk.miharu.core.kit.Kit.layouts = (u, k) -> null;
    }

    public KitLayouts layouts() {
        return layouts;
    }

    public KitEditor editor() {
        return editor;
    }

    /** Перечитать киты с диска. */
    public void reloadKits() {
        kits.load(plugin.module(PvpModule.class).combat());
        modernKits.load(plugin.module(PvpModule.class).combat());
        minigameKits.load(plugin.module(PvpModule.class).combat());
    }

    /** Мини-игры (фаербол в лобби). */
    public DuelKits minigameKits() {
        return minigameKits;
    }

    public Loot loot() {
        return loot;
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
        if (s == null) {
            s = modernKits.spec(id);
        }
        if (s == null && meetups != null && meetups.spec(id) != null) {
            return meetups.spec(id);
        }
        if (s == null && id != null) {
            for (KitSpec c : customSpecs.values()) {
                if (c.id().equals(id)) {
                    return c;
                }
            }
        }
        return s != null ? s : minigameKits.spec(id);
    }

    public List<String> enabledIds() {
        List<String> out = new ArrayList<>(kits.enabledIds());
        out.addAll(modernKits.enabledIds());
        out.addAll(minigameKits.enabledIds());
        return out;
    }

    public List<DuelKits.Entry> allEntries() {
        List<DuelKits.Entry> out = new ArrayList<>(kits.all());
        out.addAll(modernKits.all());
        out.addAll(minigameKits.all());
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
            if (g instanceof KitGame kg && (kg.kitSpec().id().equals(kit) || kg.kitSpec().id().startsWith(kit + "-"))) {
                n += g.players().size();
            }
        }
        return n;
    }

    /** Свободная (случайная подходящая) арена под кит. */
    /** Режим арены варианта (sw-trio) → общий режим игры (skywars): старые арены тоже подходят, если в них есть точки. */
    private final java.util.Map<String, String> variantBase = new java.util.HashMap<>();

    /** Короткое имя для режимов арен по числу игроков: skywars → sw. */
    String arenaShort(String mode) {
        String v = config().getString("arena-short." + mode);
        if (v != null) {
            return v;
        }
        return switch (mode) {
            case "skywars" -> "sw";
            case "bedwars" -> "bw";
            case "bridge" -> "br";
            case "fireball" -> "fb";
            case "boxpvp" -> "box";
            case "bowspleef" -> "bs";
            case "gungame" -> "gg";
            case "skycontrol" -> "sc";
            default -> mode;
        };
    }

    /** 2 → duo, 3 → trio, 4 → quatro (arena-size-names в config.yml). */
    String sizeName(int players) {
        String v = config().getString("arena-size-names." + players);
        if (v != null) {
            return v;
        }
        return switch (players) {
            case 2 -> "duo";
            case 3 -> "trio";
            case 4 -> "quatro";
            default -> String.valueOf(players);
        };
    }

    void registerVariantArenaMode(String id, String base, int players, dev.tearsmilk.miharu.core.kit.KitRules.Objective objective, String display) {
        registerVariantArenaMode(id, base, players, objective, display, List.of(), List.of(), null);
    }

    /** Размер команды варианта (BoxPvP 2x4 → 2); 1 — каждый сам за себя. */
    private final java.util.Map<String, Integer> teamSizes = new java.util.concurrent.ConcurrentHashMap<>();

    /** Свой минимум для быстрого старта (quick-start-min в yml мини-игры, например BowSpleef — от 2). */
    private FreeArenas freeArenas;

    public FreeArenas freeArenas() {
        return freeArenas;
    }

    private final java.util.Map<String, Integer> quickMins = new java.util.concurrent.ConcurrentHashMap<>();

    void setQuickMin(String kitId, int min) {
        if (min > 0) {
            quickMins.put(kitId.toLowerCase(java.util.Locale.ROOT), min);
        } else {
            quickMins.remove(kitId.toLowerCase(java.util.Locale.ROOT));
        }
    }

    int quickMin(String kitId) {
        return kitId == null ? 0 : quickMins.getOrDefault(kitId.toLowerCase(java.util.Locale.ROOT), 0);
    }

    /** Своя схема точек арены (SkyControl: red, blue, red-shop…) — p1…pN не проверяются. */
    private final java.util.Map<String, List<String>> customPoints = new java.util.concurrent.ConcurrentHashMap<>();
    /** Старые имена точек: red → p1 и т.д. (арены, настроенные до переименования). */
    private final java.util.Map<String, String> pointAliases = new java.util.concurrent.ConcurrentHashMap<>();

    void registerCustomArenaMode(String id, String base, String display, List<String> required, java.util.Map<String, String> aliases) {
        variantBase.put(id, base);
        customPoints.put(id, List.copyOf(required));
        pointAliases.putAll(aliases);
        ArenaModule arenas = plugin.module(ArenaModule.class);
        if (arenas != null) {
            arenas.registerMode(new ArenaMode(id, display, required));
        }
    }

    private boolean hasPoint(Arena a, String name) {
        return a.spawn(name) != null || (pointAliases.containsKey(name) && a.spawn(pointAliases.get(name)) != null);
    }

    /** Зайти в игру, которая ещё набирает игроков (вместо очереди). */
    boolean lateJoin(Player p, KitSpec spec) {
        GameModule games = plugin.module(GameModule.class);
        if (games == null) {
            return false;
        }
        for (Game g : games.games().games()) {
            if (g instanceof SkyControlGame sg && sg.kitSpec().id().equalsIgnoreCase(spec.id()) && sg.canLateJoin()) {
                if (games.games().addPlayer(sg, p)) {
                    changed(p);
                    return true;
                }
            }
        }
        // SkyControl: зона ожидания и есть «лобби» — первый игрок сразу создаёт игру, остальные заходят в неё
        if ("skycontrol".equalsIgnoreCase(spec.kit().id().replace("mg-", ""))) {
            plugin.messages().send(p, "skycontrol.lobby-created", "kit", spec.display());
            return startFfa(new ArrayList<>(List.of(p)), spec);
        }
        return false;
    }

    void setTeamSize(String kitId, int size) {
        teamSizes.put(kitId.toLowerCase(java.util.Locale.ROOT), size);
    }

    public int teamSize(KitSpec spec) {
        return Math.max(1, teamSizes.getOrDefault(spec.id().toLowerCase(java.util.Locale.ROOT), 1));
    }

    /** 1x1 / 1x4 / 2x4 … */
    public String format(KitSpec spec) {
        int pl = spec.rules().players();
        int ts = teamSize(spec);
        return ts > 1 ? ts + "x" + Math.max(1, pl / ts) : pl <= 2 ? "1x1" : "1x" + pl;
    }

    /**
     * @param perPlayer доп. точки на каждого игрока (pre → pre1…preN)
     * @param extras    общие точки арены (box)
     */
    void registerVariantArenaMode(String id, String base, int players, dev.tearsmilk.miharu.core.kit.KitRules.Objective objective, String display,
                                  List<String> perPlayer, List<String> extras, String format) {
        variantBase.put(id, base);
        ArenaModule arenas = plugin.module(ArenaModule.class);
        if (arenas == null) {
            return;
        }
        String extra = switch (objective) {
            case BED, BEDWARS -> "bed";
            case GOAL -> "goal";
            default -> null;
        };
        List<String> spawns = new ArrayList<>();
        for (int i = 1; i <= players; i++) {
            spawns.add("p" + i);
            if (extra != null) {
                spawns.add(extra + i);
            }
            for (String pp : perPlayer) {
                spawns.add(pp + i);
            }
        }
        spawns.addAll(extras);
        arenas.registerMode(new ArenaMode(id, display + " · " + (format != null ? format : players <= 2 ? "1x1" : "1x" + players), spawns));
    }

    public Arena arenaFor(KitSpec spec) {
        ArenaModule arenas = plugin.module(ArenaModule.class);
        int need = spec.rules().players() / teamSize(spec); // в командных режимах точки на команду
        List<Arena> candidates = new ArrayList<>(arenas.arenas().forMode(spec.arenaMode()));
        String base = variantBase.get(spec.arenaMode());
        if (base != null) {
            for (Arena a : arenas.arenas().forMode(base)) {
                if (!candidates.contains(a)) {
                    candidates.add(a);
                }
            }
        }
        ArenaMode required = arenas.mode(spec.arenaMode());
        List<String> custom = customPoints.get(spec.arenaMode());
        if (custom != null) {
            // старые имена точек (p1, shop1…) — берём арены без строгой проверки и проверяем с учётом псевдонимов
            for (Arena a : arenas.arenas().forModeAnyPoints(spec.arenaMode())) {
                if (!candidates.contains(a)) {
                    candidates.add(a);
                }
            }
            if (base != null) {
                for (Arena a : arenas.arenas().forModeAnyPoints(base)) {
                    if (!candidates.contains(a)) {
                        candidates.add(a);
                    }
                }
            }
            List<Arena> list = candidates.stream()
                    .filter(a -> spec.arenas().isEmpty() || spec.arenas().contains(a.id()))
                    .filter(a -> custom.stream().allMatch(n -> hasPoint(a, n)))
                    .toList();
            return list.isEmpty() ? null : list.get(ThreadLocalRandom.current().nextInt(list.size()));
        }
        List<Arena> list = candidates.stream()
                .filter(a -> required == null || a.spawns().keySet().containsAll(required.requiredSpawns()))
                .filter(a -> spec.arenas().isEmpty() || spec.arenas().contains(a.id()))
                .filter(a -> {
                    // нужны точки p1…pN, а для кроватей/ворот ещё bed1…bedN / goal1…goalN
                    String extra = switch (spec.rules().objective()) {
                        case BED, BEDWARS -> "bed";
                        case GOAL -> "goal";
                        default -> null;
                    };
                    for (int i = 1; i <= need; i++) {
                        if (a.spawn("p" + i) == null || (extra != null && a.spawn(extra + i) == null)) {
                            return false;
                        }
                    }
                    return true;
                })
                .toList();
        return list.isEmpty() ? null : list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    /** 0.2.33: рейтинг убран — чистим старые строки статистики; своих китов 5 вместо 3. */
    private void migrateNoRanked() {
        java.io.File f = new java.io.File(folder(), "config.yml");
        if (!f.exists()) {
            return;
        }
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        boolean changed = false;
        if (y.contains("ranked")) {
            y.set("ranked", null);
            changed = true;
        }
        for (String key : List.of("stats-menu.ranked-slots", "stats-menu.ranked-icon", "stats-menu.ranked-icons")) {
            if (y.contains(key)) {
                y.set(key, null);
                changed = true;
            }
        }
        List<String> lore = new ArrayList<>(y.getStringList("stats-menu.head.lore"));
        if (lore.removeIf(l -> l.contains("{ranked_") || l.contains("{tier") || l.contains("{elo"))) {
            y.set("stats-menu.head.lore", lore);
            changed = true;
        }
        // 0.2.34: свои киты без версии — убрать «Версия: …» из подписей
        List<String> filled = new ArrayList<>(y.getStringList("custom-kits.slots-menu.filled.lore"));
        if (filled.removeIf(l -> l.contains("{version}"))) {
            y.set("custom-kits.slots-menu.filled.lore", filled);
            changed = true;
        }
        String bt = y.getString("custom-kits.builder.title", "");
        if (bt.contains("{version}")) {
            y.set("custom-kits.builder.title", bt.replace(" <gray>· {version}", "").replace("{version}", ""));
            changed = true;
        }
        // 0.2.38: запрещённые предметы на свободных аренах
        if (y.isConfigurationSection("free-arenas.types") && !y.contains("free-arenas.types.sword.banned-items")) {
            y.set("free-arenas.types.sword.banned-items", List.of("ELYTRA", "WIND_CHARGE", "MACE", "*_SPEAR"));
            y.set("free-arenas.types.spearmace.banned-items", List.of("ELYTRA"));
            y.set("free-arenas.types.elytramace.banned-items", List.of());
            changed = true;
        }
        // 0.2.37: свободные арены — ванильное PvP 1.21.11
        if (y.isConfigurationSection("free-arenas.types") && !y.getBoolean("free-arenas.vanilla-migrated", false)) {
            for (String t : y.getConfigurationSection("free-arenas.types").getKeys(false)) {
                y.set("free-arenas.types." + t + ".version", "vanilla");
            }
            y.set("free-arenas.vanilla-migrated", true);
            changed = true;
        }
        if (y.getIntegerList("custom-kits.slots-menu.slots").size() < CustomKits.SLOTS) {
            y.set("custom-kits.slots-menu.slots", List.of(11, 12, 13, 14, 15));
            changed = true;
        }
        if (y.getIntegerList("custom-kits.duels-menu.slots").size() < CustomKits.SLOTS) {
            y.set("custom-kits.duels-menu.slots", List.of(38, 39, 40, 41, 42));
            changed = true;
        }
        if (changed) {
            try {
                y.save(f);
            } catch (java.io.IOException e) {
                plugin.getLogger().warning("Не удалось обновить modules/duels/config.yml: " + e.getMessage());
            }
        }
    }

    boolean startDuel(Player a, Player b, KitSpec spec) {
        Arena arena = arenaFor(spec);
        if (arena == null) {
            plugin.messages().send(a, "duels.no-arena", "kit", spec.display());
            plugin.messages().send(b, "duels.no-arena", "kit", spec.display());
            return false;
        }
        GameModule games = plugin.module(GameModule.class);
        DuelGame game = new DuelGame(this, games, arena, spec, plugin.module(PvpModule.class).combat(), a, b);
        if (spec.id().startsWith("custom-")) {
            game.overrideKit(a, customSelection.get(a.getUniqueId()));
            game.overrideKit(b, customSelection.get(b.getUniqueId()));
        }
        return games.games().start(game, List.of(a, b));
    }

    /** Игра «каждый сам за себя» на N игроков (SkyWars 1x3, 1x4). */
    boolean startFfa(List<Player> players, KitSpec spec) {
        Arena arena = arenaFor(spec);
        if (arena == null) {
            players.forEach(p -> plugin.messages().send(p, "duels.no-arena", "kit", spec.display()));
            return false;
        }
        GameModule games = plugin.module(GameModule.class);
        Game game;
        if (meetups.mode(spec.id()) != null) {
            game = new MeetupGame(this, games, arena, spec, meetups.mode(spec.id()), players);
        } else if ("boxpvp".equalsIgnoreCase(spec.kit().id().replace("mg-", ""))) {
            game = new BoxPvpGame(this, games, arena, spec, plugin.module(PvpModule.class).combat(), players);
        } else if ("skycontrol".equalsIgnoreCase(spec.kit().id().replace("mg-", ""))) {
            game = new SkyControlGame(this, games, arena, spec, plugin.module(PvpModule.class).combat(), players);
        } else if ("gungame".equalsIgnoreCase(spec.kit().id().replace("mg-", ""))) {
            game = new GunGameGame(this, games, arena, spec, plugin.module(PvpModule.class).combat(), players);
        } else {
            game = new FfaGame(this, games, arena, spec, plugin.module(PvpModule.class).combat(), players);
        }
        return games.games().start(game, players);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        stats.load(e.getPlayer().getUniqueId());
        layouts.load(e.getPlayer().getUniqueId());
        customKits.load(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        queue.leave(e.getPlayer(), true);
        stats.unload(e.getPlayer().getUniqueId());
        layouts.unload(e.getPlayer().getUniqueId());
        customKits.unload(e.getPlayer().getUniqueId());
        customSelection.remove(e.getPlayer().getUniqueId());
    }
}
