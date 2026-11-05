package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.arena.ArenaMode;
import dev.tearsmilk.miharu.arena.ArenaModule;
import dev.tearsmilk.miharu.core.kit.Kit;
import dev.tearsmilk.miharu.core.kit.KitRules;
import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.core.menu.Menu;
import dev.tearsmilk.miharu.core.util.Items;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.game.Game;
import dev.tearsmilk.miharu.game.GameModule;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import dev.tearsmilk.miharu.pvp.PvpModule;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Митапы: режимы mup-12 … mup-28 (1x2, 1x4, 1x8, 1x16, 2x2, 2x4, 2x8).
 * Арена: на каждого игрока точки p&lt;N&gt; (бой), pre&lt;N&gt; (коробка подготовки), loot&lt;N&gt;a / loot&lt;N&gt;b (шалкеры с лутом).
 * Настройки — modules/duels/config.yml → meetups.
 */
public final class Meetups implements Listener {

    static final NamespacedKey VOTE = new NamespacedKey("miharu", "meetup_vote");
    static final NamespacedKey START_VOTE = new NamespacedKey("miharu", "meetup_start_vote");

    /** Голоса за досрочный старт в очереди: режим → кто проголосовал. */
    private final Map<String, java.util.Set<java.util.UUID>> startVotes = new java.util.HashMap<>();

    /** Режим митапа: id (mup-14), подпись (1x4), размер команды, всего игроков. */
    public record Mode(String id, String display, int teamSize, int players) {
    }

    private final DuelsModule module;
    private final Map<String, Mode> modes = new LinkedHashMap<>();
    private final Map<String, KitSpec> specs = new LinkedHashMap<>();

    Meetups(DuelsModule module) {
        this.module = module;
    }

    ConfigurationSection cfg() {
        return module.config().getConfigurationSection("meetups");
    }

    void load() {
        modes.clear();
        specs.clear();
        ConfigurationSection m = cfg() == null ? null : cfg().getConfigurationSection("modes");
        if (m == null) {
            return;
        }
        ArenaModule arenas = module.plugin().module(ArenaModule.class);
        PvpModule pvp = module.plugin().module(PvpModule.class);
        for (String id : m.getKeys(false)) {
            ConfigurationSection s = m.getConfigurationSection(id);
            if (s == null || !s.getBoolean("enabled", true)) {
                continue;
            }
            int team = Math.max(1, s.getInt("team-size", 1));
            int players = Math.max(2, s.getInt("players", 2));
            Mode mode = new Mode(id, s.getString("display", id), team, players);
            modes.put(id, mode);
            // точки по порядку: p1, pre1, loot1a, loot1b, p2, …
            List<String> spawns = new ArrayList<>();
            for (int i = 1; i <= players; i++) {
                spawns.add("p" + i);
                spawns.add("pre" + i);
                spawns.add("loot" + i + "a");
                spawns.add("loot" + i + "b");
            }
            if (arenas != null) {
                arenas.registerMode(new ArenaMode(id, "Митап " + mode.display(), spawns));
            }
            KitRules rules = KitRules.load(cfg().getConfigurationSection("rules"), 1).withPlayers(players);
            String display = cfg().getString("display", "<#b48cff>Митап {mode}").replace("{mode}", mode.display());
            specs.put(id, new KitSpec(id, display, CombatVersion.LEGACY, pvp.combat().profile(CombatVersion.LEGACY),
                    new Kit(id, Map.of(), Map.of()), rules, id, List.of(), KitSpec.Bot.DISABLED));
        }
    }

    public Mode mode(String id) {
        return modes.get(id);
    }

    public KitSpec spec(String id) {
        return id == null ? null : specs.get(id);
    }

    public List<String> ids() {
        return new ArrayList<>(specs.keySet());
    }

    // --- лут шалкеров ---

    /**
     * Лут одного игрока: полный сет брони и оружие обязательно, остальное — случайные предметы
     * из всех дуэльных китов. Делится на два шалкера.
     */
    List<ItemStack>[] rollLoot() {
        List<ItemStack> catalog = ItemCatalog.collect(module.plugin()).stream().map(ItemCatalog.Found::item).toList();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<ItemStack> loot = new ArrayList<>();
        for (String part : List.of("_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS")) {
            List<ItemStack> pool = catalog.stream().filter(i -> i.getType().name().endsWith(part)).toList();
            if (!pool.isEmpty()) {
                loot.add(pool.get(r.nextInt(pool.size())).clone());
            }
        }
        List<ItemStack> weapons = catalog.stream().filter(i -> {
            String n = i.getType().name();
            return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.equals("MACE") || n.equals("TRIDENT");
        }).toList();
        if (!weapons.isEmpty()) {
            loot.add(weapons.get(r.nextInt(weapons.size())).clone());
        }
        int min = cfg().getInt("loot.extra-items.min", 8);
        int max = Math.max(min, cfg().getInt("loot.extra-items.max", 14));
        int extra = r.nextInt(min, max + 1);
        List<String> exclude = cfg().getStringList("loot.exclude");
        List<ItemStack> pool = catalog.stream().filter(i -> !exclude.contains(i.getType().name())).toList();
        for (int i = 0; i < extra && !pool.isEmpty(); i++) {
            loot.add(pool.get(r.nextInt(pool.size())).clone());
        }
        Collections.shuffle(loot);
        @SuppressWarnings("unchecked")
        List<ItemStack>[] boxes = new List[]{new ArrayList<>(), new ArrayList<>()};
        for (int i = 0; i < loot.size(); i++) {
            boxes[i % 2].add(loot.get(i));
        }
        return boxes;
    }

    Material shulker() {
        Material m = Material.matchMaterial(cfg().getString("loot.shulker", "PURPLE_SHULKER_BOX"));
        return m == null ? Material.PURPLE_SHULKER_BOX : m;
    }

    // --- голосование за досрочный старт (в очереди) ---

    // Быстрый старт для всех групповых режимов (мини-игры 1x3/1x4, BoxPvP, митапы): duels config → quick-start

    org.bukkit.configuration.ConfigurationSection quick() {
        var s = module.config().getConfigurationSection("quick-start");
        return s != null ? s : module.config().createSection("quick-start");
    }

    /** Сколько игроков нужно для полного матча; 0 — режим не групповой (дуэль 1x1, рейтинг). */
    int groupSize(String key) {
        if (key == null || key.startsWith("ranked:")) {
            return 0;
        }
        Mode m = modes.get(key);
        if (m != null) {
            return m.players();
        }
        KitSpec spec = module.spec(key);
        return spec != null && spec.rules().players() > 2 ? spec.rules().players() : 0;
    }

    /** Минимум для быстрого старта: половина (quick-start.fraction) от нужного, но не меньше двух. */
    int quickMin(String key) {
        int need = groupSize(key);
        int own = module.quickMin(key);
        if (own > 0) {
            return Math.max(2, own);
        }
        return Math.max(2, (int) Math.ceil(need * quick().getDouble("fraction", 0.5)));
    }

    /** В очереди есть половина участников, но ещё не полная — идёт быстрый старт (таймер + голосование). */
    boolean startVoteReady(String key, int waiting) {
        int need = groupSize(key);
        return need > 0 && quick().getBoolean("enabled", true) && waiting >= quickMin(key) && waiting < need;
    }

    /** Голосов для мгновенного старта: quick-start.vote-fraction от ожидающих (по умолчанию половина), минимум 2. */
    int votesNeeded(int waiting) {
        return Math.max(2, (int) Math.ceil(waiting * quick().getDouble("vote-fraction", 0.5)));
    }

    ItemStack startVoteItem(String mode, int waiting) {
        ItemStack it = Items.parse(quick().getConfigurationSection("item"), Map.of("waiting", waiting, "players", groupSize(mode),
                "votes", startVotes.getOrDefault(mode, java.util.Set.of()).size(), "need", votesNeeded(waiting),
                "seconds", module.queue().quickLeft(mode)));
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(START_VOTE, PersistentDataType.STRING, mode);
        it.setItemMeta(meta);
        return it;
    }

    static boolean isStartVoteItem(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(START_VOTE);
    }

    /** Убрать голос (игрок вышел из очереди или очередь перестала быть «почти полной»). */
    void unvote(java.util.UUID id) {
        startVotes.values().forEach(set -> set.remove(id));
    }

    void clearVotes(String mode) {
        startVotes.remove(mode);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onStartVote(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() == Action.PHYSICAL) {
            return;
        }
        ItemStack it = e.getItem();
        if (!isStartVoteItem(it)) {
            return;
        }
        e.setCancelled(true);
        Player p = e.getPlayer();
        DuelQueue.Entry entry = module.queue().entry(p);
        if (entry == null || entry.ranked() || groupSize(entry.kit()) == 0) {
            return;
        }
        String mode = entry.kit();
        int waiting = module.queue().waiting(mode);
        if (!startVoteReady(mode, waiting)) {
            return;
        }
        java.util.Set<java.util.UUID> set = startVotes.computeIfAbsent(mode, k -> new java.util.HashSet<>());
        if (!set.add(p.getUniqueId())) {
            module.plugin().messages().send(p, "meetups.start-vote-already");
            return;
        }
        int need = votesNeeded(waiting);
        for (java.util.UUID id : module.queue().players(mode)) {
            Player q = org.bukkit.Bukkit.getPlayer(id);
            if (q != null) {
                module.plugin().messages().send(q, "meetups.start-vote", "player", p.getName(), "votes", set.size(), "need", need);
            }
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
        if (set.size() >= need) {
            startVotes.remove(mode);
            KitSpec spec = specs.containsKey(mode) ? specs.get(mode) : module.spec(mode);
            if (spec != null) {
                module.queue().forceStart(mode, spec);
            }
        }
    }

    // --- голосование за версию PvP ---

    ItemStack voteItem() {
        ItemStack it = Items.parse(cfg().getConfigurationSection("vote.item"), Map.of());
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(VOTE, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    void openVote(Player p, MeetupGame game) {
        ConfigurationSection v = cfg().getConfigurationSection("vote");
        Menu menu = new Menu(3, Text.mm(v.getString("title", "Версия PvP")));
        for (CombatVersion ver : CombatVersion.values()) {
            String key = ver == CombatVersion.MODERN ? "modern" : "legacy";
            ConfigurationSection c = v.getConfigurationSection(key);
            Map<String, Object> ph = Map.of("votes", game.votes(ver), "mine", game.voteOf(p) == ver ? module.plugin().messages().raw("meetups.your-vote") : "");
            menu.set(c.getInt("slot"), Items.parse(c, ph), pl -> {
                game.vote(pl, ver);
                pl.playSound(pl.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
                module.plugin().messages().send(pl, "meetups.voted", "version", ver.id());
                openVote(pl, game);
            });
        }
        menu.fill(Items.parse(v.getConfigurationSection("filler"), Map.of()));
        menu.open(p);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBoxSplash(org.bukkit.event.entity.PotionSplashEvent e) {
        if (!(e.getPotion().getShooter() instanceof org.bukkit.entity.Player p)) {
            return;
        }
        GameModule games = module.plugin().module(GameModule.class);
        if (games != null && games.games().of(p) instanceof BoxPvpGame bg) {
            bg.filterSplash(e);
        }
    }

    // --- SkyControl: торговец, выбор команды, стержень молнии ---

    @EventHandler(priority = EventPriority.HIGH)
    public void onSkyShop(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (!e.getRightClicked().getPersistentDataContainer().has(SkyControlGame.SHOP)) {
            return;
        }
        e.setCancelled(true);
        GameModule games = module.plugin().module(GameModule.class);
        if (games != null && games.games().of(e.getPlayer()) instanceof SkyControlGame sg) {
            sg.openShop(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSkyItem(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() == Action.PHYSICAL) {
            return;
        }
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta()) {
            return;
        }
        var pdc = it.getItemMeta().getPersistentDataContainer();
        boolean rod = pdc.has(SkyControlGame.ROD);
        boolean team = pdc.has(SkyControlGame.TEAM_ITEM);
        if (!rod && !team) {
            return;
        }
        e.setCancelled(true);
        GameModule games = module.plugin().module(GameModule.class);
        if (games != null && games.games().of(e.getPlayer()) instanceof SkyControlGame sg) {
            if (team) {
                sg.openTeams(e.getPlayer());
            } else if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                sg.useRod(e.getPlayer(), it);
            }
        }
    }

    /** Торговца нельзя бить. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSkyShopDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(SkyControlGame.SHOP)) {
            e.setCancelled(true);
        }
    }

    /** Сундук выбора кита в BoxPvP (мини-лобби). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBoxSelector(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() == Action.PHYSICAL) {
            return;
        }
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta()) {
            return;
        }
        var pdc = it.getItemMeta().getPersistentDataContainer();
        boolean kit = pdc.has(BoxPvpGame.SELECTOR);
        boolean team = pdc.has(BoxPvpGame.TEAM_SELECTOR);
        if (!kit && !team) {
            return;
        }
        e.setCancelled(true);
        GameModule games = module.plugin().module(GameModule.class);
        Game g = games == null ? null : games.games().of(e.getPlayer());
        if (g instanceof BoxPvpGame bg) {
            if (team) {
                bg.openTeams(e.getPlayer());
            } else {
                bg.openSelect(e.getPlayer());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onVoteItem(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta() || !it.getItemMeta().getPersistentDataContainer().has(VOTE)) {
            return;
        }
        e.setCancelled(true);
        GameModule games = module.plugin().module(GameModule.class);
        Game g = games == null ? null : games.games().of(e.getPlayer());
        if (g instanceof MeetupGame mg && mg.inPrep()) {
            openVote(e.getPlayer(), mg);
        }
    }
}
