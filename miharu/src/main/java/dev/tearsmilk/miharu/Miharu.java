package dev.tearsmilk.miharu;

import dev.tearsmilk.miharu.arena.ArenaModule;
import dev.tearsmilk.miharu.bots.BotsModule;
import dev.tearsmilk.miharu.core.command.MiharuCommand;
import dev.tearsmilk.miharu.core.db.Database;
import dev.tearsmilk.miharu.core.hook.Hooks;
import dev.tearsmilk.miharu.core.lang.Messages;
import dev.tearsmilk.miharu.core.module.MiharuModule;
import dev.tearsmilk.miharu.core.module.ModuleManager;
import dev.tearsmilk.miharu.game.GameModule;
import dev.tearsmilk.miharu.pvp.PvpModule;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Miharu — PvP-ядро сервера: бой 1.8/1.9, пакетные боты, арены из схематик.
 * Каждый модуль живёт в своём пакете и хранит конфиги в plugins/Miharu/modules/&lt;id&gt;/.
 *
 * @author tearsmilk
 */
public final class Miharu extends JavaPlugin {

    private static Miharu instance;

    private Messages messages;
    private Database database;
    private ModuleManager modules;
    private Hooks hooks;

    public static Miharu get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadConfig();

        messages = new Messages(this);
        messages.load();

        database = new Database(this);
        database.connect(getConfig().getConfigurationSection("database"));

        hooks = new Hooks(this);

        modules = new ModuleManager(this);
        modules.register(new PvpModule(this));
        modules.register(new ArenaModule(this));
        modules.register(new GameModule(this));
        modules.register(new BotsModule(this));
        modules.register(new dev.tearsmilk.miharu.duels.DuelsModule(this));
        modules.register(new dev.tearsmilk.miharu.lobby.LobbyModule(this));
        modules.enableAll();

        getServer().getPluginManager().registerEvents(new dev.tearsmilk.miharu.core.menu.MenuListener(), this);
        hooks.enable();
        MiharuCommand.register(this);

        getLogger().info("Miharu " + getPluginMeta().getVersion() + " включён. Модулей: " + modules.enabled().size());
    }

    @Override
    public void onDisable() {
        if (modules != null) {
            modules.disableAll();
        }
        if (hooks != null) {
            hooks.disable();
        }
        if (database != null) {
            database.close();
        }
        instance = null;
    }

    /** Полная перезагрузка конфигов: корень, сообщения, все модули. */
    public void reloadAll() {
        reloadConfig();
        messages.load();
        modules.reloadAll();
    }

    public Messages messages() {
        return messages;
    }

    public Database database() {
        return database;
    }

    public ModuleManager modules() {
        return modules;
    }

    public Hooks hooks() {
        return hooks;
    }

    public <T extends MiharuModule> T module(Class<T> type) {
        return modules.get(type);
    }

    public boolean debug() {
        return getConfig().getBoolean("debug", false);
    }
}
