package dev.tearsmilk.miharu.lobby;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Тест-режим для проверки механик (/miharu combat-mode [ник], только OP): пустой инвентарь, выживание,
 * можно строить, поднимать и выбрасывать вещи, взаимодействовать с предметами и блоками, в лобби проходит урон. Повторная команда — выход (инвентарь чистится, предметы лобби).
 */
public final class CombatMode {

    private final LobbyModule module;
    private final Set<UUID> active = new HashSet<>();

    CombatMode(LobbyModule module) {
        this.module = module;
    }

    public boolean active(Player p) {
        return p != null && active.contains(p.getUniqueId());
    }

    /** Переключить режим игроку target (сообщение — и ему, и тому, кто включал). */
    public void toggle(org.bukkit.command.CommandSender by, Player target) {
        if (active(target)) {
            exit(target, true);
            if (by != target) {
                module.plugin().messages().send(by, "lobby.combat-off-other", "player", target.getName());
            }
            return;
        }
        var games = module.plugin().module(dev.tearsmilk.miharu.game.GameModule.class);
        if (games != null && games.games().busy(target)) {
            module.plugin().messages().send(by, "lobby.combat-in-game", "player", target.getName());
            return;
        }
        var duels = module.plugin().module(dev.tearsmilk.miharu.duels.DuelsModule.class);
        if (duels != null) {
            duels.queue().leave(target, true);
        }
        if (module.adminMode().active(target)) {
            module.adminMode().exit(target, false);
        }
        active.add(target.getUniqueId());
        target.getInventory().clear();
        target.getInventory().setArmorContents(null);
        target.setItemOnCursor(null);
        for (PotionEffect e : target.getActivePotionEffects()) {
            target.removePotionEffect(e.getType());
        }
        target.setGameMode(GameMode.SURVIVAL);
        target.setFlying(false);
        target.setAllowFlight(false);
        var max = target.getAttribute(Attribute.MAX_HEALTH);
        target.setHealth(max == null ? 20 : max.getValue());
        target.setFoodLevel(20);
        target.updateInventory();
        module.plugin().messages().send(target, "lobby.combat-on");
        if (by != target) {
            module.plugin().messages().send(by, "lobby.combat-on-other", "player", target.getName());
        }
    }

    public void exit(Player p, boolean applyLobby) {
        if (!active.remove(p.getUniqueId())) {
            return;
        }
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        p.setItemOnCursor(null);
        for (PotionEffect e : p.getActivePotionEffects()) {
            p.removePotionEffect(e.getType());
        }
        if (applyLobby) {
            module.apply(p, false);
            module.plugin().messages().send(p, "lobby.combat-off");
        }
    }

    void exitAll() {
        for (UUID id : List.copyOf(active)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                exit(p, true);
            }
        }
        active.clear();
    }

    void quit(Player p) {
        active.remove(p.getUniqueId());
    }
}
