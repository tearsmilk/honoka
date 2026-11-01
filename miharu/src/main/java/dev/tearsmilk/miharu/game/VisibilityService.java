package dev.tearsmilk.miharu.game;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Изоляция: игроки в игре видят (в мире и в табе) только участников своей игры,
 * игроки в лобби не видят тех, кто в играх.
 */
public final class VisibilityService {

    private final GameModule module;

    VisibilityService(GameModule module) {
        this.module = module;
    }

    private boolean enabled() {
        return module.config().getBoolean("hide-other-players", true);
    }

    public boolean sameContext(Player a, Player b) {
        return module.games().contextOf(a) == module.games().contextOf(b);
    }

    public void update(Player p) {
        if (!enabled()) {
            return;
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other == p) {
                continue;
            }
            if (sameContext(p, other)) {
                p.showPlayer(module.plugin(), other);
                other.showPlayer(module.plugin(), p);
            } else {
                p.hidePlayer(module.plugin(), other);
                other.hidePlayer(module.plugin(), p);
            }
        }
    }

    void showAll() {
        for (Player a : Bukkit.getOnlinePlayers()) {
            for (Player b : Bukkit.getOnlinePlayers()) {
                if (a != b) {
                    a.showPlayer(module.plugin(), b);
                }
            }
        }
    }
}
