package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.bots.BotsModule;
import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.core.menu.Menu;
import dev.tearsmilk.miharu.core.util.Items;
import dev.tearsmilk.miharu.core.util.Text;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Статистика (предмет в 7-м слоте лобби, /stats [игрок]): тиры и ELO по режимам, дуэли, боты, клик-тест. */
public final class StatsMenu {

    private final DuelsModule module;

    StatsMenu(DuelsModule module) {
        this.module = module;
    }

    private Map<String, Object> common(Player t) {
        Map<String, Object> ph = new HashMap<>();
        ph.put("player", t.getName());
        int wf = 0, lf = 0, ws = 0, ls = 0;
        for (DuelKits.Entry e : module.kits().all()) {
            wf += module.stats().wins(t.getUniqueId(), e.spec().id());
            lf += module.stats().losses(t.getUniqueId(), e.spec().id());
        }
        for (DuelKits.Entry e : module.modernKits().all()) {
            ws += module.stats().wins(t.getUniqueId(), e.spec().id());
            ls += module.stats().losses(t.getUniqueId(), e.spec().id());
        }
        ph.put("duel_wins_fast", wf);
        ph.put("duel_losses_fast", lf);
        ph.put("duel_wins_slow", ws);
        ph.put("duel_losses_slow", ls);
        ph.put("duel_wins", wf + ws);
        ph.put("duel_losses", lf + ls);
        BotsModule bots = module.bots();
        ph.put("bot_wins", bots == null ? 0 : bots.stats().summary(t.getUniqueId()).wins(null));
        ph.put("bot_losses", bots == null ? 0 : bots.stats().summary(t.getUniqueId()).losses(null));
        ph.put("clicktest_best", bots == null ? 0 : bots.stats().summary(t.getUniqueId()).bestClickTest);
        ph.put("clicktest_cps", bots == null ? 0 : bots.stats().summary(t.getUniqueId()).bestCps);
        return ph;
    }

    public void open(Player viewer, Player target) {
        ConfigurationSection m = module.config().getConfigurationSection("stats-menu");
        if (m == null) {
            return;
        }
        Map<String, Object> ph = common(target);
        Menu menu = new Menu(m.getInt("rows", 5), Text.mm(m.getString("title", "Статистика"), ph));
        ConfigurationSection head = m.getConfigurationSection("head");
        if (head != null) {
            ItemStack item = Items.parse(head, ph);
            if (item.getItemMeta() instanceof SkullMeta sm) {
                sm.setOwningPlayer(target);
                item.setItemMeta(sm);
            }
            menu.set(head.getInt("slot", 4), item, null);
        }
        ConfigurationSection summary = m.getConfigurationSection("summary");
        if (summary != null) {
            for (String key : summary.getKeys(false)) {
                ConfigurationSection s = summary.getConfigurationSection(key);
                if (s != null) {
                    menu.set(s.getInt("slot"), Items.parse(s, ph), null);
                }
            }
        }
        ConfigurationSection f = m.getConfigurationSection("filler");
        if (f != null && f.getBoolean("enabled", true)) {
            menu.fill(Items.parse(f, Map.of()));
        }
        menu.open(viewer);
    }

    /** /stats [игрок]. */
    static final class Command implements BasicCommand {

        private final DuelsModule module;

        Command(DuelsModule module) {
            this.module = module;
        }

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            if (!(source.getSender() instanceof Player p)) {
                return;
            }
            Player t = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : p;
            if (t == null) {
                module.plugin().messages().send(p, "player-not-found", "player", args[0]);
                return;
            }
            module.statsMenu().open(p, t);
        }

        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
    }
}
