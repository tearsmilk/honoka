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
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * SkyControl 2x2: красные против синих. Острова команд (p1 / p2) с торговцем (shop1 / shop2) и генератором бетона (gen1 / gen2).
 * 7 точек захвата 3x3 (cp1…cp7): каждая захваченная точка даёт команде +1 очко в секунду. Цель — 1200 очков.
 * Монеты: 1 за убийство и 1 за захват точки (каждому захватившему) — тратятся у торговца на улучшения и предметы.
 */
public final class SkyControlGame extends Game implements KitGame, BuildGame {

    public static final String MODE = "skycontrol";
    static final NamespacedKey SHOP = new NamespacedKey("miharu", "sc_shop");
    static final NamespacedKey ROD = new NamespacedKey("miharu", "sc_rod");
    static final NamespacedKey TEAM_ITEM = new NamespacedKey("miharu", "sc_team");
    private static final NamespacedKey TOOL = new NamespacedKey("miharu", "sc_tool");
    private static final Kit.Team[] TEAMS = {Kit.Team.RED, Kit.Team.BLUE};

    /** Точка захвата 3x3: центр — блок под точкой cpN. */
    private final class Point {
        final String id;
        final String name;
        final Block center;
        final List<Block> floor = new ArrayList<>();
        Kit.Team owner;
        Kit.Team capturing;
        int progress;

        Point(String id, String name, Block center) {
            this.id = id;
            this.name = name;
            this.center = center;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    floor.add(center.getRelative(dx, 0, dz));
                }
            }
        }

        boolean inside(Location l) {
            return Math.abs(l.getBlockX() - center.getX()) <= 1 && Math.abs(l.getBlockZ() - center.getZ()) <= 1
                    && l.getY() >= center.getY() && l.getY() <= center.getY() + 4;
        }

        boolean zone(Block b) {
            return Math.abs(b.getX() - center.getX()) <= 1 && Math.abs(b.getZ() - center.getZ()) <= 1
                    && b.getY() >= center.getY() && b.getY() <= center.getY() + 4;
        }

        void paint() {
            String def = owner == null ? "WHITE_STAINED_GLASS" : owner == Kit.Team.RED ? "RED_STAINED_GLASS" : "BLUE_STAINED_GLASS";
            Material m = Material.matchMaterial(cfg.getString("point-floor." + (owner == null ? "neutral" : owner.name().toLowerCase(Locale.ROOT)), def));
            for (Block b : floor) {
                b.setType(m == null ? Material.matchMaterial(def) : m, false);
            }
        }

        /** Маяк под центром точки (луч проходит через цветное стекло): маяк + основание 3x3 из железа. */
        void beacon() {
            if (!cfg.getBoolean("point-beacon", true)) {
                return;
            }
            Block b = center.getRelative(BlockFace.DOWN);
            b.setType(Material.BEACON, false);
            Block base = b.getRelative(BlockFace.DOWN);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    base.getRelative(dx, 0, dz).setType(Material.IRON_BLOCK, false);
                }
            }
            // над точкой ничего не должно закрывать луч
            for (int y = 1; y <= 4; y++) {
                Block up = center.getRelative(0, y, 0);
                if (!up.getType().isAir()) {
                    up.setType(Material.AIR, false);
                }
            }
        }
    }

    private final DuelsModule duels;
    private final KitSpec spec;
    private final CombatService combat;
    private final List<Player> order;
    private final int teamSize;
    private final ConfigurationSection cfg;
    private final Map<UUID, Kit.Team> teams = new HashMap<>();
    private final Map<UUID, Integer> coins = new HashMap<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, Integer> captures = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> upgrades = new HashMap<>();
    private final Map<UUID, UUID> lastHit = new HashMap<>();
    private final Map<UUID, Long> lastHitAt = new HashMap<>();
    private final Map<UUID, Long> rodCooldown = new HashMap<>();
    private final Set<UUID> respawning = new HashSet<>();
    private final Map<Kit.Team, Integer> score = new HashMap<>();
    private final List<Point> points = new ArrayList<>();
    private final List<Villager> shops = new ArrayList<>();
    private final BuildTracker build = new BuildTracker();
    private boolean selecting = true;
    private int selectLeft;
    private boolean finished;
    private String winnerName = "-";

    SkyControlGame(DuelsModule duels, GameModule games, Arena arena, KitSpec spec, CombatService combat, List<Player> players) {
        super(games, MODE, arena);
        this.duels = duels;
        this.spec = spec;
        this.combat = combat;
        this.order = new ArrayList<>(players);
        this.teamSize = Math.max(1, duels.teamSize(spec));
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(duels.folder(), "minigames/skycontrol.yml"));
        ConfigurationSection s = y.getConfigurationSection("skycontrol");
        this.cfg = s != null ? s : y.createSection("skycontrol");
        for (Kit.Team t : TEAMS) {
            score.put(t, 0);
        }
    }

    @Override
    public KitSpec kitSpec() {
        return spec;
    }

    @Override
    public CombatVersion combatVersion() {
        return spec.version();
    }

    @Override
    public String displayName() {
        return Text.plain(Text.mm(spec.display()));
    }

    // --- команды ---

    private Kit.Team team(Player p) {
        Kit.Team t = teams.get(p.getUniqueId());
        return t != null ? t : TEAMS[Math.max(0, order.indexOf(p)) % TEAMS.length];
    }

    private int teamIndex(Kit.Team t) {
        return t == Kit.Team.BLUE ? 2 : 1;
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
        return ta != null && ta == teams.get(b.getUniqueId());
    }

    private String teamName(Kit.Team t) {
        return plugin.messages().raw("skycontrol.team-" + t.name().toLowerCase(Locale.ROOT));
    }

    private Material concrete(Kit.Team t) {
        return t == Kit.Team.BLUE ? Material.BLUE_CONCRETE : Material.RED_CONCRETE;
    }

    @Override
    protected Location spawnFor(Player p) {
        if (selecting && instance.has("wait")) {
            return instance.spawn("wait");
        }
        return instance.spawn("p" + teamIndex(team(p)), "p1");
    }

    @Override
    protected boolean allowHunger() {
        return false;
    }

    // --- подготовка ---

    @Override
    protected void onSetup() {
        instance.world().setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, true);
        instance.world().setGameRule(GameRules.FALL_DAMAGE, true);
        List<String> names = cfg.getStringList("point-names");
        for (int i = 1; i <= 7; i++) {
            String key = "cp" + i;
            if (instance.has(key)) {
                Point pt = new Point(key, i <= names.size() ? names.get(i - 1) : String.valueOf((char) ('A' + i - 1)),
                        instance.spawn(key).getBlock().getRelative(BlockFace.DOWN));
                pt.beacon();
                pt.paint();
                points.add(pt);
            }
        }
        for (Kit.Team t : TEAMS) {
            String key = "shop" + teamIndex(t);
            if (instance.has(key)) {
                Location l = instance.spawn(key);
                Villager v = l.getWorld().spawn(l, Villager.class, vl -> {
                    vl.setAI(false);
                    vl.setInvulnerable(true);
                    vl.setSilent(true);
                    vl.setPersistent(true);
                    vl.setRemoveWhenFarAway(false);
                    vl.setProfession(Villager.Profession.WEAPONSMITH);
                    vl.customName(Text.mm(cfg.getString("shop.npc-name", "<gold><b>Торговец</b>")));
                    vl.setCustomNameVisible(true);
                    vl.getPersistentDataContainer().set(SHOP, PersistentDataType.STRING, t.name());
                });
                shops.add(v);
            }
        }
        selectLeft = Math.max(5, cfg.getInt("team-select-seconds", 15)) * 20;
        for (Player p : order) {
            reset(p);
            p.setGameMode(GameMode.ADVENTURE);
            p.teleport(spawnFor(p));
            p.getInventory().setItem(4, teamItem(p));
            coins.put(p.getUniqueId(), 0);
            p.setLevel(0);
            p.setExp(0);
        }
    }

    @Override
    protected void onRunning() {
        super.onRunning();
        for (Player p : order) {
            plugin.messages().send(p, "skycontrol.select-started", "seconds", selectLeft / 20);
            openTeams(p);
        }
    }

    private void reset(Player p) {
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        for (PotionEffect e : p.getActivePotionEffects()) {
            p.removePotionEffect(e.getType());
        }
        heal(p);
    }

    private void heal(Player p) {
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(max == null ? 20 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20);
        p.setFireTicks(0);
        p.setFallDistance(0);
    }

    // --- выбор команды ---

    private ItemStack teamItem(Player p) {
        ItemStack it = Items.parse(cfg.getConfigurationSection("team-item"), Map.of("team",
                teams.containsKey(p.getUniqueId()) ? teamName(team(p)) : plugin.messages().raw("skycontrol.team-none")));
        if (teams.containsKey(p.getUniqueId())) {
            it = it.withType(team(p) == Kit.Team.BLUE ? Material.BLUE_WOOL : Material.RED_WOOL);
        }
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(TEAM_ITEM, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    void openTeams(Player p) {
        if (!selecting || !players.contains(p)) {
            return;
        }
        Menu menu = new Menu(3, Text.mm(cfg.getString("team-menu.title", "Выбор команды")));
        int[] slots = {11, 15};
        for (int i = 0; i < TEAMS.length; i++) {
            Kit.Team t = TEAMS[i];
            List<Player> mem = members(t);
            List<String> lore = new ArrayList<>();
            lore.add(plugin.messages().raw("skycontrol.team-lore-count").replace("{count}", String.valueOf(mem.size())).replace("{size}", String.valueOf(teamSize)));
            for (Player o : mem) {
                lore.add("<dark_gray>• <white>" + o.getName());
            }
            lore.add("");
            lore.add(plugin.messages().raw(teams.get(p.getUniqueId()) == t ? "skycontrol.team-lore-yours"
                    : mem.size() >= teamSize ? "skycontrol.team-lore-full" : "skycontrol.team-lore-join"));
            ItemStack icon = new ItemStack(t == Kit.Team.BLUE ? Material.BLUE_WOOL : Material.RED_WOOL, Math.max(1, mem.size()));
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Text.item(teamName(t), Map.of()));
            meta.lore(Text.lore(lore, Map.of()));
            meta.setEnchantmentGlintOverride(teams.get(p.getUniqueId()) == t);
            icon.setItemMeta(meta);
            menu.set(slots[i], icon, pl -> joinTeam(pl, t));
        }
        menu.fill(Items.parse(cfg.getConfigurationSection("menu-filler"), Map.of()));
        menu.open(p);
    }

    private void joinTeam(Player p, Kit.Team t) {
        if (!selecting || !players.contains(p)) {
            p.closeInventory();
            return;
        }
        if (teams.get(p.getUniqueId()) == t) {
            p.closeInventory();
            return;
        }
        if (members(t).size() >= teamSize) {
            plugin.messages().send(p, "skycontrol.team-full", "team", teamName(t));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        teams.put(p.getUniqueId(), t);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
        for (Player o : players) {
            plugin.messages().send(o, "skycontrol.team-joined", "player", p.getName(), "team", teamName(t));
        }
        p.getInventory().setItem(4, teamItem(p));
        p.closeInventory();
        for (Player o : players) {
            if (o != p && o.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu
                    && Text.plain(o.getOpenInventory().title()).equals(Text.plain(Text.mm(cfg.getString("team-menu.title", ""))))) {
                openTeams(o);
            }
        }
    }

    /** Конец выбора: невыбравшие — в меньшую команду. */
    private void assignTeams() {
        List<Player> rest = new ArrayList<>();
        for (Player p : order) {
            if (players.contains(p) && !teams.containsKey(p.getUniqueId())) {
                rest.add(p);
            }
        }
        Collections.shuffle(rest);
        for (Player p : rest) {
            Kit.Team best = members(Kit.Team.RED).size() <= members(Kit.Team.BLUE).size() ? Kit.Team.RED : Kit.Team.BLUE;
            teams.put(p.getUniqueId(), best);
            plugin.messages().send(p, "skycontrol.team-auto", "team", teamName(best));
        }
    }

    private void startFight() {
        assignTeams();
        selecting = false;
        for (Player p : List.copyOf(players)) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) {
                p.closeInventory();
            }
            reset(p);
            p.setGameMode(GameMode.SURVIVAL);
            p.teleport(spawnFor(p));
            giveStart(p);
            plugin.messages().title(p, "skycontrol.fight", "team", teamName(team(p)));
        }
    }

    // --- снаряжение и улучшения ---

    private int up(Player p, String key) {
        return upgrades.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).getOrDefault(key, 0);
    }

    private List<String> tiers(String key) {
        return cfg.getStringList("shop.upgrades." + key + ".tiers");
    }

    private int maxLevel(String key) {
        return switch (key) {
            case "speed", "jump" -> Math.max(1, cfg.getInt("shop.upgrades." + key + ".max", 5));
            default -> Math.max(0, tiers(key).size() - 1);
        };
    }

    /** "IRON_SWORD sharpness 1" → неломаемый предмет. */
    private ItemStack tool(String spec, String tag) {
        String[] parts = spec.trim().split("\\s+");
        Material m = Material.matchMaterial(parts[0]);
        ItemStack it = new ItemStack(m == null ? Material.WOODEN_SWORD : m);
        ItemMeta meta = it.getItemMeta();
        for (int i = 1; i + 1 < parts.length; i += 2) {
            Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(parts[i].toLowerCase(Locale.ROOT)));
            int lvl = Integer.parseInt(parts[i + 1]);
            if (e != null && lvl > 0) {
                meta.addEnchant(e, lvl, true);
            }
        }
        meta.setUnbreakable(true);
        if (tag != null) {
            meta.getPersistentDataContainer().set(TOOL, PersistentDataType.STRING, tag);
        }
        it.setItemMeta(meta);
        return it;
    }

    private void giveStart(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.setItem(0, tool(tiers("sword").isEmpty() ? "WOODEN_SWORD" : tiers("sword").get(up(p, "sword")), "sword"));
        inv.setItem(1, tool(tiers("pickaxe").isEmpty() ? "WOODEN_PICKAXE" : tiers("pickaxe").get(up(p, "pickaxe")), "pickaxe"));
        applyArmor(p);
        applyEffects(p);
        combat.force(p, spec.profile());
    }

    private void replaceTool(Player p, String tag, ItemStack it) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack cur = inv.getItem(i);
            if (cur != null && cur.hasItemMeta() && tag.equals(cur.getItemMeta().getPersistentDataContainer().get(TOOL, PersistentDataType.STRING))) {
                inv.setItem(i, it);
                return;
            }
        }
        Map<Integer, ItemStack> left = inv.addItem(it);
        left.values().forEach(x -> p.getWorld().dropItem(p.getLocation(), x));
    }

    private void applyArmor(Player p) {
        List<String> t = tiers("armor");
        int lvl = up(p, "armor");
        PlayerInventory inv = p.getInventory();
        if (t.isEmpty() || lvl >= t.size() || t.get(lvl).isBlank() || t.get(lvl).equalsIgnoreCase("none")) {
            inv.setArmorContents(null);
            return;
        }
        String[] parts = t.get(lvl).trim().split("\\s+");
        String prefix = parts[0].toUpperCase(Locale.ROOT);
        String ench = parts.length > 2 ? " " + parts[1] + " " + parts[2] : "";
        ItemStack[] armor = {
                tool(prefix + "_BOOTS" + ench, null), tool(prefix + "_LEGGINGS" + ench, null),
                tool(prefix + "_CHESTPLATE" + ench, null), tool(prefix + "_HELMET" + ench, null)};
        if (prefix.equals("LEATHER")) {
            for (ItemStack a : armor) {
                if (a.getItemMeta() instanceof LeatherArmorMeta lm) {
                    lm.setColor(team(p) == Kit.Team.BLUE ? org.bukkit.Color.fromRGB(0x3B6FD4) : org.bukkit.Color.fromRGB(0xD43B3B));
                    a.setItemMeta(lm);
                }
            }
        }
        inv.setArmorContents(armor);
    }

    private void applyEffects(Player p) {
        p.removePotionEffect(PotionEffectType.SPEED);
        p.removePotionEffect(PotionEffectType.JUMP_BOOST);
        if (up(p, "speed") > 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, up(p, "speed") - 1, false, false, true));
        }
        if (up(p, "jump") > 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, PotionEffect.INFINITE_DURATION, up(p, "jump") - 1, false, false, true));
        }
    }

    private void addCoins(Player p, int n) {
        int c = coins.merge(p.getUniqueId(), n, Integer::sum);
        p.setLevel(c);
    }

    // --- магазин ---

    boolean ownsShop(Player p, Entity e) {
        String t = e.getPersistentDataContainer().get(SHOP, PersistentDataType.STRING);
        return t != null && players.contains(p) && !selecting && !respawning.contains(p.getUniqueId());
    }

    void openShop(Player p) {
        if (selecting || !players.contains(p) || respawning.contains(p.getUniqueId())) {
            return;
        }
        ConfigurationSection s = cfg.getConfigurationSection("shop");
        Menu menu = new Menu(Math.max(3, s.getInt("rows", 4)), Text.mm(s.getString("title", "Торговец"), Map.of("coins", coins.getOrDefault(p.getUniqueId(), 0))));
        ConfigurationSection info = s.getConfigurationSection("info");
        if (info != null) {
            ItemStack it = Items.parse(info, Map.of("coins", coins.getOrDefault(p.getUniqueId(), 0)));
            menu.set(info.getInt("slot", 4), it, pl -> {
            });
        }
        ConfigurationSection ups = s.getConfigurationSection("upgrades");
        if (ups != null) {
            for (String key : ups.getKeys(false)) {
                ConfigurationSection u = ups.getConfigurationSection(key);
                int lvl = up(p, key);
                int max = maxLevel(key);
                int price = u.getInt("price", 1);
                Map<String, Object> ph = new HashMap<>();
                ph.put("level", lvl);
                ph.put("max", max);
                ph.put("price", price);
                ph.put("coins", coins.getOrDefault(p.getUniqueId(), 0));
                ph.put("status", plugin.messages().raw(lvl >= max ? "skycontrol.shop-maxed"
                        : coins.getOrDefault(p.getUniqueId(), 0) >= price ? "skycontrol.shop-buy" : "skycontrol.shop-no-coins"));
                ItemStack icon = Items.parse(u.getConfigurationSection("icon"), ph);
                if ((key.equals("sword") || key.equals("pickaxe")) && lvl < max) {
                    ItemStack next = tool(tiers(key).get(lvl + 1), null);
                    icon = icon.withType(next.getType());
                }
                icon.setAmount(Math.max(1, Math.min(64, lvl + 1)));
                menu.set(u.getInt("slot"), icon, pl -> {
                    buyUpgrade(pl, key);
                    openShop(pl);
                });
            }
        }
        ConfigurationSection items = s.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection it = items.getConfigurationSection(key);
                int price = it.getInt("price", 1);
                Map<String, Object> ph = Map.of("price", price, "coins", coins.getOrDefault(p.getUniqueId(), 0),
                        "status", plugin.messages().raw(coins.getOrDefault(p.getUniqueId(), 0) >= price ? "skycontrol.shop-buy" : "skycontrol.shop-no-coins"));
                menu.set(it.getInt("slot"), Items.parse(it.getConfigurationSection("icon"), ph), pl -> {
                    buyItem(pl, key, it);
                    openShop(pl);
                });
            }
        }
        menu.fill(Items.parse(cfg.getConfigurationSection("menu-filler"), Map.of()));
        menu.open(p);
    }

    private boolean pay(Player p, int price) {
        int c = coins.getOrDefault(p.getUniqueId(), 0);
        if (c < price) {
            plugin.messages().send(p, "skycontrol.no-coins", "price", price, "coins", c);
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return false;
        }
        addCoins(p, -price);
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.6f, 1.2f);
        return true;
    }

    private void buyUpgrade(Player p, String key) {
        int lvl = up(p, key);
        if (lvl >= maxLevel(key)) {
            plugin.messages().send(p, "skycontrol.maxed");
            return;
        }
        if (!pay(p, cfg.getInt("shop.upgrades." + key + ".price", 1))) {
            return;
        }
        upgrades.get(p.getUniqueId()).put(key, lvl + 1);
        switch (key) {
            case "sword", "pickaxe" -> replaceTool(p, key, tool(tiers(key).get(lvl + 1), key));
            case "armor" -> applyArmor(p);
            default -> applyEffects(p);
        }
        plugin.messages().send(p, "skycontrol.bought-upgrade", "name",
                Text.plain(Text.mm(cfg.getString("shop.upgrades." + key + ".icon.name", key).replaceAll("\\{[a-z_]+}", ""))).replace("/", "").trim(),
                "level", lvl + 1, "max", maxLevel(key));
    }

    private void buyItem(Player p, String key, ConfigurationSection it) {
        if (!pay(p, it.getInt("price", 1))) {
            return;
        }
        ItemStack give = Items.parse(it.getConfigurationSection("give"), Map.of());
        ItemMeta meta = give.getItemMeta();
        if (key.equals("rod")) {
            meta.getPersistentDataContainer().set(ROD, PersistentDataType.BYTE, (byte) 1);
        }
        give.setItemMeta(meta);
        Map<Integer, ItemStack> left = p.getInventory().addItem(give);
        left.values().forEach(x -> p.getWorld().dropItem(p.getLocation(), x));
        plugin.messages().send(p, "skycontrol.bought-item", "name", Text.plain(Text.mm(it.getString("icon.name", key))));
    }

    // --- стержень: молния в точку взгляда ---

    void useRod(Player p, ItemStack item) {
        if (selecting || !players.contains(p) || respawning.contains(p.getUniqueId())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - rodCooldown.getOrDefault(p.getUniqueId(), 0L) < cfg.getLong("rod.cooldown-ticks", 20) * 50L) {
            return;
        }
        Block target = p.getTargetBlockExact(Math.max(5, cfg.getInt("rod.range", 50)));
        if (target == null) {
            plugin.messages().actionBar(p, "skycontrol.rod-no-target");
            return;
        }
        rodCooldown.put(p.getUniqueId(), now);
        item.setAmount(item.getAmount() - 1);
        Location at = target.getLocation().add(0.5, 1, 0.5);
        at.getWorld().strikeLightningEffect(at);
        double radius = cfg.getDouble("rod.radius", 3.0);
        for (Player t : List.copyOf(players)) {
            if (t == p || sameTeam(p, t) || respawning.contains(t.getUniqueId()) || t.getLocation().distance(at) > radius) {
                continue;
            }
            Vector away = t.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (away.lengthSquared() > 0.01) {
                away.normalize().multiply(cfg.getDouble("rod.push", 0.4));
            }
            lastHit.put(t.getUniqueId(), p.getUniqueId());
            lastHitAt.put(t.getUniqueId(), now);
            t.damage(cfg.getDouble("rod.damage", 2.0));
            t.setVelocity(away.setY(cfg.getDouble("rod.launch", 1.1)));
        }
    }

    // --- тик: выбор, точки, очки, генераторы ---

    @Override
    protected void onTick() {
        if (finished) {
            return;
        }
        if (selecting) {
            selectLeft--;
            if (selectLeft % 20 == 0) {
                for (Player p : players) {
                    plugin.messages().actionBar(p, "skycontrol.select-actionbar", "seconds", selectLeft / 20);
                }
            }
            if (selectLeft <= 0) {
                startFight();
            }
            return;
        }
        if (runningTicks % 5 == 0) {
            tickPoints(5);
        }
        if (runningTicks % 20 == 0) {
            int per = cfg.getInt("score-per-point", 1);
            for (Kit.Team t : TEAMS) {
                int owned = (int) points.stream().filter(pt -> pt.owner == t).count();
                if (owned > 0) {
                    score.merge(t, owned * per, Integer::sum);
                }
            }
            int goal = cfg.getInt("goal", 1200);
            for (Kit.Team t : TEAMS) {
                if (score.get(t) >= goal) {
                    finish(t);
                    return;
                }
            }
        }
        int genEvery = Math.max(1, cfg.getInt("generator.interval-ticks", 20));
        if (runningTicks % genEvery == 0) {
            // точка команды 3x3: бетон своей команды сразу в инвентарь тому, кто на ней стоит
            for (Player p : players) {
                if (respawning.contains(p.getUniqueId()) || p.getGameMode() != GameMode.SURVIVAL) {
                    continue;
                }
                String key = "gen" + teamIndex(team(p));
                if (!instance.has(key) || !onGen(p.getLocation(), instance.spawn(key))) {
                    continue;
                }
                Material c = concrete(team(p));
                int have = 0;
                for (ItemStack it : p.getInventory().getStorageContents()) {
                    if (it != null && it.getType() == c) {
                        have += it.getAmount();
                    }
                }
                if (have < cfg.getInt("generator.max-items", 64)) {
                    p.getInventory().addItem(new ItemStack(c, Math.max(1, cfg.getInt("generator.amount", 1))));
                }
            }
        }
        if (runningTicks % 20 == 0) {
            // чужая база: в радиусе base-damage.radius от точки противника — урон каждую секунду
            double r = cfg.getDouble("base-damage.radius", 5);
            for (Player p : List.copyOf(players)) {
                if (respawning.contains(p.getUniqueId()) || p.getGameMode() != GameMode.SURVIVAL) {
                    continue;
                }
                Kit.Team enemy = team(p) == Kit.Team.RED ? Kit.Team.BLUE : Kit.Team.RED;
                String key = "gen" + teamIndex(enemy);
                if (instance.has(key) && p.getLocation().distance(instance.spawn(key)) <= r) {
                    p.damage(cfg.getDouble("base-damage.damage", 2.0));
                    plugin.messages().actionBar(p, "skycontrol.enemy-base");
                }
            }
        }
        updateBars();
        if (spec.rules().maxDurationSeconds() > 0 && runningTicks >= spec.rules().maxDurationSeconds() * 20) {
            finish(score.get(Kit.Team.RED) >= score.get(Kit.Team.BLUE) ? (score.get(Kit.Team.RED).equals(score.get(Kit.Team.BLUE)) ? null : Kit.Team.RED) : Kit.Team.BLUE);
        }
    }

    private boolean onGen(Location l, Location gen) {
        Block c = gen.getBlock().getRelative(BlockFace.DOWN);
        return Math.abs(l.getBlockX() - c.getX()) <= 1 && Math.abs(l.getBlockZ() - c.getZ()) <= 1
                && l.getY() >= c.getY() && l.getY() <= c.getY() + 3;
    }

    // --- боссбары: прогресс команд ---

    private final Map<Kit.Team, net.kyori.adventure.bossbar.BossBar> bars = new HashMap<>();

    private void updateBars() {
        int goal = Math.max(1, cfg.getInt("goal", 1200));
        for (Kit.Team t : TEAMS) {
            int owned = (int) points.stream().filter(pt -> pt.owner == t).count();
            net.kyori.adventure.text.Component title = Text.mm(plugin.messages().raw("skycontrol.bossbar-" + t.name().toLowerCase(Locale.ROOT)),
                    Map.of("score", score.get(t), "goal", goal, "points", owned, "total", points.size()));
            float prog = Math.max(0f, Math.min(1f, score.get(t) / (float) goal));
            net.kyori.adventure.bossbar.BossBar bar = bars.get(t);
            if (bar == null) {
                bar = net.kyori.adventure.bossbar.BossBar.bossBar(title, prog,
                        t == Kit.Team.RED ? net.kyori.adventure.bossbar.BossBar.Color.RED : net.kyori.adventure.bossbar.BossBar.Color.BLUE,
                        net.kyori.adventure.bossbar.BossBar.Overlay.NOTCHED_10);
                bars.put(t, bar);
            } else {
                bar.name(title);
                bar.progress(prog);
            }
            for (Player p : players) {
                p.showBossBar(bar);
            }
            for (Player p : spectators()) {
                p.showBossBar(bar);
            }
        }
    }

    private void hideBars(Player p) {
        bars.values().forEach(p::hideBossBar);
    }

    private void tickPoints(int step) {
        int need = Math.max(20, cfg.getInt("capture-seconds", 5) * 20);
        for (Point pt : points) {
            Map<Kit.Team, List<Player>> on = new HashMap<>();
            for (Player p : players) {
                if (!respawning.contains(p.getUniqueId()) && p.getGameMode() == GameMode.SURVIVAL && pt.inside(p.getLocation())) {
                    on.computeIfAbsent(team(p), k -> new ArrayList<>()).add(p);
                }
            }
            if (on.size() > 1) {
                for (List<Player> l : on.values()) {
                    l.forEach(p -> plugin.messages().actionBar(p, "skycontrol.contested", "point", pt.name));
                }
                continue;
            }
            if (on.isEmpty()) {
                if (pt.capturing != null && pt.capturing != pt.owner) {
                    pt.progress = Math.max(0, pt.progress - step);
                    if (pt.progress == 0) {
                        pt.capturing = null;
                    }
                }
                continue;
            }
            Kit.Team t = on.keySet().iterator().next();
            List<Player> who = on.get(t);
            if (pt.owner == t) {
                pt.capturing = t;
                pt.progress = need;
                continue;
            }
            if (pt.capturing != t) {
                pt.progress -= step;
                if (pt.progress <= 0) {
                    pt.capturing = t;
                    pt.progress = 0;
                }
            } else {
                pt.progress += step; // 5 секунд независимо от числа тиммейтов
            }
            int pct = pt.capturing == t ? Math.min(100, pt.progress * 100 / need) : 0;
            for (Player p : who) {
                plugin.messages().actionBar(p, "skycontrol.capturing", "point", pt.name, "percent", pct, "bar", bar(pct));
            }
            if (pt.capturing == t && pt.progress >= need) {
                pt.owner = t;
                pt.progress = need;
                pt.paint();
                for (Player p : who) {
                    addCoins(p, cfg.getInt("coins.capture", 1));
                    captures.merge(p.getUniqueId(), 1, Integer::sum);
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
                }
                broadcast("skycontrol.captured", "team", teamName(t), "point", pt.name);
            }
        }
    }

    private static String bar(int pct) {
        int n = pct / 10;
        return "<green>" + "|".repeat(n) + "<dark_gray>" + "|".repeat(10 - n);
    }

    // --- урон и смерть ---

    @Override
    protected boolean onDamage(Player p, EntityDamageEvent e) {
        if (selecting || respawning.contains(p.getUniqueId())) {
            e.setCancelled(true);
            return true;
        }
        if (e instanceof EntityDamageByEntityEvent ed) {
            Player attacker = ed.getDamager() instanceof Player dp ? dp
                    : ed.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player sp ? sp
                    : ed.getDamager() instanceof TNTPrimed tnt && tnt.getSource() instanceof Player tp ? tp : null;
            if (attacker != null && attacker != p) {
                if (sameTeam(attacker, p) || respawning.contains(attacker.getUniqueId())) {
                    e.setCancelled(true);
                    return true;
                }
                lastHit.put(p.getUniqueId(), attacker.getUniqueId());
                lastHitAt.put(p.getUniqueId(), System.currentTimeMillis());
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
        if (finished || respawning.contains(p.getUniqueId())) {
            return;
        }
        UUID kid = lastHit.remove(p.getUniqueId());
        long at = lastHitAt.getOrDefault(p.getUniqueId(), 0L);
        Player killer = kid == null || System.currentTimeMillis() - at > 10_000 ? null : Bukkit.getPlayer(kid);
        if (killer != null && players.contains(killer) && !sameTeam(killer, p)) {
            kills.merge(killer.getUniqueId(), 1, Integer::sum);
            addCoins(killer, cfg.getInt("coins.kill", 1));
            broadcast("skycontrol.killed", "player", p.getName(), "killer", killer.getName());
        } else {
            broadcast("skycontrol.died", "player", p.getName());
        }
        respawning.add(p.getUniqueId());
        heal(p);
        // купленные меч, кирка и броня остаются; TNT, фаерболы и стержни молнии пропадают
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && (it.getType() == Material.TNT || it.getType() == Material.FIRE_CHARGE
                    || (it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(ROD)))) {
                inv.setItem(i, null);
            }
        }
        if (p.getItemOnCursor().getType() == Material.TNT || p.getItemOnCursor().getType() == Material.FIRE_CHARGE) {
            p.setItemOnCursor(null);
        }
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(spawnFor(p).clone().add(0, 4, 0));
        int sec = Math.max(1, cfg.getInt("respawn-seconds", 3));
        for (int i = 0; i < sec; i++) {
            int left = sec - i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (players.contains(p) && respawning.contains(p.getUniqueId()) && !finished) {
                    plugin.messages().title(p, "minigames.respawning", "seconds", left);
                }
            }, i * 20L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            respawning.remove(p.getUniqueId());
            if (players.contains(p) && !finished && state == State.RUNNING && p.isOnline()) {
                p.setGameMode(GameMode.SURVIVAL);
                p.teleport(spawnFor(p));
                heal(p);
                applyArmor(p);
                applyEffects(p);
            }
        }, sec * 20L);
    }

    @Override
    protected void onPlayerLeave(Player p, boolean quit) {
        hideBars(p);
        if (finished || !(state == State.RUNNING || state == State.COUNTDOWN)) {
            return;
        }
        duels.stats().add(p, spec.id(), false);
        List<Player> rest = new ArrayList<>(players);
        rest.remove(p);
        Set<Kit.Team> left = new HashSet<>();
        for (Player o : rest) {
            left.add(team(o));
        }
        if (!selecting && left.size() <= 1) {
            Bukkit.getScheduler().runTask(plugin, () -> finish(left.isEmpty() ? null : left.iterator().next()));
        } else if (rest.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> finish(null));
        }
    }

    // --- стройка ---

    private boolean protectedBlock(Block b) {
        for (Point pt : points) {
            if (pt.zone(b)) {
                return true;
            }
        }
        for (String key : List.of("p1", "p2", "shop1", "shop2", "gen1", "gen2")) {
            if (instance.has(key)) {
                Location s = instance.spawn(key);
                if (Math.abs(s.getBlockX() - b.getX()) <= 1 && Math.abs(s.getBlockZ() - b.getZ()) <= 1 && Math.abs(s.getBlockY() - b.getY()) <= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected boolean canPlace(Player p, Block block, BlockState replaced) {
        if (selecting || respawning.contains(p.getUniqueId()) || protectedBlock(block)) {
            return false;
        }
        build.place(block, replaced);
        return true;
    }

    @Override
    protected boolean canBreak(Player p, Block block) {
        if (selecting || respawning.contains(p.getUniqueId()) || !build.isPlaced(block)) {
            return false;
        }
        build.broke(block);
        return true;
    }

    @Override
    public void filterExplosion(List<Block> blocks) {
        blocks.removeIf(bl -> !build.isPlaced(bl) || protectedBlock(bl));
        blocks.forEach(build::broke);
    }

    @Override
    public void trackExplosion(List<Block> blocks) {
        blocks.removeIf(this::protectedBlock);
        blocks.forEach(build::broke);
    }

    // --- конец ---

    private void finish(Kit.Team w) {
        if (finished) {
            return;
        }
        finished = true;
        winnerName = w == null ? plugin.messages().raw("skycontrol.draw") : teamName(w);
        for (Player p : order) {
            if (players.contains(p)) {
                duels.stats().add(p, spec.id(), team(p) == w);
            }
        }
        for (Player p : List.copyOf(players)) {
            Object[] kv = kv(p);
            boolean won = team(p) == w;
            plugin.messages().title(p, won ? "duels.win" : "minigames.ffa-end", kv);
            plugin.messages().sendList(p, "skycontrol.summary", kv);
            p.playSound(p.getLocation(), won ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        }
        shops.forEach(Entity::remove);
        for (Player p : order) {
            hideBars(p);
        }
        for (Player p : spectators()) {
            hideBars(p);
        }
        end();
    }

    private Object[] kv(Player p) {
        return placeholders(p).entrySet().stream().flatMap(en -> Stream.of(en.getKey(), en.getValue())).toArray();
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
        map.put("kit", spec.display());
        map.put("team", order.contains(viewer) ? teamName(team(viewer)) : "-");
        map.put("red_score", score.get(Kit.Team.RED));
        map.put("blue_score", score.get(Kit.Team.BLUE));
        map.put("goal", cfg.getInt("goal", 1200));
        map.put("red_points", points.stream().filter(pt -> pt.owner == Kit.Team.RED).count());
        map.put("blue_points", points.stream().filter(pt -> pt.owner == Kit.Team.BLUE).count());
        StringBuilder line = new StringBuilder();
        for (Point pt : points) {
            line.append(pt.owner == Kit.Team.RED ? "<red>" : pt.owner == Kit.Team.BLUE ? "<blue>" : "<gray>").append(pt.name).append(' ');
        }
        map.put("points", line.toString().trim());
        map.put("coins", coins.getOrDefault(viewer.getUniqueId(), 0));
        map.put("kills", kills.getOrDefault(viewer.getUniqueId(), 0));
        map.put("captures", captures.getOrDefault(viewer.getUniqueId(), 0));
        map.put("phase", plugin.messages().raw(selecting ? "skycontrol.phase-select" : "skycontrol.phase-fight"));
        map.put("winner", winnerName);
        map.put("player_hp", Text.num(Math.round(viewer.getHealth() * 10) / 10.0));
        map.put("time_left", Text.time(Math.max(0, spec.rules().maxDurationSeconds() - runningTicks / 20)));
        return map;
    }
}
