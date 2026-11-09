package dev.tearsmilk.miharu.core.command;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.bots.BotsModule;
import dev.tearsmilk.miharu.core.kit.Kit;
import dev.tearsmilk.miharu.core.menu.Menu;
import dev.tearsmilk.miharu.core.util.Items;
import dev.tearsmilk.miharu.core.util.Text;
import dev.tearsmilk.miharu.duels.DuelKits;
import dev.tearsmilk.miharu.duels.DuelsModule;
import dev.tearsmilk.miharu.duels.ItemCatalog;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** /miharu adm-items — все предметы из всех китов, только просмотр (OP). */
final class AdminItemsMenu {

    private final Miharu plugin;

    AdminItemsMenu(Miharu plugin) {
        this.plugin = plugin;
    }

    void open(Player p, int page) {
        List<ItemCatalog.Found> all = ItemCatalog.collect(plugin);
        int pages = Math.max(1, (all.size() + 44) / 45);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menu menu = new Menu(6, Text.mm(plugin.messages().raw("admin.items-title"),
                Map.of("page", pg + 1, "pages", pages, "count", all.size())));
        for (int i = 0; i < 45 && pg * 45 + i < all.size(); i++) {
            ItemCatalog.Found f = all.get(pg * 45 + i);
            ItemStack shown = f.item().clone();
            ItemMeta meta = shown.getItemMeta();
            List<net.kyori.adventure.text.Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(net.kyori.adventure.text.Component.empty());
            lore.add(Text.item(plugin.messages().raw("admin.items-kits"), Map.of("count", f.kits().size())));
            int n = 0;
            for (String k : f.kits()) {
                if (n++ >= 8) {
                    lore.add(Text.item("<dark_gray>… и ещё " + (f.kits().size() - 8), Map.of()));
                    break;
                }
                lore.add(Text.item("<dark_gray>• <gray>" + k, Map.of()));
            }
            meta.lore(lore);
            shown.setItemMeta(meta);
            menu.set(i, shown, null);
        }
        ItemStack glass = Items.base("GRAY_STAINED_GLASS_PANE");
        ItemMeta gm = glass.getItemMeta();
        gm.displayName(Text.item(" ", Map.of()));
        glass.setItemMeta(gm);
        for (int s = 45; s < 54; s++) {
            menu.set(s, glass.clone(), null);
        }
        if (pg > 0) {
            menu.set(45, button("ARROW", plugin.messages().raw("admin.items-prev")), pl -> open(pl, pg - 1));
        }
        menu.set(49, button("BOOK", Text.apply(plugin.messages().raw("admin.items-info"), Map.of("page", pg + 1, "pages", pages, "count", all.size()))), null);
        if (pg < pages - 1) {
            menu.set(53, button("ARROW", plugin.messages().raw("admin.items-next")), pl -> open(pl, pg + 1));
        }
        menu.open(p);
    }

    private static ItemStack button(String material, String name) {
        ItemStack it = Items.base(material);
        ItemMeta m = it.getItemMeta();
        m.displayName(Text.item(name, Map.of()));
        it.setItemMeta(m);
        return it;
    }
}
