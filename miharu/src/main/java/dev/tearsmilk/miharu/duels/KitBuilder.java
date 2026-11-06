package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.core.menu.Menu;
import dev.tearsmilk.miharu.core.util.Items;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.game.GameModule;
import dev.tearsmilk.miharu.pvp.CombatVersion;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /kit create — свои киты (до 3). Пока идёт сборка, инвентарь игрока убран:
 * из «Каталога» берёшь копии предметов себе, раскладываешь их в окне кита и сохраняешь.
 */
public final class KitBuilder implements Listener {

    private static final int[] ARMOR_SLOTS = {36, 37, 38, 39, 40};
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFF_HAND};

    private final class Session {
        final int slot;
        final CombatVersion version;
        final ItemStack[] savedInventory;
        Inventory builder;
        boolean switching;
        int page;

        Session(int slot, CombatVersion version, ItemStack[] saved) {
            this.slot = slot;
            this.version = version;
            this.savedInventory = saved;
        }
    }

    private static final class BuilderHolder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final class CatalogHolder implements InventoryHolder {
        Inventory inv;
        final List<ItemStack> items;

        CatalogHolder(List<ItemStack> items) {
            this.items = items;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final DuelsModule module;
    private final Map<UUID, Session> sessions = new HashMap<>();

    KitBuilder(DuelsModule module) {
        this.module = module;
    }

    private ConfigurationSection cfg() {
        return module.config().getConfigurationSection("custom-kits");
    }

    public boolean active(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    // --- меню слотов ---

    public void openSlots(Player p) {
        GameModule games = module.plugin().module(GameModule.class);
        if (games != null && games.games().busy(p)) {
            module.plugin().messages().send(p, "custom-kits.in-game");
            return;
        }
        ConfigurationSection m = cfg().getConfigurationSection("slots-menu");
        Menu menu = new Menu(m.getInt("rows", 3), Text.mm(m.getString("title", "Свои киты")));
        List<Integer> slots = m.getIntegerList("slots");
        for (int i = 1; i <= CustomKits.SLOTS && i - 1 < slots.size(); i++) {
            int n = i;
            CustomKits.CustomKit kit = module.customKits().get(p.getUniqueId(), n);
            Map<String, Object> ph = Map.of("slot", n, "name", kit == null ? "" : kit.name(), "version", kit == null ? "" : kit.version().id(),
                    "items", kit == null ? 0 : kit.size());
            ItemStack icon = Items.parse(m.getConfigurationSection(kit == null ? "empty" : "filled"), ph);
            menu.set(slots.get(i - 1), icon, pl -> {
                click(pl);
                if (kit == null) {
                    start(pl, n, CombatVersion.LEGACY, null); // версия не выбирается: кит общий для 1.8 и 1.9
                }
            });
            // ПКМ по готовому — удалить: Menu не различает кнопки, поэтому удаление — отдельной кнопкой под слотом
            if (kit != null) {
                menu.set(slots.get(i - 1) + 9, Items.parse(m.getConfigurationSection("delete"), ph), pl -> {
                    module.customKits().delete(pl.getUniqueId(), n);
                    pl.playSound(pl.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.6f, 1f);
                    module.plugin().messages().send(pl, "custom-kits.deleted", "slot", n);
                    openSlots(pl);
                });
                menu.set(slots.get(i - 1), icon, pl -> {
                    click(pl);
                    start(pl, n, CombatVersion.LEGACY, kit);
                });
            }
        }
        ConfigurationSection f = m.getConfigurationSection("filler");
        if (f != null && f.getBoolean("enabled", true)) {
            menu.fill(Items.parse(f, Map.of()));
        }
        menu.open(p);
    }

    // --- сессия ---

    private static int guiToInv(int gui) {
        return gui < 27 ? gui + 9 : gui - 27;
    }

    private static int invToGui(int inv) {
        return inv >= 9 ? inv - 9 : inv + 27;
    }

    private void start(Player p, int slot, CombatVersion version, CustomKits.CustomKit existing) {
        var duels = module.queue();
        duels.leave(p, true);
        ItemStack[] saved = p.getInventory().getContents().clone();
        for (int i = 0; i < saved.length; i++) {
            saved[i] = saved[i] == null ? null : saved[i].clone();
        }
        Session s = new Session(slot, version, saved);
        sessions.put(p.getUniqueId(), s);
        p.getInventory().clear();
        BuilderHolder h = new BuilderHolder();
        ConfigurationSection b = cfg().getConfigurationSection("builder");
        Inventory inv = Bukkit.createInventory(h, 54, Text.mm(b.getString("title", "Свой кит"), Map.of("slot", slot, "version", "")));
        h.inv = inv;
        s.builder = inv;
        if (existing != null) {
            existing.items().forEach((i, it) -> {
                if (i >= 0 && i < 36) {
                    inv.setItem(invToGui(i), it.clone());
                }
            });
            for (int k = 0; k < ARMOR.length; k++) {
                ItemStack it = existing.armor().get(ARMOR[k]);
                if (it != null) {
                    inv.setItem(ARMOR_SLOTS[k], it.clone());
                }
            }
        }
        decorate(inv);
        p.openInventory(inv);
        module.plugin().messages().send(p, "custom-kits.build-started", "slot", slot);
    }

    private void decorate(Inventory inv) {
        ConfigurationSection b = cfg().getConfigurationSection("builder");
        ItemStack filler = Items.parse(b.getConfigurationSection("filler"), Map.of());
        for (int s = 41; s < 54; s++) {
            inv.setItem(s, filler.clone());
        }
        for (String key : List.of("catalog", "clear", "save", "exit")) {
            ConfigurationSection c = b.getConfigurationSection(key);
            inv.setItem(c.getInt("slot"), Items.parse(c, Map.of()));
        }
        ItemStack hint = Items.parse(b.getConfigurationSection("armor-hint"), Map.of());
        inv.setItem(41, hint);
    }

    private void end(Player p, boolean message) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) {
            return;
        }
        p.setItemOnCursor(null);
        p.getInventory().clear();
        p.getInventory().setContents(s.savedInventory);
        p.updateInventory();
        if (message) {
            module.plugin().messages().send(p, "custom-kits.exited");
        }
    }

    private void save(Player p, Session s) {
        Map<Integer, ItemStack> items = new LinkedHashMap<>();
        for (int g = 0; g < 36; g++) {
            ItemStack it = s.builder.getItem(g);
            if (it != null && !it.getType().isAir()) {
                items.put(guiToInv(g), it.clone());
            }
        }
        Map<EquipmentSlot, ItemStack> armor = new EnumMap<>(EquipmentSlot.class);
        for (int k = 0; k < ARMOR.length; k++) {
            ItemStack it = s.builder.getItem(ARMOR_SLOTS[k]);
            if (it != null && !it.getType().isAir()) {
                armor.put(ARMOR[k], it.clone());
            }
        }
        if (items.isEmpty() && armor.isEmpty()) {
            module.plugin().messages().send(p, "custom-kits.empty");
            return;
        }
        String name = Text.apply(cfg().getString("default-name", "Свой кит #{slot}"), Map.of("slot", s.slot, "version", s.version.id()));
        module.customKits().save(p.getUniqueId(), new CustomKits.CustomKit(s.slot, name, s.version, armor, items));
        s.switching = true;
        p.closeInventory();
        end(p, false);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        module.plugin().messages().send(p, "custom-kits.build-saved", "slot", s.slot);
    }

    // --- каталог ---

    private void openCatalog(Player p, Session s, int page) {
        List<ItemStack> all = ItemCatalog.collect(module.plugin()).stream().map(ItemCatalog.Found::item).toList();
        int pages = Math.max(1, (all.size() + 44) / 45);
        int pg = Math.max(0, Math.min(page, pages - 1));
        s.page = pg;
        CatalogHolder h = new CatalogHolder(all);
        ConfigurationSection c = cfg().getConfigurationSection("catalog");
        Inventory inv = Bukkit.createInventory(h, 54, Text.mm(c.getString("title", "Каталог"), Map.of("page", pg + 1, "pages", pages)));
        h.inv = inv;
        for (int i = 0; i < 45 && pg * 45 + i < all.size(); i++) {
            inv.setItem(i, all.get(pg * 45 + i).clone());
        }
        ItemStack filler = Items.parse(c.getConfigurationSection("filler"), Map.of());
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, filler.clone());
        }
        if (pg > 0) {
            inv.setItem(45, Items.parse(c.getConfigurationSection("prev"), Map.of()));
        }
        inv.setItem(49, Items.parse(c.getConfigurationSection("back"), Map.of()));
        if (pg < pages - 1) {
            inv.setItem(53, Items.parse(c.getConfigurationSection("next"), Map.of()));
        }
        s.switching = true;
        p.openInventory(inv);
        s.switching = false;
    }

    private void backToBuilder(Player p, Session s) {
        s.switching = true;
        p.openInventory(s.builder);
        s.switching = false;
    }

    // --- события ---

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        Session s = sessions.get(p.getUniqueId());
        if (s == null) {
            return;
        }
        Object holder = e.getInventory().getHolder(false);
        int raw = e.getRawSlot();
        if (holder instanceof BuilderHolder) {
            if (raw >= 41 && raw < 54) {
                e.setCancelled(true);
                ConfigurationSection b = cfg().getConfigurationSection("builder");
                if (raw == b.getInt("catalog.slot")) {
                    openCatalog(p, s, s.page);
                } else if (raw == b.getInt("clear.slot")) {
                    for (int g = 0; g < 41; g++) {
                        s.builder.setItem(g, null);
                    }
                } else if (raw == b.getInt("save.slot")) {
                    save(p, s);
                } else if (raw == b.getInt("exit.slot")) {
                    s.switching = true;
                    p.closeInventory();
                    end(p, true);
                }
            }
            // остальное (слоты кита и свой инвентарь) — можно свободно двигать
            return;
        }
        if (holder instanceof CatalogHolder ch) {
            if (raw >= 0 && raw < 54) {
                e.setCancelled(true);
                if (raw < 45) {
                    int idx = s.page * 45 + raw;
                    if (idx < ch.items.size()) {
                        ItemStack copy = ch.items.get(idx).clone();
                        Map<Integer, ItemStack> left = p.getInventory().addItem(copy);
                        if (!left.isEmpty()) {
                            module.plugin().messages().send(p, "custom-kits.inventory-full");
                        } else {
                            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.4f, 1.4f);
                        }
                    }
                } else if (raw == 45) {
                    openCatalog(p, s, s.page - 1);
                } else if (raw == 53) {
                    openCatalog(p, s, s.page + 1);
                } else if (raw == 49) {
                    backToBuilder(p, s);
                }
            } else if (e.getClick().isShiftClick()) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !active(p)) {
            return;
        }
        Object holder = e.getInventory().getHolder(false);
        for (int raw : e.getRawSlots()) {
            if ((holder instanceof BuilderHolder && raw >= 41 && raw < 54) || (holder instanceof CatalogHolder && raw < 54)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) {
            return;
        }
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.switching) {
            return;
        }
        Object holder = e.getInventory().getHolder(false);
        if (holder instanceof BuilderHolder || holder instanceof CatalogHolder) {
            // Esc — выход без сохранения (в следующем тике, чтобы не мешать закрытию)
            Bukkit.getScheduler().runTask(module.plugin(), () -> {
                if (sessions.get(p.getUniqueId()) == s && !(p.getOpenInventory().getTopInventory().getHolder(false) instanceof BuilderHolder)
                        && !(p.getOpenInventory().getTopInventory().getHolder(false) instanceof CatalogHolder)) {
                    end(p, true);
                }
            });
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (active(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        end(e.getPlayer(), false);
    }

    void endAll() {
        for (UUID id : List.copyOf(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.closeInventory();
                end(p, false);
            }
        }
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }
}
