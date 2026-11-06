package dev.tearsmilk.miharu.xp;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.core.db.Database;
import dev.tearsmilk.miharu.core.module.MiharuModule;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.game.GameModule;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Валюта «Опыт»: N за сыгранную игру и M за победу (modules/xp/config.yml), хранится в data.db → miharu_xp.
 * Скорборд лобби — {xp}, PlaceholderAPI — %miharu_xp%. Голограмма с топом — /xptop place (OP).
 */
public final class XpModule extends MiharuModule implements Listener {

    private static final NamespacedKey HOLO = new NamespacedKey("miharu", "xp_top");

    public record Entry(String name, long xp) {
    }

    private final Map<UUID, Long> cache = new ConcurrentHashMap<>();
    private volatile List<Entry> top = List.of();
    private final List<Location> holograms = new ArrayList<>();
    private BukkitTask task;
    private BukkitTask trimTask;
    private final ArmorTrims trims = new ArmorTrims(this);

    /** Меню «Экипировка» (отделки брони). */
    public void openTrims(Player p) {
        trims.open(p);
    }

    public XpModule(Miharu plugin) {
        super(plugin, "xp");
    }

    private Database db() {
        return plugin.database();
    }

    @Override
    protected void onEnable() {
        config();
        db().schema("CREATE TABLE IF NOT EXISTS miharu_xp (uuid VARCHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(16) NOT NULL, xp BIGINT NOT NULL DEFAULT 0)");
        listen(this);
        trims.load();
        Bukkit.getOnlinePlayers().forEach(p -> {
            load(p.getUniqueId());
            trims.loadPlayer(p.getUniqueId());
        });
        trimTask = Bukkit.getScheduler().runTaskTimer(plugin, trims::tick, 20L, 20L);
        plugin.registerCommand("trims", "Экипировка: отделки брони", List.of("armortrims"), (source, args) -> {
            if (source.getSender() instanceof Player p) {
                trims.open(p);
            }
        });
        plugin.hooks().placeholder("xp", (p, rest) -> p == null ? "0" : String.valueOf(xp(p.getUniqueId())));
        plugin.registerCommand("xp", "Опыт игрока", List.of("exp"), new XpCommand());
        plugin.registerCommand("xptop", "Голограмма топа по опыту", List.of(), new TopCommand());
        loadHolograms();
        int every = Math.max(10, config().getInt("hologram.refresh-seconds", 60));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshTop, 40L, every * 20L);
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
        }
        if (trimTask != null) {
            trimTask.cancel();
        }
        removeHologramEntities();
        cache.clear();
    }

    // --- данные ---

    private void load(UUID id) {
        db().query("SELECT xp FROM miharu_xp WHERE uuid = ?", rs -> rs.next() ? rs.getLong(1) : 0L, id.toString())
                .thenAccept(v -> cache.put(id, v));
    }

    public long xp(UUID id) {
        return cache.getOrDefault(id, 0L);
    }

    public void add(OfflinePlayer p, long amount) {
        long now = Math.max(0, xp(p.getUniqueId()) + amount);
        set(p, now);
    }

    public void set(OfflinePlayer p, long value) {
        long v = Math.max(0, value);
        cache.put(p.getUniqueId(), v);
        String name = p.getName() == null ? "?" : p.getName();
        db().upsert("UPDATE miharu_xp SET xp = ?, name = ? WHERE uuid = ?", new Object[]{v, name, p.getUniqueId().toString()},
                "INSERT INTO miharu_xp (uuid, name, xp) VALUES (?, ?, ?)", new Object[]{p.getUniqueId().toString(), name, v});
    }

    /**
     * Итог игры для игрока: опыт за сыгранную игру (+ за победу). Кто вышел из игры (/leave, выход) — без опыта.
     * kit — id режима (mg-bedwars-4, m-diamond, custom-18…), для своих сумм — modes.&lt;префикс&gt;.
     */
    public static void award(Miharu plugin, Player p, String kit, boolean win) {
        XpModule m = plugin.module(XpModule.class);
        GameModule games = plugin.module(GameModule.class);
        if (m == null || p == null || !p.isOnline() || (games != null && games.games().leaving(p))) {
            return;
        }
        m.give(p, kit, win);
    }

    private void give(Player p, String kit, boolean win) {
        ConfigurationSection c = config();
        long play = c.getLong("play", 5);
        long winXp = c.getLong("win", 15);
        ConfigurationSection modes = c.getConfigurationSection("modes");
        if (modes != null && kit != null) {
            String best = null;
            for (String key : modes.getKeys(false)) {
                if (kit.toLowerCase(Locale.ROOT).startsWith(key.toLowerCase(Locale.ROOT)) && (best == null || key.length() > best.length())) {
                    best = key;
                }
            }
            if (best != null) {
                play = modes.getLong(best + ".play", play);
                winXp = modes.getLong(best + ".win", winXp);
            }
        }
        long total = play + (win ? winXp : 0);
        if (total <= 0) {
            return;
        }
        add(p, total);
        plugin.messages().send(p, win ? "xp.got-win" : "xp.got", "xp", total, "total", xp(p.getUniqueId()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        load(e.getPlayer().getUniqueId());
        trims.loadPlayer(e.getPlayer().getUniqueId());
        // имя в базе — для топа
        UUID id = e.getPlayer().getUniqueId();
        String name = e.getPlayer().getName();
        db().update("UPDATE miharu_xp SET name = ? WHERE uuid = ?", name, id.toString());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cache.remove(e.getPlayer().getUniqueId());
        trims.unload(e.getPlayer().getUniqueId());
    }

    // --- топ и голограммы ---

    private void refreshTop() {
        int n = Math.max(1, config().getInt("hologram.size", 10));
        db().query("SELECT name, xp FROM miharu_xp ORDER BY xp DESC LIMIT " + n, rs -> {
            List<Entry> list = new ArrayList<>();
            while (rs.next()) {
                list.add(new Entry(rs.getString(1), rs.getLong(2)));
            }
            return list;
        }).thenAccept(list -> {
            top = list;
            Bukkit.getScheduler().runTask(plugin, this::updateHolograms);
        });
    }

    private File holoFile() {
        return new File(folder(), "holograms.yml");
    }

    private void loadHolograms() {
        holograms.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(holoFile());
        for (String s : y.getStringList("top")) {
            String[] p = s.split(";");
            if (p.length < 4) {
                continue;
            }
            World w = Bukkit.getWorld(p[0]);
            holograms.add(new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3])));
            if (w == null) {
                holograms.getLast().setWorld(null);
                pendingWorlds.add(p[0]);
            }
        }
        rawHolos = y.getStringList("top");
    }

    private final List<String> pendingWorlds = new ArrayList<>();
    private List<String> rawHolos = new ArrayList<>();

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        if (pendingWorlds.remove(e.getWorld().getName())) {
            loadHolograms();
            updateHolograms();
        }
    }

    private void saveHolograms() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> list = new ArrayList<>();
        for (Location l : holograms) {
            if (l.getWorld() != null) {
                list.add(l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ());
            }
        }
        for (String raw : rawHolos) {
            String w = raw.split(";")[0];
            if (Bukkit.getWorld(w) == null && !list.contains(raw)) {
                list.add(raw); // мир ещё не загружен — не теряем
            }
        }
        y.set("top", list);
        rawHolos = list;
        try {
            y.save(holoFile());
        } catch (IOException ex) {
            plugin.getLogger().warning("Не удалось сохранить голограммы: " + ex.getMessage());
        }
    }

    private void removeHologramEntities() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity d : w.getEntities()) {
                if ((d instanceof TextDisplay || d instanceof org.bukkit.entity.ArmorStand) && d.getPersistentDataContainer().has(HOLO)) {
                    d.remove();
                }
            }
        }
    }

    /** Строки голограммы сверху вниз. */
    private List<String> hologramLines() {
        return java.util.Arrays.asList(hologramText().split("\n", -1));
    }

    private String hologramText() {
        ConfigurationSection h = config().getConfigurationSection("hologram");
        StringBuilder sb = new StringBuilder();
        for (String line : h.getStringList("header")) {
            sb.append(line).append('\n');
        }
        String fmt = h.getString("line", "<gray>#{place} <white>{player} <dark_gray>— <#7fd4ff>{xp}xp");
        List<Entry> list = top;
        int n = Math.max(1, h.getInt("size", 10));
        for (int i = 0; i < n; i++) {
            if (i < list.size()) {
                sb.append(fmt.replace("{place}", String.valueOf(i + 1)).replace("{player}", list.get(i).name()).replace("{xp}", String.valueOf(list.get(i).xp())));
            } else {
                sb.append(h.getString("empty-line", "<dark_gray>#{place} —").replace("{place}", String.valueOf(i + 1)));
            }
            sb.append('\n');
        }
        for (String line : h.getStringList("footer")) {
            sb.append(line).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Голограмма на армор-стендах (как HolographicDisplays): строка = невидимый маркер-стенд с видимым именем.
     * Стоит на месте, текст поворачивается к игроку как ник. Верхняя строка — в точке установки.
     */
    private void updateHolograms() {
        removeHologramEntities();
        List<String> lines = hologramLines();
        double spacing = config().getDouble("hologram.line-spacing", 0.27);
        for (Location base : holograms) {
            if (base.getWorld() == null) {
                continue;
            }
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue; // пустая строка — просто отступ
                }
                Location at = base.clone().add(0, -i * spacing, 0);
                net.kyori.adventure.text.Component text = Text.mm(line);
                at.getWorld().spawn(at, org.bukkit.entity.ArmorStand.class, st -> {
                    st.setInvisible(true);
                    st.setMarker(true);
                    st.setSmall(true);
                    st.setGravity(false);
                    st.setInvulnerable(true);
                    st.setSilent(true);
                    st.setPersistent(false);
                    st.setCanPickupItems(false);
                    st.setBasePlate(false);
                    st.customName(text);
                    st.setCustomNameVisible(true);
                    st.getPersistentDataContainer().set(HOLO, PersistentDataType.BYTE, (byte) 1);
                });
            }
        }
    }

    // --- команды ---

    private final class XpCommand implements BasicCommand {
        @Override
        public void execute(CommandSourceStack source, String[] args) {
            var s = source.getSender();
            if (args.length >= 3 && List.of("give", "take", "set").contains(args[0].toLowerCase(Locale.ROOT))) {
                if (!s.hasPermission("miharu.admin")) {
                    plugin.messages().send(s, "no-permission");
                    return;
                }
                OfflinePlayer t = Bukkit.getOfflinePlayer(args[1]);
                long n;
                try {
                    n = Long.parseLong(args[2]);
                } catch (NumberFormatException e) {
                    plugin.messages().send(s, "bad-number");
                    return;
                }
                if (!cache.containsKey(t.getUniqueId()) && !t.isOnline()) {
                    // оффлайн: читаем из базы и меняем
                    db().query("SELECT xp FROM miharu_xp WHERE uuid = ?", rs -> rs.next() ? rs.getLong(1) : 0L, t.getUniqueId().toString())
                            .thenAccept(v -> Bukkit.getScheduler().runTask(plugin, () -> {
                                cache.put(t.getUniqueId(), v);
                                change(s, t, args[0], n);
                                cache.remove(t.getUniqueId());
                            }));
                    return;
                }
                change(s, t, args[0], n);
                return;
            }
            Player target = args.length >= 1 ? Bukkit.getPlayerExact(args[0]) : s instanceof Player p ? p : null;
            if (target == null) {
                plugin.messages().send(s, "xp.usage");
                return;
            }
            plugin.messages().send(s, "xp.balance", "player", target.getName(), "xp", xp(target.getUniqueId()));
        }

        private void change(org.bukkit.command.CommandSender s, OfflinePlayer t, String op, long n) {
            switch (op.toLowerCase(Locale.ROOT)) {
                case "give" -> add(t, n);
                case "take" -> add(t, -n);
                default -> set(t, n);
            }
            plugin.messages().send(s, "xp.changed", "player", t.getName() == null ? "?" : t.getName(), "xp", xp(t.getUniqueId()));
        }

        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            List<String> out = new ArrayList<>();
            if (args.length <= 1) {
                if (source.getSender().hasPermission("miharu.admin")) {
                    out.addAll(List.of("give", "take", "set"));
                }
                Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            } else if (args.length == 2) {
                Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            }
            return out;
        }
    }

    private final class TopCommand implements BasicCommand {
        @Override
        public void execute(CommandSourceStack source, String[] args) {
            var s = source.getSender();
            if (!s.hasPermission("miharu.admin")) {
                plugin.messages().send(s, "no-permission");
                return;
            }
            if (!(s instanceof Player p)) {
                plugin.messages().send(s, "players-only");
                return;
            }
            String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "place", "set" -> {
                    holograms.add(p.getLocation().add(0, config().getDouble("hologram.height", 2.0), 0));
                    saveHolograms();
                    refreshTop();
                    plugin.messages().send(p, "xp.holo-placed");
                }
                case "remove" -> {
                    Location best = null;
                    for (Location l : holograms) {
                        if (l.getWorld() == p.getWorld() && l.distance(p.getLocation()) <= 6 && (best == null || l.distance(p.getLocation()) < best.distance(p.getLocation()))) {
                            best = l;
                        }
                    }
                    if (best == null) {
                        plugin.messages().send(p, "xp.holo-none");
                        return;
                    }
                    holograms.remove(best);
                    saveHolograms();
                    updateHolograms();
                    plugin.messages().send(p, "xp.holo-removed");
                }
                case "refresh" -> {
                    refreshTop();
                    plugin.messages().send(p, "xp.holo-refreshed");
                }
                default -> plugin.messages().send(p, "xp.holo-usage");
            }
        }

        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            return args.length <= 1 ? List.of("place", "remove", "refresh") : List.of();
        }
    }
}
