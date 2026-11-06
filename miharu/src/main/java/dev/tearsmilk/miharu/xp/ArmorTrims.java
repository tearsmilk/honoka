package dev.tearsmilk.miharu.xp;

import dev.tearsmilk.miharu.core.menu.Menu;
import dev.tearsmilk.miharu.core.util.Items;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.game.GameModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Косметика «Экипировка»: сеты отделок брони (modules/xp/config.yml → armor-trims). Покупка за опыт, потом «Применить».
 * В играх на надетую броню игрока раз в секунду накладывается его отделка (видят все).
 */
final class ArmorTrims {

    /** Отделка на 4 части: шлем, нагрудник, поножи, ботинки. */
    record Set4(String id, int price, int column, String name, ArmorTrim[] pieces) {
    }

    private static final String[] PARTS = {"helmet", "chestplate", "leggings", "boots"};

    private final XpModule xp;
    private final Map<String, Set4> sets = new LinkedHashMap<>();
    private final Map<UUID, Set<String>> owned = new ConcurrentHashMap<>();
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();

    ArmorTrims(XpModule xp) {
        this.xp = xp;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = xp.config().getConfigurationSection("armor-trims");
        return s != null ? s : xp.config().createSection("armor-trims");
    }

    void load() {
        xp.plugin().database().schema("CREATE TABLE IF NOT EXISTS miharu_trims (uuid VARCHAR(36) NOT NULL PRIMARY KEY, owned TEXT NOT NULL, selected VARCHAR(32) NOT NULL)");
        sets.clear();
        ConfigurationSection s = cfg().getConfigurationSection("sets");
        if (s == null) {
            return;
        }
        for (String id : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(id);
            if (c == null || !c.getBoolean("enabled", true)) {
                continue;
            }
            ArmorTrim[] pieces = new ArmorTrim[4];
            for (int i = 0; i < 4; i++) {
                pieces[i] = trim(c.getString("pieces." + PARTS[i], ""));
            }
            sets.put(id, new Set4(id, c.getInt("price", 1000), c.getInt("column", 4), c.getString("name", id), pieces));
        }
    }

    /** "dune:amethyst" → отделка. */
    private static ArmorTrim trim(String spec) {
        String[] p = spec.toLowerCase(Locale.ROOT).split(":");
        if (p.length < 2) {
            return null;
        }
        TrimPattern pattern = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft(p[0].trim()));
        TrimMaterial material = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft(p[1].trim()));
        return pattern == null || material == null ? null : new ArmorTrim(material, pattern);
    }

    void loadPlayer(UUID id) {
        xp.plugin().database().query("SELECT owned, selected FROM miharu_trims WHERE uuid = ?", rs -> {
            if (!rs.next()) {
                return null;
            }
            return new String[]{rs.getString(1), rs.getString(2)};
        }, id.toString()).thenAccept(r -> {
            Set<String> set = ConcurrentHashMap.newKeySet();
            if (r != null) {
                for (String x : r[0].split(",")) {
                    if (!x.isBlank()) {
                        set.add(x.trim());
                    }
                }
                if (!r[1].isBlank()) {
                    selected.put(id, r[1]);
                }
            }
            owned.put(id, set);
        });
    }

    void unload(UUID id) {
        owned.remove(id);
        selected.remove(id);
    }

    private void save(UUID id) {
        String own = String.join(",", owned.getOrDefault(id, Set.of()));
        String sel = selected.getOrDefault(id, "");
        xp.plugin().database().upsert("UPDATE miharu_trims SET owned = ?, selected = ? WHERE uuid = ?", new Object[]{own, sel, id.toString()},
                "INSERT INTO miharu_trims (uuid, owned, selected) VALUES (?, ?, ?)", new Object[]{id.toString(), own, sel});
    }

    // --- меню ---

    void open(Player p) {
        Menu menu = new Menu(6, Text.mm(cfg().getString("title", "<dark_gray>Экипировка")));
        Material[] show = {Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS};
        UUID id = p.getUniqueId();
        for (Set4 s : sets.values()) {
            boolean have = owned.getOrDefault(id, Set.of()).contains(s.id());
            boolean active = s.id().equals(selected.get(id));
            Map<String, Object> ph = new HashMap<>();
            ph.put("set", s.name());
            ph.put("price", s.price());
            ph.put("xp", xp.xp(id));
            for (int i = 0; i < 4; i++) {
                ItemStack it = new ItemStack(show[i]);
                if (it.getItemMeta() instanceof ArmorMeta am) {
                    if (s.pieces()[i] != null) {
                        am.setTrim(s.pieces()[i]);
                    }
                    am.displayName(Text.item(s.name(), ph));
                    List<String> lore = new ArrayList<>(cfg().getStringList("piece-lore"));
                    am.lore(Text.lore(lore, ph));
                    am.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
                    it.setItemMeta(am);
                }
                menu.set(i * 9 + s.column(), it, pl -> {
                });
            }
            String state = active ? "active" : have ? "owned" : "buy";
            ItemStack feather = Items.parse(cfg().getConfigurationSection("button." + state), ph);
            if (active) {
                ItemMeta fm = feather.getItemMeta();
                fm.setEnchantmentGlintOverride(true);
                feather.setItemMeta(fm);
            }
            menu.set(5 * 9 + s.column(), feather, pl -> click(pl, s));
        }
        ConfigurationSection f = cfg().getConfigurationSection("filler");
        if (f != null && f.getBoolean("enabled", false)) {
            menu.fill(Items.parse(f, Map.of()));
        }
        menu.open(p);
    }

    private void click(Player p, Set4 s) {
        UUID id = p.getUniqueId();
        Set<String> have = owned.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
        if (!have.contains(s.id())) {
            long bal = xp.xp(id);
            if (bal < s.price()) {
                xp.plugin().messages().send(p, "trims.no-xp", "price", s.price(), "xp", bal, "set", s.name());
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                return;
            }
            xp.add(p, -s.price());
            have.add(s.id());
            save(id);
            xp.plugin().messages().send(p, "trims.bought", "set", s.name(), "price", s.price());
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
        } else if (s.id().equals(selected.get(id))) {
            selected.remove(id); // повторный клик по выбранному — снять
            save(id);
            xp.plugin().messages().send(p, "trims.removed", "set", s.name());
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.9f);
        } else {
            selected.put(id, s.id());
            save(id);
            xp.plugin().messages().send(p, "trims.applied", "set", s.name());
            p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 0.8f, 1.1f);
        }
        open(p);
    }

    // --- наложение в играх ---

    /** Раз в секунду: на надетую броню игроков в играх — их отделку. */
    void tick() {
        GameModule games = xp.plugin().module(GameModule.class);
        if (games == null) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            String sel = selected.get(p.getUniqueId());
            if (sel == null || !games.games().inGame(p)) {
                continue;
            }
            Set4 s = sets.get(sel);
            if (s == null) {
                continue;
            }
            ItemStack[] armor = p.getInventory().getArmorContents(); // ботинки, поножи, нагрудник, шлем
            boolean changed = false;
            for (int i = 0; i < 4; i++) {
                ItemStack it = armor[i];
                ArmorTrim want = s.pieces()[3 - i];
                if (it == null || want == null || !(it.getItemMeta() instanceof ArmorMeta am)) {
                    continue;
                }
                if (want.equals(am.getTrim())) {
                    continue;
                }
                am.setTrim(want);
                it.setItemMeta(am);
                changed = true;
            }
            if (changed) {
                p.getInventory().setArmorContents(armor);
            }
        }
    }
}
