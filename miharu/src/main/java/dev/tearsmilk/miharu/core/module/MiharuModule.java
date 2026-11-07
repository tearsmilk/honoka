package dev.tearsmilk.miharu.core.module;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.core.config.Configs;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Базовый модуль Miharu. У каждого модуля своя папка конфигов: plugins/Miharu/modules/&lt;id&gt;/.
 */
public abstract class MiharuModule {

    protected final Miharu plugin;
    private final String id;
    private final List<Listener> listeners = new ArrayList<>();
    private YamlConfiguration config;
    private boolean enabled;

    protected MiharuModule(Miharu plugin, String id) {
        this.plugin = plugin;
        this.id = id;
    }

    public final String id() {
        return id;
    }

    public final Miharu plugin() {
        return plugin;
    }

    public final boolean isEnabled() {
        return enabled;
    }

    /** Модули, без которых этот не запускается. */
    public List<Class<? extends MiharuModule>> dependencies() {
        return List.of();
    }

    /** Папка модуля: plugins/Miharu/modules/&lt;id&gt;. */
    public final File folder() {
        File dir = new File(plugin.getDataFolder(), "modules/" + id);
        dir.mkdirs();
        return dir;
    }

    /** Главный конфиг модуля (modules/&lt;id&gt;/config.yml). */
    public final YamlConfiguration config() {
        if (config == null) {
            config = loadFile("config.yml");
        }
        return config;
    }

    /** Загружает yml из папки модуля, дописывая недостающие ключи из дефолтного файла в jar. */
    public final YamlConfiguration loadFile(String name) {
        return Configs.load(plugin, "modules/" + id + "/" + name);
    }

    protected final void listen(Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }

    final void enableInternal() {
        config = null;
        onEnable();
        enabled = true;
    }

    final void disableInternal() {
        try {
            onDisable();
        } finally {
            listeners.forEach(HandlerList::unregisterAll);
            listeners.clear();
            enabled = false;
        }
    }

    final void reloadInternal() {
        config = null;
        onReload();
    }

    protected abstract void onEnable();

    protected void onDisable() {
    }

    protected void onReload() {
    }
}
