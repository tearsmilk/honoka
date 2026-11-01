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
