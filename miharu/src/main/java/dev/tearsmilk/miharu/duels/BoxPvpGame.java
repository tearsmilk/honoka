package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.arena.Arena;
import dev.tearsmilk.miharu.core.kit.Kit;
import dev.tearsmilk.miharu.core.kit.KitGame;
import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.core.menu.Menu;
import dev.tearsmilk.miharu.core.util.Items;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.game.Game;
import dev.tearsmilk.miharu.game.GameModule;
import dev.tearsmilk.miharu.pvp.CombatService;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * BoxPvP 1x4: мини-лобби (pre1…pre4) — 30 секунд выбрать класс (Мечник, Лучник, Танк, Поддержка, Хиллер, Ассасин),
 * потом на точки p1…p4. В центре 3x3 белой шерсти (точка box — центральный блок): поставь все 9 своего цвета
 * и удержи 3 секунды. Смерть — 5 секунд в мини-лобби (можно сменить кит) и снова в бой.
 */
public final class BoxPvpGame extends Game implements KitGame {

    public static final String MODE = "boxpvp";
    static final NamespacedKey SELECTOR = new NamespacedKey("miharu", "box_kit");
    static final NamespacedKey TEAM_SELECTOR = new NamespacedKey("miharu", "box_team");

    private final DuelsModule duels;
    private final KitSpec spec;
    private final CombatService combat;
    private final List<Player> order;
    private final List<KitSpec> choices = new ArrayList<>();
    private final Map<KitSpec, ConfigurationSection> icons = new HashMap<>();
    private final Map<UUID, KitSpec> chosen = new HashMap<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, UUID> lastHit = new HashMap<>();
    private final Set<UUID> respawning = new HashSet<>();
    private final List<Block> box = new ArrayList<>();
    private boolean selecting = true;
    private int selectLeft;
    private final int teamSize;
    /** Выбранные команды. В 1x4 — сразу по порядку; в командных режимах заполняется выбором, остальных докидывает в конце выбора. */
    private final Map<UUID, Kit.Team> teams = new HashMap<>();
    private Kit.Team holder;
    private int holdTicks;
    private boolean finished;
    private String winnerName = "-";

    BoxPvpGame(DuelsModule duels, GameModule games, Arena arena, KitSpec spec, CombatService combat, List<Player> players) {
        super(games, MODE, arena);
        this.duels = duels;
        this.spec = spec;
        this.combat = combat;
        this.order = new ArrayList<>(players);
        this.teamSize = duels.teamSize(spec);
        if (teamSize <= 1) {
            for (Player p : order) {
                teams.put(p.getUniqueId(), Kit.Team.values()[index(p) % Kit.Team.values().length]);
            }
        }
        // классы BoxPvP — modules/duels/minigames/boxpvp.yml → classes
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(duels.folder(), "minigames/boxpvp.yml"));
        ConfigurationSection cls = y.getConfigurationSection("classes");
        if (cls != null) {
            for (String key : cls.getKeys(false)) {
                ConfigurationSection c = cls.getConfigurationSection(key);
                if (c == null || !c.getBoolean("enabled", true)) {
                    continue;
                }
                String id = "box-" + key;
                KitSpec k = new KitSpec(id, c.getString("display", key), CombatVersion.MODERN,
                        combat.derive(CombatVersion.MODERN, c.getConfigurationSection("combat")), new Kit(id, c), spec.rules(),
                        spec.arenaMode(), List.of(), KitSpec.Bot.DISABLED);
                choices.add(k);
                if (c.isConfigurationSection("icon")) {
                    icons.put(k, c.getConfigurationSection("icon"));
                }
            }
        }
        if (choices.isEmpty()) {
            // классов нет — как раньше: случайные киты дуэлей 1.9
            List<KitSpec> pool = new ArrayList<>();
            for (DuelKits.Entry e : duels.modernKits().all()) {
                if (e.enabled()) {
                    pool.add(e.spec());
                }
            }
            Collections.shuffle(pool);
            choices.addAll(pool.subList(0, Math.min(cfg().getInt("kit-choices", 6), pool.size())));
        }
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = duels.config().getConfigurationSection("boxpvp");
        return s != null ? s : duels.config().createSection("boxpvp");
    }

    @Override
    public KitSpec kitSpec() {
        return spec;
    }

    @Override
    public CombatVersion combatVersion() {
        return CombatVersion.MODERN;
    }

    @Override
    public String displayName() {
        return Text.plain(Text.mm(spec.display()));
    }

    private int index(Player p) {
        return Math.max(0, order.indexOf(p));
    }

    /** Команда игрока; пока не выбрана — временная по порядку (0,4,8… — красные, 1,5,9… — синие…). */
    private int teamIndex(Player p) {
        return team(p).ordinal();
    }

    private Kit.Team team(Player p) {
        Kit.Team t = teams.get(p.getUniqueId());
        return t != null ? t : Kit.Team.values()[index(p) % Kit.Team.values().length];
    }

    boolean teamMode() {
        return teamSize > 1;
    }

    private Material wool(Kit.Team t) {
        return switch (t) {
            case RED -> Material.RED_WOOL;
            case BLUE -> Material.BLUE_WOOL;
            case GREEN -> Material.LIME_WOOL;
            case YELLOW -> Material.YELLOW_WOOL;
        };
    }

    private List<Player> members(Kit.Team t) {
        List<Player> list = new ArrayList<>();
        for (Player p : order) {
            if (players.contains(p) && teams.get(p.getUniqueId()) == t) {
                list.add(p);
            }
        }
        return list;
    }

    private boolean sameTeam(Player a, Player b) {
        Kit.Team ta = teams.get(a.getUniqueId());
        return teamSize > 1 && ta != null && ta == teams.get(b.getUniqueId());
    }

    /** Имя для сообщений: в 1x4 — ник, в командах — «Красные». */
    private String teamName(Kit.Team t) {
        if (teamSize <= 1) {
            for (Player p : order) {
                if (team(p) == t) {
                    return p.getName();
                }
            }
        }
        return plugin.messages().raw("boxpvp.team-" + t.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** Класс занят напарником по команде. */
    private Player takenBy(Player p, KitSpec k) {
        if (teamSize <= 1) {
            return null;
        }
        Kit.Team mine = teams.get(p.getUniqueId());
        if (mine == null) {
            return null; // команда ещё не выбрана
        }
        for (Player o : members(mine)) {
            if (o != p && chosen.get(o.getUniqueId()) == k) {
                return o;
            }
        }
        return null;
    }

    private Material wool(Player p) {
        return wool(team(p));
    }

    @Override
    protected Location spawnFor(Player p) {
        int n = teamIndex(p) + 1; // точки p1…p4 / pre1…pre4 — по одной на команду
        return selecting || respawning.contains(p.getUniqueId()) ? instance.spawn("pre" + n, "p" + n) : instance.spawn("p" + n, "p1");
    }

    @Override
    protected boolean allowHunger() {
        return false;
    }

    // --- выбор кита ---

    private ItemStack selectorItem() {
        ItemStack it = Items.parse(cfg().getConfigurationSection("selector"), Map.of());
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(SELECTOR, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    boolean canChoose(Player p) {
        return (selecting || respawning.contains(p.getUniqueId())) && players.contains(p);
    }

    void openSelect(Player p) {
        if (!canChoose(p)) {
            return;
        }
        ConfigurationSection m = cfg().getConfigurationSection("menu");
        Menu menu = new Menu(3, Text.mm(m.getString("title", "Выбор кита")));
        List<Integer> slots = m.getIntegerList("slots");
        for (int i = 0; i < choices.size() && i < slots.size(); i++) {
            KitSpec k = choices.get(i);
            DuelKits.Entry e = duels.modernKits().get(k.id());
            ConfigurationSection iconCfg = icons.containsKey(k) ? icons.get(k)
                    : e != null && e.icon() != null ? e.icon() : m.getConfigurationSection("default-icon");
            Map<String, Object> ph = new HashMap<>();
            ph.put("kit", k.display());
            ph.put("queue", 0);
            ph.put("playing", 0);
            ph.put("wins", 0);
            ph.put("losses", 0);
            ph.put("bot", "");
            ph.put("version", "1.9");
            ItemStack icon = Items.parse(iconCfg, ph);
            ItemMeta meta = icon.getItemMeta();
            boolean mine = chosen.get(p.getUniqueId()) == k;
            Player taken = takenBy(p, k);
            List<String> lore = new ArrayList<>(iconCfg != null ? iconCfg.getStringList("lore") : List.of());
            lore.add("");
            lore.add(mine ? plugin.messages().raw("boxpvp.lore-chosen")
                    : taken != null ? plugin.messages().raw("boxpvp.lore-taken").replace("{player}", taken.getName())
                    : plugin.messages().raw("boxpvp.lore-choose"));
            meta.lore(Text.lore(lore, ph));
            meta.setEnchantmentGlintOverride(mine);
            icon.setItemMeta(meta);
            menu.set(slots.get(i), icon, pl -> {
                if (!canChoose(pl)) {
                    pl.closeInventory();
                    return;
                }
                Player tk = takenBy(pl, k);
                if (tk != null) {
                    plugin.messages().send(pl, "boxpvp.class-taken", "kit", k.display(), "player", tk.getName());
                    pl.playSound(pl.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                    return;
                }
                chosen.put(pl.getUniqueId(), k);
                for (Player mate : members(team(pl))) {
                    if (mate != pl) {
                        plugin.messages().send(mate, "boxpvp.mate-chosen", "player", pl.getName(), "kit", k.display());
                        if (mate.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu && canChoose(mate)) {
                            openSelect(mate); // обновить «занято»
                        }
                    }
                }
                pl.playSound(pl.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
                plugin.messages().send(pl, "boxpvp.class-chosen", "kit", k.display());
                pl.closeInventory();
            });
        }
        menu.fill(Items.parse(m.getConfigurationSection("filler"), Map.of()));
        menu.open(p);
    }

    // --- выбор команды (2x4 / 3x4 / 4x4) ---

    private ItemStack teamSelectorItem(Player p) {
        ConfigurationSection c = cfg().getConfigurationSection("team-selector");
        ItemStack it = Items.parse(c, Map.of("team", teams.containsKey(p.getUniqueId()) ? teamName(team(p)) : plugin.messages().raw("boxpvp.team-none")));
        if (teams.containsKey(p.getUniqueId())) {
            it = it.withType(wool(team(p)));
        }
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(TEAM_SELECTOR, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    void openTeams(Player p) {
        if (!selecting || !teamMode() || !players.contains(p)) {
            return;
        }
        ConfigurationSection m = cfg().getConfigurationSection("team-menu");
        Menu menu = new Menu(3, Text.mm(m.getString("title", "Выбор команды")));
        List<Integer> slots = m.getIntegerList("slots");
        Kit.Team[] all = Kit.Team.values();
        for (int i = 0; i < all.length && i < slots.size(); i++) {
            Kit.Team t = all[i];
            List<Player> mem = members(t);
            List<String> lore = new ArrayList<>();
            lore.add(plugin.messages().raw("boxpvp.team-lore-count").replace("{count}", String.valueOf(mem.size())).replace("{size}", String.valueOf(teamSize)));
            for (Player o : mem) {
                KitSpec k = chosen.get(o.getUniqueId());
                lore.add("<dark_gray>• <white>" + o.getName() + (k == null ? "" : " <gray>(" + k.display() + "<gray>)"));
            }
            lore.add("");
            lore.add(plugin.messages().raw(teams.get(p.getUniqueId()) == t ? "boxpvp.team-lore-yours"
                    : mem.size() >= teamSize ? "boxpvp.team-lore-full" : "boxpvp.team-lore-join"));
            ItemStack icon = new ItemStack(wool(t), Math.max(1, mem.size()));
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Text.item(teamName(t), Map.of()));
            meta.lore(Text.lore(lore, Map.of()));
            meta.setEnchantmentGlintOverride(teams.get(p.getUniqueId()) == t);
            icon.setItemMeta(meta);
            menu.set(slots.get(i), icon, pl -> joinTeam(pl, t));
        }
        menu.fill(Items.parse(cfg().getConfigurationSection("menu.filler"), Map.of()));
        menu.open(p);
    }

    private void joinTeam(Player p, Kit.Team t) {
        if (!selecting || !players.contains(p)) {
            p.closeInventory();
            return;
        }
        if (teams.get(p.getUniqueId()) == t) {
            openSelect(p);
            return;
        }
        if (members(t).size() >= teamSize) {
            plugin.messages().send(p, "boxpvp.team-full", "team", teamName(t));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        teams.put(p.getUniqueId(), t);
        KitSpec k = chosen.get(p.getUniqueId());
        if (k != null && takenBy(p, k) != null) {
            chosen.remove(p.getUniqueId()); // в новой команде этот класс уже занят
            plugin.messages().send(p, "boxpvp.class-reset", "kit", k.display());
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
        for (Player o : players) {
            plugin.messages().send(o, "boxpvp.team-joined", "player", p.getName(), "team", teamName(t));
        }
        p.teleport(spawnFor(p));
        p.getInventory().setItem(cfg().getInt("team-selector.slot", 3), teamSelectorItem(p));
        // обновить открытые меню команд у остальных
        for (Player o : players) {
            if (o != p && o.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu
                    && Text.plain(o.getOpenInventory().title()).equals(Text.plain(Text.mm(cfg().getString("team-menu.title", "")))) ) {
                openTeams(o);
            }
        }
        openSelect(p);
    }

    /** Конец выбора: кто не выбрал команду — в самые маленькие команды; повторы классов в команде — сброс на свободный. */
    private void assignTeams() {
        if (!teamMode()) {
            return;
        }
        List<Player> rest = new ArrayList<>();
        for (Player p : order) {
            if (players.contains(p) && !teams.containsKey(p.getUniqueId())) {
                rest.add(p);
            }
        }
        Collections.shuffle(rest);
        for (Player p : rest) {
            Kit.Team best = null;
            for (Kit.Team t : Kit.Team.values()) {
                if (best == null || members(t).size() < members(best).size()) {
                    best = t;
                }
            }
            teams.put(p.getUniqueId(), best);
            plugin.messages().send(p, "boxpvp.team-auto", "team", teamName(best));
        }
        for (Player p : order) {
            KitSpec k = chosen.get(p.getUniqueId());
            if (k != null && takenBy(p, k) != null && rest.contains(p)) {
                chosen.remove(p.getUniqueId());
                plugin.messages().send(p, "boxpvp.class-reset", "kit", k.display());
            }
        }
    }

    private void toLobby(Player p) {
        p.getInventory().clear();
        for (PotionEffect e : p.getActivePotionEffects()) {
            p.removePotionEffect(e.getType());
        }
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
        p.setFireTicks(0);
        p.setGameMode(GameMode.ADVENTURE);
        p.teleport(spawnFor(p));
        p.getInventory().setItem(cfg().getInt("selector.slot", 4), selectorItem());
        if (selecting && teamMode()) {
            p.getInventory().setItem(cfg().getInt("team-selector.slot", 3), teamSelectorItem(p));
        }
    }

    // --- старт ---

    @Override
    protected void onSetup() {
        instance.world().setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, true);
        instance.world().setGameRule(GameRules.FALL_DAMAGE, true);
        if (instance.has("box")) {
            Block center = instance.spawn("box").getBlock().getRelative(BlockFace.DOWN);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block b = center.getRelative(dx, 0, dz);
                    b.setType(Material.WHITE_WOOL, false);
                    box.add(b);
                }
            }
        }
        selectLeft = Math.max(5, cfg().getInt("select-seconds", 30)) * 20;
        for (Player p : order) {
            toLobby(p);
        }
    }

    @Override
    protected void onRunning() {
        super.onRunning();
        for (Player p : order) {
            plugin.messages().send(p, teamMode() ? "boxpvp.team-select-started" : "boxpvp.class-select-started", "seconds", selectLeft / 20);
            if (teamMode()) {
                openTeams(p);
            } else {
                openSelect(p);
            }
        }
    }

    private KitSpec kitOf(Player p) {
        KitSpec k = chosen.get(p.getUniqueId());
        if (k == null && !choices.isEmpty()) {
            List<KitSpec> free = new ArrayList<>();
            for (KitSpec c : choices) {
                if (takenBy(p, c) == null) {
                    free.add(c);
                }
            }
            List<KitSpec> from = free.isEmpty() ? choices : free;
            k = from.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(from.size()));
            chosen.put(p.getUniqueId(), k);
            plugin.messages().send(p, "boxpvp.class-random", "kit", k.display());
        }
        return k;
    }

    private void toFight(Player p) {
        respawning.remove(p.getUniqueId());
        if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) {
            p.closeInventory();
        }
        KitSpec k = kitOf(p);
        p.setGameMode(GameMode.SURVIVAL);
        p.teleport(spawnFor(p));
        p.setFallDistance(0);
        if (k != null) {
            combat.force(p, k.profile());
            k.kit().apply(p, combat, k.profile(), team(p));
        }
        var inv = p.getInventory();
        Map<Integer, ItemStack> left = inv.addItem(new ItemStack(wool(p), cfg().getInt("wool", 64)), new ItemStack(Material.SHEARS));
        left.values().forEach(it -> p.getWorld().dropItem(p.getLocation(), it));
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
    }

    @Override
    protected void onTick() {
        if (selecting) {
            selectLeft--;
            if (selectLeft % 20 == 0) {
                for (Player p : players) {
                    plugin.messages().actionBar(p, "boxpvp.class-actionbar", "seconds", selectLeft / 20);
                }
            }
            if (selectLeft <= 0) {
                assignTeams();
                selecting = false;
                for (Player p : List.copyOf(players)) {
                    toFight(p);
                    plugin.messages().title(p, "boxpvp.fight");
                }
            }
            return;
        }
        checkBox();
        if (runningTicks % 10 == 0) {
            for (Player p : players) {
                if (!respawning.contains(p.getUniqueId())) {
                    plugin.messages().actionBar(p, "boxpvp.fight-actionbar", placeholders(p).entrySet().stream().flatMap(en -> Stream.of(en.getKey(), en.getValue())).toArray());
                }
            }
        }
        if (spec.rules().maxDurationSeconds() > 0 && runningTicks >= spec.rules().maxDurationSeconds() * 20) {
            Kit.Team best = null;
            int bestCount = -1;
            for (Kit.Team t : Kit.Team.values()) {
                int c = ownBlocks(t);
                if (!members(t).isEmpty() && c > bestCount) {
                    bestCount = c;
                    best = t;
                }
            }
            finish(best);
        }
    }

    private int ownBlocks(Kit.Team t) {
        Material w = wool(t);
        int n = 0;
        for (Block b : box) {
            if (b.getType() == w) {
                n++;
            }
        }
        return n;
    }

    /** Все 9 блоков одного цвета игрока — держим таймер; любой чужой блок сбрасывает его. */
    private void checkBox() {
        if (box.isEmpty()) {
            return;
        }
        Material first = box.getFirst().getType();
        Kit.Team owner = null;
        if (box.stream().allMatch(b -> b.getType() == first)) {
            for (Kit.Team t : Kit.Team.values()) {
                if (wool(t) == first && !members(t).isEmpty()) {
                    owner = t;
                }
            }
        }
        if (owner == null) {
            if (holder != null) {
                broadcast("boxpvp.box-lost", "player", teamName(holder));
            }
            holder = null;
            holdTicks = 0;
            return;
        }
        if (owner != holder) {
            holder = owner;
            holdTicks = 0;
            broadcast("boxpvp.box-captured", "player", teamName(owner), "seconds", cfg().getInt("hold-time", 3));
            for (Player m : members(owner)) {
                m.playSound(m.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
            }
        }
        holdTicks++;
        if (holdTicks >= cfg().getInt("hold-time", 3) * 20) {
            finish(owner);
        }
    }

    // --- урон, смерть ---

    @Override
    protected boolean onDamage(Player p, EntityDamageEvent e) {
        if (selecting || respawning.contains(p.getUniqueId())) {
            e.setCancelled(true);
            return true;
        }
        if (e instanceof EntityDamageByEntityEvent ed) {
            Player attacker = ed.getDamager() instanceof Player dp ? dp
                    : ed.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player sp ? sp : null;
            if (attacker != null && attacker != p) {
                if (respawning.contains(attacker.getUniqueId()) || sameTeam(attacker, p)) {
                    e.setCancelled(true);
                    return true;
                }
                lastHit.put(p.getUniqueId(), attacker.getUniqueId());
            }
        }
        return false;
    }

    @Override
    protected void onLethal(Player p, EntityDamageEvent e) {
        died(p);
    }

    @Override
    protected void onVoid(Player p) {
        if (selecting || respawning.contains(p.getUniqueId())) {
            p.teleport(spawnFor(p));
            return;
        }
        died(p);
    }

    private void died(Player p) {
        UUID kid = lastHit.remove(p.getUniqueId());
        Player killer = kid == null ? null : Bukkit.getPlayer(kid);
        if (killer != null && players.contains(killer)) {
            kills.merge(kid, 1, Integer::sum);
            broadcast("duels.killed", "player", p.getName(), "killer", killer.getName());
        }
        respawning.add(p.getUniqueId());
        toLobby(p);
        int sec = Math.max(1, cfg().getInt("respawn-seconds", 5));
        plugin.messages().send(p, "boxpvp.respawn-class", "seconds", sec);
        for (int i = 0; i < sec; i++) {
            int left = sec - i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (players.contains(p) && respawning.contains(p.getUniqueId()) && !finished) {
                    plugin.messages().title(p, "minigames.respawning", "seconds", left);
                }
            }, i * 20L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (players.contains(p) && !finished && state == State.RUNNING && p.isOnline()) {
                toFight(p);
            }
        }, sec * 20L);
    }

    @Override
    protected void onPlayerLeave(Player p, boolean quit) {
        if (finished || !(state == State.RUNNING || state == State.COUNTDOWN)) {
            return;
        }
        duels.stats().add(p, spec.id(), false);
        List<Player> rest = new ArrayList<>(players);
        rest.remove(p);
        Set<Kit.Team> teams = new HashSet<>();
        for (Player o : rest) {
            teams.add(team(o));
        }
        if (teams.size() <= 1) {
            Bukkit.getScheduler().runTask(plugin, () -> finish(teams.isEmpty() ? null : teams.iterator().next()));
        }
    }

    /** Вредные взрывные зелья (медлительность, слабость…) не действуют на напарников. */
    void filterSplash(org.bukkit.event.entity.PotionSplashEvent e) {
        if (teamSize <= 1 || !(e.getPotion().getShooter() instanceof Player shooter)) {
            return;
        }
        boolean harmful = e.getPotion().getEffects().stream()
                .anyMatch(ef -> ef.getType().getCategory() == org.bukkit.potion.PotionEffectTypeCategory.HARMFUL);
        if (!harmful) {
            return;
        }
        for (var le : e.getAffectedEntities()) {
            if (le instanceof Player t && t != shooter && sameTeam(shooter, t)) {
                e.setIntensity(le, 0);
            }
        }
    }

    // --- стройка: только шерсть в квадрате 3x3 ---

    private boolean inBox(Block b) {
        for (Block x : box) {
            if (x.getX() == b.getX() && x.getY() == b.getY() && x.getZ() == b.getZ()) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean canPlace(Player p, Block block, BlockState replaced) {
        return !selecting && !respawning.contains(p.getUniqueId()) && inBox(block) && block.getType() == wool(p) && replaced.getType().isAir();
    }

    @Override
    protected boolean canBreak(Player p, Block block) {
        return !selecting && !respawning.contains(p.getUniqueId()) && inBox(block) && Tag.WOOL.isTagged(block.getType()) && block.getType() != wool(p);
    }

    // --- конец ---

    private void finish(Kit.Team w) {
        if (finished) {
            return;
        }
        finished = true;
        winnerName = w == null ? "-" : teamName(w);
        for (Player p : order) {
            if (players.contains(p)) {
                duels.stats().add(p, spec.id(), team(p) == w);
            }
        }
        for (Player p : List.copyOf(players)) {
            Object[] kv = placeholders(p).entrySet().stream().flatMap(en -> Stream.of(en.getKey(), en.getValue())).toArray();
            boolean won = team(p) == w;
            plugin.messages().title(p, won ? "minigames.win" : "minigames.ffa-end", kv);
            plugin.messages().sendList(p, "boxpvp.summary", kv);
            p.playSound(p.getLocation(), won ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        }
        end();
    }

    private void broadcast(String key, Object... kv) {
        for (Player p : players) {
            plugin.messages().send(p, key, kv);
        }
        for (Player p : spectators()) {
            plugin.messages().send(p, key, kv);
        }
    }

    @Override
    public Map<String, Object> placeholders(Player viewer) {
        Map<String, Object> map = super.placeholders(viewer);
        map.put("kit", chosen.containsKey(viewer.getUniqueId()) ? chosen.get(viewer.getUniqueId()).display() : "-");
        map.put("phase", plugin.messages().raw(selecting ? "boxpvp.phase-class" : "boxpvp.phase-fight"));
        map.put("select_left", Text.time(Math.max(0, selectLeft / 20)));
        map.put("holder", holder == null ? "-" : teamName(holder));
        int hold = cfg().getInt("hold-time", 3);
        map.put("hold_left", holder == null ? "-" : Text.time(Math.max(0, hold - holdTicks / 20)));
        map.put("own_blocks", order.contains(viewer) ? ownBlocks(team(viewer)) : 0);
        map.put("team", order.contains(viewer) ? plugin.messages().raw("boxpvp.team-" + team(viewer).name().toLowerCase(java.util.Locale.ROOT)) : "-");
        map.put("format", duels.format(spec));
        StringBuilder mates = new StringBuilder();
        if (order.contains(viewer)) {
            for (Player m : members(team(viewer))) {
                if (m != viewer) {
                    KitSpec mk = chosen.get(m.getUniqueId());
                    mates.append(mates.isEmpty() ? "" : "<gray>, ").append("<white>").append(m.getName())
                            .append(mk == null ? "" : " <gray>(" + mk.display() + "<gray>)");
                }
            }
        }
        map.put("teammates", mates.isEmpty() ? "-" : mates.toString());
        map.put("kills", kills.getOrDefault(viewer.getUniqueId(), 0));
        map.put("players_left", players.size());
        map.put("winner", winnerName);
        map.put("player_hp", Text.num(Math.round(viewer.getHealth() * 10) / 10.0));
        return map;
    }
}
