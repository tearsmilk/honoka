package dev.tearsmilk.miharu.core.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

public final class Configs {

    private Configs() {
    }

    /**
     * Дочерняя секция по «сырому» ключу. Нужна для ключей с точкой ("1.8", "1.9"):
     * обычный getConfigurationSection("1.8") понимает точку как разделитель пути.
     */
    public static org.bukkit.configuration.ConfigurationSection child(org.bukkit.configuration.ConfigurationSection parent, String key) {
        if (parent == null) {
            return null;
        }
        Object v = parent.getValues(false).get(key);
        if (v instanceof org.bukkit.configuration.ConfigurationSection cs) {
            return cs;
        }
        return parent.getConfigurationSection(key);
    }

    /** Значение по «сырому» ключу (ключи с точкой). */
    public static Object value(org.bukkit.configuration.ConfigurationSection parent, String key) {
        if (parent == null) {
            return null;
        }
        Object v = parent.getValues(false).get(key);
        return v != null && !(v instanceof org.bukkit.configuration.ConfigurationSection) ? v : parent.get(key);
    }

    /**
     * Загружает файл из папки плагина. Если файла нет — копирует дефолт из jar.
     * Если в дефолте появились новые ключи — дописывает их, не трогая значения пользователя.
     */
    public static YamlConfiguration load(JavaPlugin plugin, String path) {
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists() && plugin.getResource(path) != null) {
            plugin.saveResource(path, false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource(path)) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                boolean changed = false;
                for (String key : defaults.getKeys(true)) {
                    // копируем только значения: секции создадутся сами, иначе в память попадёт чужая секция
                    if (defaults.isConfigurationSection(key) && !defaults.getConfigurationSection(key).getKeys(false).isEmpty()) {
                        continue;
                    }
                    if (!cfg.contains(key, true)) {
                        cfg.set(key, defaults.get(key));
                        changed = true;
                    }
                }
                if (changed) {
                    cfg.save(file);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось дополнить " + path, e);
        }
        return cfg;
    }
}
