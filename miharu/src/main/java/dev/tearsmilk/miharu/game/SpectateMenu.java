package dev.tearsmilk.miharu.game;

import dev.tearsmilk.miharu.core.menu.Menu;
import dev.tearsmilk.miharu.core.util.Items;
import dev.tearsmilk.miharu.core.util.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Меню активных игр для наблюдения (modules/game/config.yml → spectate). */
public final class SpectateMenu {

    private final GameModule module;

    SpectateMenu(GameModule module) {
        this.module = module;
    }

    public void open(Player p) {
        ConfigurationSection s = module.config().getConfigurationSection("spectate.menu");
        if (s == null) {
            return;
        }
        int rows = Math.max(1, Math.min(6, s.getInt("rows", 6)));
        Menu menu = new Menu(rows, Text.mm(s.getString("title", "Наблюдение")));
        List<Game> games = module.games().games().stream().filter(Game::isActive).toList();
        int max = (rows - 1) * 9;
        int slot = 0;
        for (Game g : games) {
            if (slot >= max) {
                break;
            }
            Map<String, Object> ph = new HashMap<>();
            ph.put("id", g.id());
            ph.put("mode", g.displayName());
            ph.put("arena", Text.plain(Text.mm(g.arena().display())));
            ph.put("players", String.join(" vs ", g.players().stream().map(Player::getName).toList()));
            ph.put("spectators", g.spectators().size());
            ph.put("time", Text.time(g.runningTicks / 20));
            ConfigurationSection icon = s.getConfigurationSection("game-icons." + g.mode());
            if (icon == null) {
                icon = s.getConfigurationSection("game-icon");
            }
            ItemStack item = Items.parse(icon, ph);
            menu.set(slot++, item, pl -> {
                pl.closeInventory();
                if (g.isActive()) {
                    module.games().spectate(pl, g);
                } else {
                    module.plugin().messages().send(pl, "game.spectate-unavailable");
                }
            });
        }
        if (games.isEmpty() && s.isConfigurationSection("empty")) {
            menu.set(s.getInt("empty.slot", 22), Items.parse(s.getConfigurationSection("empty"), Map.of()), null);
        }
        if (s.isConfigurationSection("refresh")) {
            menu.set(s.getInt("refresh.slot", rows * 9 - 5), Items.parse(s.getConfigurationSection("refresh"), Map.of()), this::open);
        }
        menu.open(p);
    }
}
