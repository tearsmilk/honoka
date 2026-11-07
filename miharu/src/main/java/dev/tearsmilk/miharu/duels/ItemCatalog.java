package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.bots.BotsModule;
import dev.tearsmilk.miharu.core.kit.Kit;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Все предметы из всех китов без повторов (для /miharu adm-items и сборки своего кита). */
public final class ItemCatalog {

    public record Found(ItemStack item, Set<String> kits) {
    }

    private ItemCatalog() {
    }

    public static List<Found> collect(Miharu plugin) {
        List<Found> out = new ArrayList<>();
        DuelsModule duels = plugin.module(DuelsModule.class);
        if (duels != null) {
            for (DuelKits.Entry e : duels.allEntries()) {
                add(out, e.spec().kit(), e.spec().id());
            }
        }
        BotsModule bots = plugin.module(BotsModule.class);
        if (bots != null) {
            for (Kit k : bots.kits().all()) {
                add(out, k, "bot:" + k.id());
            }
        }
        return out;
    }

    private static void add(List<Found> out, Kit kit, String name) {
        List<ItemStack> items = new ArrayList<>(kit.armor().values());
        items.addAll(kit.items().values());
        if (kit.fill() != null) {
            items.add(kit.fill());
        }
        for (ItemStack it : items) {
            if (it == null || it.getType().isAir()) {
                continue;
            }
            Found same = null;
            for (Found f : out) {
                if (f.item().isSimilar(it)) {
                    same = f;
                    break;
                }
            }
            if (same == null) {
                same = new Found(it.clone(), new LinkedHashSet<>());
                out.add(same);
            }
            same.kits().add(name);
        }
    }
}
