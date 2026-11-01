package dev.tearsmilk.miharu.bots;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.arena.Arena;
import dev.tearsmilk.miharu.arena.ArenaMode;
import dev.tearsmilk.miharu.arena.ArenaModule;
import dev.tearsmilk.miharu.bots.ai.BotDifficulty;
import dev.tearsmilk.miharu.bots.game.BotDuelGame;
import dev.tearsmilk.miharu.bots.game.BotGame;
import dev.tearsmilk.miharu.bots.game.ClickTestGame;
import dev.tearsmilk.miharu.core.kit.Kit;
import dev.tearsmilk.miharu.bots.kit.KitManager;
import dev.tearsmilk.miharu.bots.menu.BotMenu;
import dev.tearsmilk.miharu.bots.stats.BotStats;
import dev.tearsmilk.miharu.core.kit.KitRules;
import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.core.module.MiharuModule;
import dev.tearsmilk.miharu.game.GameModule;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import dev.tearsmilk.miharu.pvp.PvpModule;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Игры с пакетными ботами: дуэль и клик-тест, сложности easy/medium/hard, меню выбора. */
public final class BotsModule extends MiharuModule implements Listener {

    public record Skin(String value, String signature) {
    }

    private final Map<String, BotDifficulty> difficulties = new LinkedHashMap<>();
    private final KitManager kits = new KitManager();
    private final Set<UUID> suppressKnockback = new HashSet<>();
    private YamlConfiguration menuConfig;
    private BotStats stats;
    private BotMenu menu;
    private volatile Skin namedSkin;

    public BotsModule(Miharu plugin) {
        super(plugin, "bots");
    }

    @Override
    public List<Class<? extends MiharuModule>> dependencies() {
        return List.of(PvpModule.class, ArenaModule.class, GameModule.class);
    }

    @Override
    protected void onEnable() {
        load();
        stats = new BotStats(this, plugin.database());
        stats.createTables();
        menu = new BotMenu(this);
        ArenaModule arenas = plugin.module(ArenaModule.class);
        arenas.registerMode(new ArenaMode(BotDuelGame.MODE, config().getString("modes.bot-duel.display", "Дуэль с ботом"), List.of("player", "bot")));
        arenas.registerMode(new ArenaMode(ClickTestGame.MODE, config().getString("modes.clicktest.display", "Клик-тест"), List.of("player", "bot")));
        listen(this);
        plugin.registerCommand("bot", "Игры с ботами", List.of("bots", "duelbot"), new BotCommand(plugin, this));
        Bukkit.getOnlinePlayers().forEach(p -> stats.load(p.getUniqueId()));

        plugin.hooks().placeholder("bot_wins", (p, rest) -> p == null ? "0" : String.valueOf(stats.summary(p.getUniqueId()).wins(rest.isEmpty() ? null : rest)));
        plugin.hooks().placeholder("bot_losses", (p, rest) -> p == null ? "0" : String.valueOf(stats.summary(p.getUniqueId()).losses(rest.isEmpty() ? null : rest)));
        plugin.hooks().placeholder("clicktest_best", (p, rest) -> p == null ? "0" : String.valueOf(stats.summary(p.getUniqueId()).bestClickTest));
        plugin.hooks().placeholder("clicktest_best_cps", (p, rest) -> p == null ? "0" : String.valueOf(stats.summary(p.getUniqueId()).bestCps));
    }

    @Override
    protected void onReload() {
        load();
    }

    private void load() {
        difficulties.clear();
        ConfigurationSection ds = config().getConfigurationSection("difficulties");
        if (ds != null) {
            for (String id : ds.getKeys(false)) {
                difficulties.put(id.toLowerCase(Locale.ROOT), BotDifficulty.load(id.toLowerCase(Locale.ROOT), ds.getConfigurationSection(id)));
            }
        }
        kits.load(loadFile("kits.yml"), plugin.getLogger());
        menuConfig = loadFile("menu.yml");
        namedSkin = null;
        if ("name".equalsIgnoreCase(config().getString("bot.skin.mode", "none"))) {
            String name = config().getString("bot.skin.name", "");
            if (!name.isEmpty()) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        PlayerProfile profile = Bukkit.createProfile(name);
                        if (profile.complete(true)) {
                            for (ProfileProperty prop : profile.getProperties()) {
                                if (prop.getName().equals("textures")) {
                                    namedSkin = new Skin(prop.getValue(), prop.getSignature());
                                }
                            }
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Не удалось получить скин " + name + ": " + e.getMessage());
                    }
                });
            }
        }
    }

    // --- API ---

    public YamlConfiguration menuConfig() {
        return menuConfig;
    }

    public BotStats stats() {
        return stats;
    }

    public BotMenu menu() {
        return menu;
    }

    public BotDifficulty difficulty(String id) {
        return id == null ? null : difficulties.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<BotDifficulty> difficulties() {
        return difficulties.values();
    }

    public List<String> modes() {
        return List.of(BotDuelGame.MODE, ClickTestGame.MODE);
    }

    public static String normalizeMode(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "duel", "bot-duel", "botduel", "дуэль" -> BotDuelGame.MODE;
            case "clicktest", "click", "cps", "sumo", "клик", "кликтест" -> ClickTestGame.MODE;
            default -> null;
        };
    }

    /**
     * Запуск игры с ботом.
     *
     * @param arenaId конкретная арена или null — случайная подходящая
     * @return true, если игра создана
     */
    public boolean start(Player p, String mode, CombatVersion version, BotDifficulty difficulty, String arenaId) {
        GameModule games = plugin.module(GameModule.class);
        ArenaModule arenas = plugin.module(ArenaModule.class);
        PvpModule pvp = plugin.module(PvpModule.class);
        if (games.games().inGame(p)) {
            plugin.messages().send(p, "game.already-in-game");
            return false;
        }
        if (!config().getBoolean("modes." + mode + ".enabled", true)) {
            plugin.messages().send(p, "bots.mode-disabled");
            return false;
        }
        Arena arena;
        if (arenaId != null) {
            arena = arenas.arenas().get(arenaId);
            if (arena == null || !arenas.arenas().forMode(mode).contains(arena)) {
                plugin.messages().send(p, "bots.arena-unavailable", "arena", arenaId);
                return false;
            }
        } else {
            arena = arenas.arenas().random(mode);
        }
        if (arena == null) {
            plugin.messages().send(p, "bots.no-arena", "mode", config().getString("modes." + mode + ".display", mode));
            return false;
        }
        String kitId = config().getString("modes." + mode + ".kits." + version.id(), config().getString("modes." + mode + ".kit", ""));
        Kit kit = kits.get(kitId);
        KitRules base = KitRules.load(null, 1);
        KitRules rules = new KitRules(base.damage(), base.hunger(), base.naturalRegen(), base.fallDamage(), false, true, false,
                liquidIsVoid(mode), true, 0, KitRules.Objective.NONE, 2.5, KitRules.DeathAction.LOSE_ROUND, 1, 3, false, 7, 0, true,
                config().getInt("modes.bot-duel.max-duration-seconds", 300));
        KitSpec spec = new KitSpec(kitId.isEmpty() ? "default" : kitId, config().getString("modes." + mode + ".display", mode), version,
                pvp.combat().profile(version), kit, rules, mode, List.of(), KitSpec.Bot.DEFAULT);
        BotGame game = mode.equals(ClickTestGame.MODE)
                ? new ClickTestGame(this, games, arena, p, spec, difficulty, pvp.combat())
                : new BotDuelGame(this, games, arena, p, spec, difficulty, pvp.combat());
        plugin.messages().send(p, "bots.starting", "mode", game.displayName(), "version", version.id(),
                "difficulty", difficulty.display(), "arena", arena.display());
        return games.games().start(game, List.of(p));
    }

    /**
     * Дуэль с ботом по киту из модуля дуэлей. Арена — по режиму кита (duel/sumo/...) и его белому списку.
     */
    public boolean startKit(Player p, KitSpec spec, BotDifficulty difficulty) {
        GameModule games = plugin.module(GameModule.class);
        ArenaModule arenas = plugin.module(ArenaModule.class);
        PvpModule pvp = plugin.module(PvpModule.class);
        if (games.games().inGame(p)) {
            plugin.messages().send(p, "game.already-in-game");
            return false;
        }
        List<Arena> list = arenas.arenas().forMode(spec.arenaMode()).stream()
                .filter(a -> spec.arenas().isEmpty() || spec.arenas().contains(a.id()))
                .filter(a -> (a.spawn("p1") != null || a.spawn("player") != null) && (a.spawn("p2") != null || a.spawn("bot") != null))
                .toList();
        if (list.isEmpty()) {
            plugin.messages().send(p, "bots.no-arena", "mode", spec.display());
            return false;
        }
        Arena arena = list.get(ThreadLocalRandom.current().nextInt(list.size()));
        BotDuelGame game = new BotDuelGame(this, games, arena, p, spec, difficulty, pvp.combat());
        plugin.messages().send(p, "bots.starting", "mode", spec.display(), "version", spec.version().id(),
                "difficulty", difficulty.display(), "arena", arena.display());
        return games.games().start(game, List.of(p));
    }

    public String botName(BotDifficulty d) {
        List<String> names = config().getStringList("bot.names");
        String name = names.isEmpty() ? "Miharu" : names.get(ThreadLocalRandom.current().nextInt(names.size()));
        return name.replace("{difficulty}", d.id());
    }

    /** Скин бота по bot.skin.mode: none | opponent | fixed | name. */
    public Skin skin(Player opponent) {
        String mode = config().getString("bot.skin.mode", "none").toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "opponent" -> {
                for (ProfileProperty prop : opponent.getPlayerProfile().getProperties()) {
                    if (prop.getName().equals("textures")) {
                        yield new Skin(prop.getValue(), prop.getSignature());
                    }
                }
                yield null;
            }
            case "fixed" -> {
                String v = config().getString("bot.skin.value", "");
                yield v.isEmpty() ? null : new Skin(v, config().getString("bot.skin.signature", ""));
            }
            case "name" -> namedSkin;
            default -> null;
        };
    }

    public boolean liquidIsVoid(String mode) {
        return config().getBoolean("modes." + mode + ".liquid-is-void", ClickTestGame.MODE.equals(mode));
    }

    /** Очки клик-теста: rank-by: clicks | hits. */
    public int clickTestScore(int clicks, int hits) {
        return "hits".equalsIgnoreCase(config().getString("modes.clicktest.rank-by", "clicks")) ? hits : clicks;
    }

    public void suppressKnockback(Player p) {
        suppressKnockback.add(p.getUniqueId());
    }

    public void releaseKnockback(Player p) {
        suppressKnockback.remove(p.getUniqueId());
    }

    // --- события ---

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onKnockback(EntityKnockbackEvent e) {
        if (suppressKnockback.contains(e.getEntity().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        stats.load(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stats.unload(e.getPlayer().getUniqueId());
        suppressKnockback.remove(e.getPlayer().getUniqueId());
    }
}
