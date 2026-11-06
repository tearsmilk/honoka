package dev.tearsmilk.miharu.pvp;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.core.module.MiharuModule;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/** Модуль PvP: две версии боя (1.8 быстрое / 1.9 медленное), всё в modules/pvp/config.yml. */
public final class PvpModule extends MiharuModule {

    private final CombatService combat = new CombatService();
    private PvpListener listener;
    private BukkitTask regenTask;
    private AttributeSwap attributeSwap;

    /** Ванильный свап атрибутов (меч → булава/топор в момент удара). */
    public AttributeSwap attributeSwap() {
        return attributeSwap;
    }

    public PvpModule(Miharu plugin) {
        super(plugin, "pvp");
    }

    public CombatService combat() {
        return combat;
    }

    @Override
    protected void onEnable() {
        combat.load(config());
        listener = new PvpListener(this);
        listen(listener);
        regenTask = Bukkit.getScheduler().runTaskTimer(plugin, listener::tickRegen, 20L, 10L);
        attributeSwap = new AttributeSwap(plugin, combat);
        listen(attributeSwap);
        attributeSwap.start(config().getBoolean("attribute-swap.enabled", true));
        plugin.registerCommand("pvp", "Версия боя: 1.8 / 1.9", List.of("combat"), new PvpCommand(plugin, this));
        plugin.hooks().placeholder("pvp_version", (player, rest) -> {
            if (player == null || !player.isOnline()) {
                return "";
            }
            CombatVersion v = combat.version(player.getPlayer());
            return v == null ? "vanilla" : v.id();
        });
    }

    @Override
    protected void onReload() {
        combat.load(config());
    }

    @Override
    protected void onDisable() {
        if (regenTask != null) {
            regenTask.cancel();
        }
        if (attributeSwap != null) {
            attributeSwap.stop();
        }
        Bukkit.getOnlinePlayers().forEach(combat::reset);
    }
}
