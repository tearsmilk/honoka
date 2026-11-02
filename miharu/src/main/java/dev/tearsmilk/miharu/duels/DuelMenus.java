package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.bots.BotsModule;
import dev.tearsmilk.miharu.bots.ai.BotDifficulty;
import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.core.menu.Menu;
import dev.tearsmilk.miharu.core.util.Items;
import dev.tearsmilk.miharu.core.util.Text;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Каменный меч в лобби → выбор кита → бот или игрок → (сложность бота) → игра / очередь.
 * Вид меню китов — modules/duels/kits/config.yml, остальные — modules/duels/config.yml → menus.
 */
public final class DuelMenus {

    private final DuelsModule module;

    DuelMenus(DuelsModule module) {
        this.module = module;
    }

    public void openKits(Player p) {
        openKits(p, module.kits());
    }

    /** Меню китов категории (1.8 — каменный меч, 1.9 — золотой). */
    public void openKits(Player p, DuelKits category) {
        ConfigurationSection m = category.menuConfig().getConfigurationSection("menu");
        if (m == null) {
            m = category.menuConfig().createSection("menu");
        }
        Menu menu = new Menu(m.getInt("rows", 4), Text.mm(m.getString("title", "Киты")));
        for (DuelKits.Entry e : category.all()) {
            if (!e.enabled()) {
                continue;
            }
            KitSpec spec = e.spec();
            Map<String, Object> ph = placeholders(p, spec);
            ConfigurationSection icon = e.icon();
            if (icon == null) {
                icon = m.getConfigurationSection("default-icon");
            }
            menu.set(e.slot(), Items.parse(icon, ph), pl -> {
                click(pl);
                chooseKit(pl, spec);
            });
        }
        filler(menu, m.getConfigurationSection("filler"));
        menu.open(p);
    }

    private Map<String, Object> placeholders(Player p, KitSpec spec) {
        Map<String, Object> ph = new HashMap<>();
        ph.put("kit", spec.display());
        ph.put("version", spec.version().id());
        ph.put("queue", module.queue().size(spec.id()));
        ph.put("playing", module.playing(spec.id()));
        ph.put("wins", module.stats().wins(p.getUniqueId(), spec.id()));
        ph.put("losses", module.stats().losses(p.getUniqueId(), spec.id()));
        ph.put("bot", spec.bot().enabled() && module.bots() != null ? module.plugin().messages().raw("duels.bot-yes") : module.plugin().messages().raw("duels.bot-no"));
        return ph;
    }

    private void chooseKit(Player p, KitSpec spec) {
        if (!spec.bot().enabled() || module.bots() == null) {
            p.closeInventory();
            module.queue().join(p, spec);
            return;
        }
        openOpponent(p, spec);
    }

    public void openOpponent(Player p, KitSpec spec) {
        ConfigurationSection s = module.config().getConfigurationSection("menus.opponent");
        Map<String, Object> ph = placeholders(p, spec);
        Menu menu = new Menu(s.getInt("rows", 3), Text.mm(s.getString("title", "Соперник"), ph));
        menu.set(s.getInt("bot.slot", 11), Items.parse(s.getConfigurationSection("bot"), ph), pl -> {
            click(pl);
            openDifficulty(pl, spec);
        });
        menu.set(s.getInt("player.slot", 15), Items.parse(s.getConfigurationSection("player"), ph), pl -> {
            click(pl);
            pl.closeInventory();
            module.queue().join(pl, spec);
        });
        if (s.isConfigurationSection("back")) {
            menu.set(s.getInt("back.slot", 22), Items.parse(s.getConfigurationSection("back"), ph), pl -> {
                click(pl);
                openKits(pl, module.category(spec.version().id()));
            });
        }
        filler(menu, s.getConfigurationSection("filler"));
        menu.open(p);
    }

    public void openDifficulty(Player p, KitSpec spec) {
        BotsModule bots = module.bots();
        if (bots == null) {
            return;
        }
        ConfigurationSection s = module.config().getConfigurationSection("menus.difficulty");
        Map<String, Object> ph = placeholders(p, spec);
        Menu menu = new Menu(s.getInt("rows", 3), Text.mm(s.getString("title", "Сложность"), ph));
        ConfigurationSection items = s.getConfigurationSection("items");
        if (items != null) {
            for (String id : items.getKeys(false)) {
                BotDifficulty d = bots.difficulty(id);
                if (d == null) {
                    continue;
                }
                Map<String, Object> iph = new HashMap<>(ph);
                iph.put("difficulty", d.display());
                iph.put("cps_min", Text.num(d.cpsMin()));
                iph.put("cps_max", Text.num(d.cpsMax()));
                iph.put("reach", Text.num(d.reach()));
                ConfigurationSection it = items.getConfigurationSection(id);
                menu.set(it.getInt("slot"), Items.parse(it, iph), pl -> {
                    click(pl);
                    pl.closeInventory();
                    module.queue().leave(pl, true);
                    bots.startKit(pl, spec, d);
                });
            }
        }
        if (s.isConfigurationSection("back")) {
            menu.set(s.getInt("back.slot", 22), Items.parse(s.getConfigurationSection("back"), ph), pl -> {
                click(pl);
                openOpponent(pl, spec);
            });
        }
        filler(menu, s.getConfigurationSection("filler"));
        menu.open(p);
    }

    private void filler(Menu menu, ConfigurationSection f) {
        if (f == null) {
            f = module.config().getConfigurationSection("menus.filler");
        }
        if (f != null && f.getBoolean("enabled", true)) {
            menu.fill(Items.parse(f, Map.of()));
        }
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }
}
