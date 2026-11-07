package dev.tearsmilk.miharu.core.hook;

import dev.tearsmilk.miharu.Miharu;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.logging.Level;

/**
 * Необязательные интеграции: PlaceholderAPI (свои %miharu_*% и разбор чужих в табло)
 * и Nexo (кастомные предметы в меню и китах: material: "nexo:id").
 */
public final class Hooks {

    private final Miharu plugin;
    /** префикс плейсхолдера → обработчик (игрок, остаток после префикса). */
    private final Map<String, BiFunction<OfflinePlayer, String, String>> placeholders = new LinkedHashMap<>();
    private Object expansion;
    private boolean papi;
    private boolean nexo;
    private Method nexoItemFromId;
    private Method nexoBuild;

    public Hooks(Miharu plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        papi = plugin.getConfig().getBoolean("hooks.placeholderapi", true) && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        if (papi) {
            try {
                MiharuExpansion exp = new MiharuExpansion(plugin, this);
                exp.register();
                expansion = exp;
                plugin.getLogger().info("PlaceholderAPI: зарегистрированы %miharu_...%");
            } catch (Throwable t) {
                papi = false;
                plugin.getLogger().log(Level.WARNING, "Не удалось подключить PlaceholderAPI", t);
            }
        }
        nexo = plugin.getConfig().getBoolean("hooks.nexo", true) && Bukkit.getPluginManager().getPlugin("Nexo") != null;
        if (nexo) {
            try {
                Class<?> items = Class.forName("com.nexomc.nexo.api.NexoItems");
                nexoItemFromId = items.getMethod("itemFromId", String.class);
                nexoBuild = Class.forName("com.nexomc.nexo.items.ItemBuilder").getMethod("build");
                plugin.getLogger().info("Nexo: предметы nexo:<id> доступны в меню и китах");
            } catch (Throwable t) {
                nexo = false;
                plugin.getLogger().log(Level.WARNING, "Nexo найден, но API не совпало", t);
            }
        }
    }

    public void disable() {
        if (expansion instanceof MiharuExpansion exp) {
            try {
                exp.unregister();
            } catch (Throwable ignored) {
            }
        }
    }

    public void placeholder(String prefix, BiFunction<OfflinePlayer, String, String> handler) {
        placeholders.put(prefix.toLowerCase(), handler);
    }

    String resolve(OfflinePlayer player, String params) {
        String p = params.toLowerCase();
        for (Map.Entry<String, BiFunction<OfflinePlayer, String, String>> e : placeholders.entrySet()) {
            if (p.equals(e.getKey()) || p.startsWith(e.getKey() + "_")) {
                String rest = p.length() > e.getKey().length() ? p.substring(e.getKey().length() + 1) : "";
                return e.getValue().apply(player, rest);
            }
        }
        return null;
    }

    /** Прогоняет строку через PlaceholderAPI, если он есть. */
    public String papi(Player player, String text) {
        if (!papi || player == null || text == null || text.indexOf('%') < 0) {
            return text;
        }
        try {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        } catch (Throwable t) {
            return text;
        }
    }

    public boolean nexo() {
        return nexo;
    }

    /** Предмет Nexo по id или null. */
    public ItemStack nexoItem(String id) {
        if (!nexo) {
            return null;
        }
        try {
            Object builder = nexoItemFromId.invoke(null, id);
            return builder == null ? null : (ItemStack) nexoBuild.invoke(builder);
        } catch (Throwable t) {
            return null;
        }
    }
}
