package dev.tearsmilk.miharu.bots.kit;

import dev.tearsmilk.miharu.core.kit.Kit;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public final class KitManager {

    private final Map<String, Kit> kits = new HashMap<>();

    public void load(YamlConfiguration cfg, Logger log) {
        kits.clear();
        ConfigurationSection root = cfg.getConfigurationSection("kits");
        if (root == null) {
            return;
        }
        // id с точкой (duel-1.8) Bukkit раскладывает во вложенные секции duel-1 → 8,
        // поэтому кит — любая секция с armor/items/fill/effects, id — полный путь
        for (String id : root.getKeys(true)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null || !(s.contains("armor") || s.contains("items") || s.contains("fill") || s.contains("effects"))) {
                continue;
            }
            try {
                kits.put(id, new Kit(id, s));
            } catch (Exception e) {
                log.warning("Кит " + id + " не загрузился: " + e.getMessage());
            }
        }
    }

    public Kit get(String id) {
        return kits.get(id);
    }
}
