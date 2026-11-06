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
import org.bukkit.inventory.ItemStack;

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
        int rows = m.getInt("rows", 4);
        for (DuelKits.Entry e : category.all()) {
            if (e.enabled()) {
                rows = Math.max(rows, e.slot() / 9 + 2); // меню растёт под занятые слоты (+ ряд снизу)
            }
        }
        Menu menu = new Menu(Math.min(6, rows), Text.mm(m.getString("title", "Киты")));
        for (DuelKits.Entry e : category.all()) {
            if (!e.enabled()) {
                continue;
            }
            KitSpec spec = e.spec();
            Map<String, Object> ph = placeholders(p, spec);
            if (e.locked()) {
                // заблокированный кит: барьер, запуск выключен
                ConfigurationSection li = m.getConfigurationSection("locked-icon");
                ItemStack lockedIcon = li != null ? Items.parse(li, ph) : new ItemStack(org.bukkit.Material.BARRIER);
                menu.set(e.slot(), lockedIcon, pl -> {
                    pl.playSound(pl.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                    module.plugin().messages().send(pl, "duels.kit-locked", "kit", spec.display());
                });
                continue;
            }
            ConfigurationSection icon = e.icon();
            if (icon == null) {
                icon = m.getConfigurationSection("default-icon");
            }
            menu.set(e.slot(), Items.parse(icon, ph), pl -> {
                click(pl);
                if (!e.variants().isEmpty()) {
                    openVariants(pl, e, category);
                } else {
                    chooseKit(pl, spec);
                }
            });
        }
        if (!category.isMinigames()) {
            addCustomRow(p, menu, category.version());
        }
        filler(menu, m.getConfigurationSection("filler"));
        menu.open(p);
    }

    /** Нижний ряд меню 1.8/1.9: свои киты игрока (кнопка — в очередь «Свой кит»). */
    private void addCustomRow(Player p, Menu menu, dev.tearsmilk.miharu.pvp.CombatVersion version) {
        ConfigurationSection c = module.config().getConfigurationSection("custom-kits.duels-menu");
        if (c == null || !c.getBoolean("enabled", true)) {
            return;
        }
        java.util.List<Integer> slots = c.getIntegerList("slots");
        for (int i = 1; i <= CustomKits.SLOTS && i - 1 < slots.size(); i++) {
            int n = i;
            CustomKits.CustomKit kit = module.customKits().get(p.getUniqueId(), n);
            Map<String, Object> ph = new HashMap<>();
            ph.put("slot", n);
            ph.put("version", version.id()); // свой кит играется в версии меню, из которого запущен
            ph.put("name", kit == null ? "" : kit.name());
            ph.put("items", kit == null ? 0 : kit.size());
            ph.put("queue", module.queue().size("custom-" + version.id().replace(".", "")));
            String key = kit == null ? "empty" : "ready";
            menu.set(slots.get(i - 1), Items.parse(c.getConfigurationSection(key), ph), pl -> {
                click(pl);
                if (kit == null) {
                    pl.closeInventory();
                    module.builder().openSlots(pl);
                } else {
                    pl.closeInventory();
                    module.queueCustom(pl, n, version);
                }
            });
        }
    }

    /**
     * Выбор варианта: сначала число игроков (1x1 / 1x3 / 1x4), потом длина матча (3/5/7).
     * Если остаётся один вариант — сразу в очередь.
     */
    public void openVariants(Player p, DuelKits.Entry entry, DuelKits category) {
        openVariants(p, entry, category, null);
    }

    private void openVariants(Player p, DuelKits.Entry entry, DuelKits category, Integer players) {
        java.util.List<Integer> playerOpts = entry.variants().stream().map(v -> v.rules().players()).distinct().toList();
        if (players == null && playerOpts.size() > 1) {
            // шаг 1: сколько игроков — по одному представителю на каждое число
            java.util.List<KitSpec> reps = new java.util.ArrayList<>();
            for (int n : playerOpts) {
                entry.variants().stream().filter(v -> v.rules().players() == n).findFirst().ifPresent(reps::add);
            }
            showOptions(p, entry, category, reps, true, v -> openVariants(p, entry, category, v.rules().players()));
            return;
        }
        int n = players != null ? players : playerOpts.isEmpty() ? 2 : playerOpts.getFirst();
        java.util.List<KitSpec> list = entry.variants().stream().filter(v -> v.rules().players() == n).toList();
        if (list.size() == 1) {
            chooseKit(p, list.getFirst());
            return;
        }
        showOptions(p, entry, category, list, false, v -> chooseKit(p, v));
    }

    private void showOptions(Player p, DuelKits.Entry entry, DuelKits category, java.util.List<KitSpec> options, boolean byPlayers,
                             java.util.function.Consumer<KitSpec> action) {
        ConfigurationSection s = module.config().getConfigurationSection("menus.variants");
        Map<String, Object> ph = placeholders(p, entry.spec());
        Menu menu = new Menu(s.getInt("rows", 3), Text.mm(s.getString("title", "Вариант"), ph));
        java.util.List<Integer> slots = s.getIntegerList("slots");
        if (options.size() > slots.size()) {
            // вариантов больше, чем мест в конфиге (BoxPvP 1x4/2x4/3x4/4x4) — раскладываем по центру 2-го ряда
            int k = Math.min(7, options.size());
            slots = new java.util.ArrayList<>();
            for (int i = 0; i < k; i++) {
                slots.add(k <= 4 ? 13 - (k - 1) + i * 2 : 13 - (k - 1) / 2 + i);
            }
        }
        for (int i = 0; i < options.size() && i < slots.size(); i++) {
            KitSpec v = options.get(i);
            int pl = v.rules().players();
            int count = byPlayers ? pl : v.rules().scoreToWin();
            Map<String, Object> vph = placeholders(p, v);
            vph.put("count", count);
            vph.put("format", module.format(v));
            vph.put("opponents", Math.max(1, pl - 1));
            vph.put("goal", Text.plain(Text.mm(module.plugin().messages().raw(byPlayers ? "minigames.goal-players"
                    : "minigames.goal-" + v.rules().objective().name().toLowerCase()))));
            vph.put("variant_info", Text.apply(module.plugin().messages().raw(byPlayers ? "minigames.variant-players" : "minigames.variant-rounds"), vph));
            int waiting = byPlayers
                    ? entry.variants().stream().filter(x -> x.rules().players() == pl).mapToInt(x -> module.queue().size(x.id())).sum()
                    : module.queue().size(v.id());
            vph.put("queue", waiting + (pl > 2 ? "/" + pl : ""));
            vph.put("kit", byPlayers ? entry.spec().display() + " <gray>· " + vph.get("format") : v.display());
            ItemStack icon = Items.parse(s.getConfigurationSection("icon"), vph);
            icon.setAmount(Math.max(1, Math.min(64, byPlayers ? (pl <= 2 ? 1 : pl) : count)));
            menu.set(slots.get(i), icon, who -> {
                click(who);
                action.accept(v);
            });
        }
        if (s.isConfigurationSection("back")) {
            menu.set(s.getInt("back.slot", 22), Items.parse(s.getConfigurationSection("back"), ph), who -> {
                click(who);
                openKits(who, category);
            });
        }
        filler(menu, s.getConfigurationSection("filler"));
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
                openKits(pl, spec.id().startsWith("mg-") ? module.minigameKits() : module.category(spec.version().id()));
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
