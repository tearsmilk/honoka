package dev.tearsmilk.miharu.bots.menu;

import dev.tearsmilk.miharu.bots.BotsModule;
import dev.tearsmilk.miharu.bots.ai.BotDifficulty;
import dev.tearsmilk.miharu.core.util.Items;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * /bot → режим → версия PvP (1.8 / 1.9) → сложность. Всё настраивается в modules/bots/menu.yml.
 * Материал может быть «nexo:id», заголовок — MiniMessage (глифы Nexo тоже работают).
 */
public final class BotMenu {

    private final BotsModule module;

    public BotMenu(BotsModule module) {
        this.module = module;
    }

    private YamlConfiguration cfg() {
        return module.menuConfig();
    }

    public void openModes(Player p) {
        ConfigurationSection s = cfg().getConfigurationSection("modes");
        Menu menu = new Menu(s.getInt("rows", 3), Text.mm(s.getString("title", "Режим")));
        ConfigurationSection items = s.getConfigurationSection("items");
        if (items != null) {
            for (String mode : items.getKeys(false)) {
                ConfigurationSection it = items.getConfigurationSection(mode);
                menu.set(it.getInt("slot"), Items.parse(it, Map.of()), pl -> {
                    click(pl);
                    openVersions(pl, mode);
                });
            }
        }
        filler(menu, s);
        menu.open(p);
    }

    public void openVersions(Player p, String mode) {
        ConfigurationSection s = cfg().getConfigurationSection("versions");
        Menu menu = new Menu(s.getInt("rows", 3), Text.mm(s.getString("title", "Версия"), Map.of("mode", modeName(mode))));
        ConfigurationSection items = s.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                CombatVersion v = CombatVersion.parse(key);
                if (v == null) {
                    continue;
                }
                ConfigurationSection it = items.getConfigurationSection(key);
                menu.set(it.getInt("slot"), Items.parse(it, Map.of("mode", modeName(mode))), pl -> {
                    click(pl);
                    openDifficulties(pl, mode, v);
                });
            }
        }
        back(menu, s, () -> openModes(p));
        filler(menu, s);
        menu.open(p);
    }

    public void openDifficulties(Player p, String mode, CombatVersion version) {
        ConfigurationSection s = cfg().getConfigurationSection("difficulties");
        Map<String, Object> ph = Map.of("mode", modeName(mode), "version", version.id());
        Menu menu = new Menu(s.getInt("rows", 3), Text.mm(s.getString("title", "Сложность"), ph));
        ConfigurationSection items = s.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                BotDifficulty d = module.difficulty(key);
                if (d == null) {
                    continue;
                }
                ConfigurationSection it = items.getConfigurationSection(key);
                var stats = module.stats().summary(p.getUniqueId());
                Map<String, Object> iph = Map.of("mode", modeName(mode), "version", version.id(), "difficulty", d.display(),
                        "wins", stats.wins(d.id()), "losses", stats.losses(d.id()), "cps_min", Text.num(d.cpsMin()),
                        "cps_max", Text.num(d.cpsMax()), "reach", Text.num(d.reach()));
                ItemStack icon = Items.parse(it, iph);
                menu.set(it.getInt("slot"), icon, pl -> {
                    click(pl);
                    pl.closeInventory();
                    module.start(pl, mode, version, d, null);
                });
            }
        }
        back(menu, s, () -> openVersions(p, mode));
        filler(menu, s);
        menu.open(p);
    }

    private String modeName(String mode) {
        return module.config().getString("modes." + mode + ".display", mode);
    }

    private void back(Menu menu, ConfigurationSection s, Runnable action) {
        ConfigurationSection b = s.getConfigurationSection("back");
        if (b != null) {
            menu.set(b.getInt("slot"), Items.parse(b, Map.of()), pl -> {
                click(pl);
                action.run();
            });
        }
    }

    private void filler(Menu menu, ConfigurationSection s) {
        ConfigurationSection f = s.getConfigurationSection("filler");
        if (f == null) {
            f = cfg().getConfigurationSection("filler");
        }
        if (f != null && f.getBoolean("enabled", true)) {
            menu.fill(Items.parse(f, Map.of()));
        }
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }
}
