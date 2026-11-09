package dev.tearsmilk.miharu.core.module;

import dev.tearsmilk.miharu.Miharu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public final class ModuleManager {

    private final Miharu plugin;
    private final Map<Class<? extends MiharuModule>, MiharuModule> modules = new LinkedHashMap<>();

    public ModuleManager(Miharu plugin) {
        this.plugin = plugin;
    }

    public void register(MiharuModule module) {
        modules.put(module.getClass(), module);
    }

    @SuppressWarnings("unchecked")
    public <T extends MiharuModule> T get(Class<T> type) {
        MiharuModule module = modules.get(type);
        return module != null && module.isEnabled() ? (T) module : null;
    }

    public List<MiharuModule> all() {
        return Collections.unmodifiableList(new ArrayList<>(modules.values()));
    }

    public List<MiharuModule> enabled() {
        return modules.values().stream().filter(MiharuModule::isEnabled).toList();
    }

    public void enableAll() {
        for (MiharuModule module : modules.values()) {
            if (!plugin.getConfig().getBoolean("modules." + module.id(), true)) {
                plugin.getLogger().info("Модуль " + module.id() + " выключен в config.yml");
                continue;
            }
            boolean depsOk = module.dependencies().stream().allMatch(d -> get(d) != null);
            if (!depsOk) {
                plugin.getLogger().warning("Модуль " + module.id() + " не запущен: не включены зависимости " + module.dependencies().stream().map(Class::getSimpleName).toList());
                continue;
            }
            try {
                module.enableInternal();
                plugin.getLogger().info("Модуль " + module.id() + " запущен");
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Не удалось запустить модуль " + module.id(), t);
            }
        }
    }

    public void disableAll() {
        List<MiharuModule> list = new ArrayList<>(modules.values());
        Collections.reverse(list);
        for (MiharuModule module : list) {
            if (!module.isEnabled()) {
                continue;
            }
            try {
                module.disableInternal();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при выключении модуля " + module.id(), t);
            }
        }
    }

    public void reloadAll() {
        for (MiharuModule module : modules.values()) {
            if (!module.isEnabled()) {
                continue;
            }
            try {
                module.reloadInternal();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при перезагрузке модуля " + module.id(), t);
            }
        }
    }
}
