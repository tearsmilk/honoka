package dev.tearsmilk.miharu.core.hook;

import dev.tearsmilk.miharu.Miharu;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/** %miharu_...% — грузится только если установлен PlaceholderAPI. */
final class MiharuExpansion extends PlaceholderExpansion {

    private final Miharu plugin;
    private final Hooks hooks;

    MiharuExpansion(Miharu plugin, Hooks hooks) {
        this.plugin = plugin;
        this.hooks = hooks;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "miharu";
    }

    @Override
    public @NotNull String getAuthor() {
        return "tearsmilk";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        return hooks.resolve(player, params);
    }
}
