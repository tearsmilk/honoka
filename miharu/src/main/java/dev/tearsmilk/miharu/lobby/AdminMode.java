package dev.tearsmilk.miharu.lobby;

import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Режим строителя (/miharu adm-mode, только OP): свой чистый инвентарь, креатив, полёт,
 * защиты лобби не действуют. Инвентарь строителя хранится в modules/lobby/adm-mode/&lt;uuid&gt;.yml.
 */
public final class AdminMode {

    private final LobbyModule module;
    private final Set<UUID> active = new HashSet<>();

    AdminMode(LobbyModule module) {
        this.module = module;
    }

    public boolean active(Player p) {
        return active.contains(p.getUniqueId());
    }

    private File file(UUID id) {
        return new File(module.folder(), "adm-mode/" + id + ".yml");
    }

    public void toggle(Player p) {
        if (active(p)) {
            exit(p, true);
        } else {
            enter(p);
        }
    }

    public void enter(Player p) {
        if (!p.isOp()) {
            module.plugin().messages().send(p, "no-permission");
            return;
        }
        var games = module.plugin().module(dev.tearsmilk.miharu.game.GameModule.class);
        if (games != null && games.games().busy(p)) {
            module.plugin().messages().send(p, "lobby.adm-in-game");
            return;
        }
        var duels = module.plugin().module(dev.tearsmilk.miharu.duels.DuelsModule.class);
        if (duels != null) {
            duels.queue().leave(p, true);
        }
        active.add(p.getUniqueId());
        p.getInventory().clear();
        File f = file(p.getUniqueId());
        if (f.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            @SuppressWarnings("unchecked")
            List<ItemStack> items = (List<ItemStack>) y.getList("contents", new ArrayList<>());
            p.getInventory().setContents(items.toArray(new ItemStack[0]));
        }
        p.setGameMode(GameMode.CREATIVE);
        p.setAllowFlight(true);
        p.setFlying(true);
        p.updateInventory();
        module.plugin().messages().send(p, "lobby.adm-on");
    }

    /** Выход: инвентарь строителя сохраняется, лобби возвращает свои предметы. */
    public void exit(Player p, boolean applyLobby) {
        if (!active.remove(p.getUniqueId())) {
            return;
        }
        save(p);
        p.getInventory().clear();
        p.setFlying(false);
        p.setAllowFlight(false);
        if (applyLobby) {
            module.apply(p, false);
            module.plugin().messages().send(p, "lobby.adm-off");
        }
    }

    private void save(Player p) {
        YamlConfiguration y = new YamlConfiguration();
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack i : p.getInventory().getContents()) {
            list.add(i);
        }
        y.set("contents", list);
        try {
            File f = file(p.getUniqueId());
            f.getParentFile().mkdirs();
            y.save(f);
        } catch (Exception e) {
            module.plugin().getLogger().warning("Не удалось сохранить инвентарь строителя " + p.getName() + ": " + e.getMessage());
        }
    }

    void exitAll() {
        for (UUID id : List.copyOf(active)) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p != null) {
                exit(p, false);
            }
        }
        active.clear();
    }
}
