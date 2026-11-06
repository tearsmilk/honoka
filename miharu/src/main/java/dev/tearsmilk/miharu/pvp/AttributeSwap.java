package dev.tearsmilk.miharu.pvp;

import dev.tearsmilk.miharu.Miharu;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ванильный «свап атрибутов» (MC-28289): ударил и за 1–2 тика сменил предмет — удар смешивает урон одного предмета
 * и чары другого (меч → булава/топор). Paper это блокирует настройкой unsupported-settings.update-equipment-on-player-actions;
 * здесь она выключается на время работы плагина, а заряд удара (attackStrengthTicker) не сбрасывается при смене слота.
 * Настройка — modules/pvp/config.yml → attribute-swap, переключатель — /miharu attribute-swap on|off.
 */
public final class AttributeSwap implements Listener {

    private final Miharu plugin;
    private final CombatService combat;
    private final Map<UUID, Integer> lastTicker = new ConcurrentHashMap<>();
    private boolean enabled;
    private BukkitTask task;

    // Paper GlobalConfiguration → поле «equipment…» (update-equipment-on-player-actions)
    private Object paperHolder;
    private Field paperField;
    private Boolean paperOriginal;

    // NMS: LivingEntity.attackStrengthTicker (маппинг Mojang)
    private Field tickerField;
    private boolean tickerMissing;

    AttributeSwap(Miharu plugin, CombatService combat) {
        this.plugin = plugin;
        this.combat = combat;
    }

    public boolean enabled() {
        return enabled;
    }

    void start(boolean on) {
        findPaperSetting();
        set(on);
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!enabled) {
                return;
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                int t = ticker(p);
                if (t >= 0) {
                    lastTicker.put(p.getUniqueId(), t);
                }
            }
        }, 1L, 1L);
    }

    void stop() {
        if (task != null) {
            task.cancel();
        }
        restorePaper();
    }

    public void set(boolean on) {
        enabled = on;
        if (paperField != null) {
            try {
                paperField.setBoolean(paperHolder, on ? false : paperOriginal);
            } catch (IllegalAccessException e) {
                plugin.getLogger().warning("attribute-swap: не удалось поменять настройку Paper: " + e.getMessage());
            }
        }
    }

    private void restorePaper() {
        if (paperField != null && paperOriginal != null) {
            try {
                paperField.setBoolean(paperHolder, paperOriginal);
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    private void findPaperSetting() {
        if (paperField != null) {
            return;
        }
        try {
            Class<?> gc = Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
            Object cfg = gc.getMethod("get").invoke(null);
            for (Field section : gc.getDeclaredFields()) {
                section.setAccessible(true);
                Object holder = section.get(cfg);
                if (holder == null || holder.getClass().getName().startsWith("java.")) {
                    continue;
                }
                for (Field f : holder.getClass().getDeclaredFields()) {
                    if (f.getType() == boolean.class && f.getName().toLowerCase().contains("updateequipment")) {
                        f.setAccessible(true);
                        paperHolder = holder;
                        paperField = f;
                        paperOriginal = f.getBoolean(holder);
                        plugin.getLogger().info("attribute-swap: настройка Paper " + section.getName() + "." + f.getName() + " = " + paperOriginal);
                        return;
                    }
                }
            }
            plugin.getLogger().warning("attribute-swap: настройка Paper update-equipment-on-player-actions не найдена");
        } catch (Exception e) {
            plugin.getLogger().warning("attribute-swap: конфиг Paper недоступен (" + e.getMessage() + ")");
        }
    }

    // --- заряд удара не сбрасывается при смене предмета ---

    private Object handle(Player p) throws Exception {
        return p.getClass().getMethod("getHandle").invoke(p);
    }

    private int ticker(Player p) {
        if (tickerMissing) {
            return -1;
        }
        try {
            Object h = handle(p);
            if (tickerField == null) {
                for (Class<?> c = h.getClass(); c != null && tickerField == null; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (f.getName().equals("attackStrengthTicker") && f.getType() == int.class) {
                            f.setAccessible(true);
                            tickerField = f;
                            break;
                        }
                    }
                }
                if (tickerField == null) {
                    tickerMissing = true;
                    plugin.getLogger().warning("attribute-swap: attackStrengthTicker не найден — заряд удара при смене предмета будет сбрасываться");
                    return -1;
                }
            }
            return tickerField.getInt(h);
        } catch (Exception e) {
            return -1;
        }
    }

    private void setTicker(Player p, int v) {
        if (tickerField == null) {
            return;
        }
        try {
            tickerField.setInt(handle(p), v);
        } catch (Exception ignored) {
        }
    }

    private void keepCharge(Player p) {
        if (!enabled) {
            return;
        }
        CombatProfile prof = combat.profile(p);
        if (prof != null && prof.version == CombatVersion.LEGACY) {
            return; // в 1.8 кулдауна удара нет
        }
        Integer t = lastTicker.get(p.getUniqueId());
        if (t == null || t < 0) {
            return;
        }
        setTicker(p, t);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && enabled) {
                setTicker(p, t);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHeld(PlayerItemHeldEvent e) {
        keepCharge(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        keepCharge(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastTicker.remove(e.getPlayer().getUniqueId());
    }
}
