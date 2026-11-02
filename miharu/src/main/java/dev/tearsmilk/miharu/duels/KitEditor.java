package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.core.kit.Kit;
import dev.tearsmilk.miharu.core.kit.KitSpec;
import dev.tearsmilk.miharu.core.menu.Menu;
import dev.tearsmilk.miharu.core.util.Items;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.game.GameModule;
import dev.tearsmilk.miharu.pvp.PvpModule;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;

/**
 * /kit edit — свои раскладки китов. Меню китов (вкладки 1.8 / 1.9 / мини-игры) → редактор:
 * ряды 1–3 = инвентарь, ряд 4 = хотбар, внизу «Сохранить и выйти», «Сбросить», «Отмена».
 */
public final class KitEditor implements Listener {

    private static final NamespacedKey DEF = new NamespacedKey("miharu", "kit_def");

    /** Окно редактора: предметы двигать можно, вынести к себе — нет. */
    private final class Holder implements InventoryHolder {
        final KitSpec spec;
        final DuelKits category;
        Inventory inv;
        boolean done;

        Holder(KitSpec spec, DuelKits category) {
            this.spec = spec;
            this.category = category;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final DuelsModule module;

    KitEditor(DuelsModule module) {
        this.module = module;
    }

    private ConfigurationSection cfg() {
        return module.config().getConfigurationSection("kit-editor");
    }

    // --- меню китов ---

    public void openKits(Player p, DuelKits category) {
        GameModule games = module.plugin().module(GameModule.class);
        if (games != null && games.games().busy(p)) {
            module.plugin().messages().send(p, "kit-editor.in-game");
            return;
        }
        ConfigurationSection m = cfg().getConfigurationSection("menu");
        Menu menu = new Menu(6, Text.mm(m.getString("title", "Раскладка китов")));
        for (DuelKits cat : List.of(module.kits(), module.modernKits(), module.minigameKits())) {
            String key = cat == module.kits() ? "tab-legacy" : cat == module.modernKits() ? "tab-modern" : "tab-minigames";
            ConfigurationSection t = m.getConfigurationSection(key + (cat == category ? ".selected" : ".normal"));
            if (t != null) {
                menu.set(m.getInt(key + ".slot"), Items.parse(t, Map.of()), pl -> {
                    click(pl);
                    openKits(pl, cat);
                });
            }
        }
        List<Integer> slots = m.getIntegerList("kit-slots");
        int i = 0;
        for (DuelKits.Entry e : category.all()) {
            if (!e.enabled() || i >= slots.size()) {
                continue;
            }
            KitSpec spec = e.spec();
            ConfigurationSection iconCfg = e.icon() != null ? e.icon() : m.getConfigurationSection("default-icon");
            ItemStack icon = Items.parse(iconCfg, Map.of("kit", spec.display(), "queue", 0, "playing", 0, "wins", 0, "losses", 0, "bot", "",
                    "version", spec.version().id()));
            ItemMeta meta = icon.getItemMeta();
            boolean custom = module.layouts().get(p.getUniqueId(), spec.id()) != null;
            meta.lore(Text.lore(List.of(module.plugin().messages().raw(custom ? "kit-editor.lore-custom" : "kit-editor.lore-default"),
                    "", module.plugin().messages().raw("kit-editor.lore-click")), Map.of()));
            icon.setItemMeta(meta);
            menu.set(slots.get(i++), icon, pl -> {
                click(pl);
                openEditor(pl, spec, category);
            });
        }
        ConfigurationSection f = m.getConfigurationSection("filler");
        if (f != null && f.getBoolean("enabled", true)) {
            menu.fill(Items.parse(f, Map.of()));
        }
        menu.open(p);
    }

    // --- редактор ---

    private static int guiToInv(int gui) {
        return gui < 27 ? gui + 9 : gui - 27;     // 0–26 → 9–35, 27–35 → хотбар 0–8
    }

    private static int invToGui(int inv) {
        return inv >= 9 ? inv - 9 : inv + 27;
    }

    public void openEditor(Player p, KitSpec spec, DuelKits category) {
        Holder h = new Holder(spec, category);
        ConfigurationSection e = cfg().getConfigurationSection("editor");
        Inventory inv = Bukkit.createInventory(h, 54, Text.mm(e.getString("title", "{kit}"), Map.of("kit", spec.display())));
        h.inv = inv;
        fill(inv, spec, module.layouts().get(p.getUniqueId(), spec.id()));
        ConfigurationSection sep = e.getConfigurationSection("separator");
        for (int s = 36; s < 45; s++) {
            inv.setItem(s, Items.parse(sep, Map.of()));
        }
        ConfigurationSection bottom = e.getConfigurationSection("bottom-filler");
        for (int s = 45; s < 54; s++) {
            inv.setItem(s, Items.parse(bottom, Map.of()));
        }
        inv.setItem(e.getInt("reset.slot", 45), Items.parse(e.getConfigurationSection("reset"), Map.of()));
        inv.setItem(e.getInt("save.slot", 49), Items.parse(e.getConfigurationSection("save"), Map.of()));
        inv.setItem(e.getInt("cancel.slot", 53), Items.parse(e.getConfigurationSection("cancel"), Map.of()));
        p.openInventory(inv);
    }

    private void fill(Inventory inv, KitSpec spec, int[] layout) {
        for (int s = 0; s < 36; s++) {
            inv.setItem(s, null);
        }
        PvpModule pvp = module.plugin().module(PvpModule.class);
        ItemStack[] def = spec.kit().defaults(pvp.combat(), spec.profile(), Kit.Team.RED);
        for (int d = 0; d < 36; d++) {
            if (def[d] != null) {
                ItemMeta meta = def[d].getItemMeta();
                meta.getPersistentDataContainer().set(DEF, PersistentDataType.INTEGER, d);
                def[d].setItemMeta(meta);
            }
        }
        ItemStack[] arranged = Kit.arrange(def, layout);
        for (int i = 0; i < 36; i++) {
            if (arranged[i] != null) {
                inv.setItem(invToGui(i), arranged[i]);
            }
        }
    }

    private void save(Player p, Holder h) {
        int[] layout = new int[36];
        java.util.Arrays.fill(layout, -1);
        for (int g = 0; g < 36; g++) {
            ItemStack item = h.inv.getItem(g);
            if (item == null || !item.hasItemMeta()) {
                continue;
            }
            Integer d = item.getItemMeta().getPersistentDataContainer().get(DEF, PersistentDataType.INTEGER);
            if (d != null) {
                layout[guiToInv(g)] = d;
            }
        }
        module.layouts().set(p.getUniqueId(), h.spec.id(), layout);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Holder h) || !(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        int raw = e.getRawSlot();
        ClickType c = e.getClick();
        boolean top = raw >= 0 && raw < 54;
        // кнопки
        if (top && raw >= 36) {
            e.setCancelled(true);
            ConfigurationSection ed = cfg().getConfigurationSection("editor");
            if (raw == ed.getInt("save.slot", 49)) {
                p.setItemOnCursor(null);
                save(p, h);
                h.done = true;
                p.closeInventory();
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
                module.plugin().messages().send(p, "kit-editor.saved", "kit", h.spec.display());
            } else if (raw == ed.getInt("reset.slot", 45)) {
                p.setItemOnCursor(null);
                fill(h.inv, h.spec, null);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.8f);
            } else if (raw == ed.getInt("cancel.slot", 53)) {
                p.setItemOnCursor(null);
                h.done = true;
                p.closeInventory();
                module.plugin().messages().send(p, "kit-editor.cancelled");
            }
            return;
        }
        // в свой инвентарь и хитрые клики — нельзя (иначе предметы уйдут из редактора)
        if (!top || c.isShiftClick() || c == ClickType.NUMBER_KEY || c == ClickType.DOUBLE_CLICK || c == ClickType.SWAP_OFFHAND
                || c == ClickType.DROP || c == ClickType.CONTROL_DROP || c == ClickType.MIDDLE || c == ClickType.CREATIVE
                || e.getAction() == InventoryAction.COLLECT_TO_CURSOR || e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof Holder) {
            for (int raw : e.getRawSlots()) {
                if (raw >= 36) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof Holder h && e.getPlayer() instanceof Player p) {
            // предмет на курсоре не должен уйти к игроку
            ItemStack cursor = p.getItemOnCursor();
            if (cursor.hasItemMeta() && cursor.getItemMeta().getPersistentDataContainer().has(DEF)) {
                p.setItemOnCursor(null);
            }
            if (!h.done) {
                module.plugin().messages().send(p, "kit-editor.closed");
            }
        }
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }
}
