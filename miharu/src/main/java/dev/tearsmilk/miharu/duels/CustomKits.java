package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.core.db.Database;
import dev.tearsmilk.miharu.core.kit.Kit;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Свои киты игроков (до 3 штук) — data.db → miharu_custom_kits. Предметы хранятся как YAML. */
public final class CustomKits {

    public static final int SLOTS = 3;

    public record CustomKit(int slot, String name, CombatVersion version, Map<EquipmentSlot, ItemStack> armor, Map<Integer, ItemStack> items) {
        public Kit toKit(UUID owner) {
            return new Kit("custom:" + owner + ":" + slot, armor, items);
        }

        public int size() {
            return armor.size() + items.size();
        }
    }

    private final Database db;
    private final Map<UUID, Map<Integer, CustomKit>> cache = new ConcurrentHashMap<>();

    CustomKits(Database db) {
        this.db = db;
    }

    void createTables() {
        db.schema("CREATE TABLE IF NOT EXISTS miharu_custom_kits (uuid VARCHAR(36) NOT NULL, slot INT NOT NULL, name VARCHAR(64) NOT NULL, "
                + "version VARCHAR(4) NOT NULL, data TEXT NOT NULL, PRIMARY KEY (uuid, slot))");
    }

    void load(UUID id) {
        Map<Integer, CustomKit> m = cache.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        db.query("SELECT slot, name, version, data FROM miharu_custom_kits WHERE uuid = ?", rs -> {
            while (rs.next()) {
                CustomKit k = decode(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4));
                if (k != null) {
                    m.put(k.slot(), k);
                }
            }
            return null;
        }, id.toString());
    }

    void unload(UUID id) {
        cache.remove(id);
    }

    public CustomKit get(UUID id, int slot) {
        Map<Integer, CustomKit> m = cache.get(id);
        return m == null ? null : m.get(slot);
    }

    public void save(UUID id, CustomKit kit) {
        cache.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(kit.slot(), kit);
        String data = encode(kit);
        db.upsert("UPDATE miharu_custom_kits SET name = ?, version = ?, data = ? WHERE uuid = ? AND slot = ?",
                new Object[]{kit.name(), kit.version().id(), data, id.toString(), kit.slot()},
                "INSERT INTO miharu_custom_kits (uuid, slot, name, version, data) VALUES (?, ?, ?, ?, ?)",
                new Object[]{id.toString(), kit.slot(), kit.name(), kit.version().id(), data});
    }

    public void delete(UUID id, int slot) {
        Map<Integer, CustomKit> m = cache.get(id);
        if (m != null) {
            m.remove(slot);
        }
        db.update("DELETE FROM miharu_custom_kits WHERE uuid = ? AND slot = ?", id.toString(), slot);
    }

    private static String encode(CustomKit k) {
        YamlConfiguration y = new YamlConfiguration();
        k.armor().forEach((s, i) -> y.set("armor." + s.name(), i));
        k.items().forEach((s, i) -> y.set("items." + s, i));
        return y.saveToString();
    }

    private static CustomKit decode(int slot, String name, String version, String data) {
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(data);
            Map<EquipmentSlot, ItemStack> armor = new EnumMap<>(EquipmentSlot.class);
            ConfigurationSection a = y.getConfigurationSection("armor");
            if (a != null) {
                for (String key : a.getKeys(false)) {
                    ItemStack it = a.getItemStack(key);
                    if (it != null) {
                        armor.put(EquipmentSlot.valueOf(key), it);
                    }
                }
            }
            Map<Integer, ItemStack> items = new LinkedHashMap<>();
            ConfigurationSection is = y.getConfigurationSection("items");
            if (is != null) {
                for (String key : is.getKeys(false)) {
                    ItemStack it = is.getItemStack(key);
                    if (it != null) {
                        items.put(Integer.parseInt(key), it);
                    }
                }
            }
            CombatVersion v = CombatVersion.parse(version);
            return new CustomKit(slot, name, v == null ? CombatVersion.LEGACY : v, armor, items);
        } catch (Exception e) {
            return null;
        }
    }
}
