package dev.tearsmilk.miharu.bots.kit;

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
        for (String id : root.getKeys(false)) {
            try {
                kits.put(id, new Kit(id, root.getConfigurationSection(id)));
            } catch (Exception e) {
                log.warning("Кит " + id + " не загрузился: " + e.getMessage());
            }
        }
    }

    public Kit get(String id) {
        return kits.get(id);
    }
}
