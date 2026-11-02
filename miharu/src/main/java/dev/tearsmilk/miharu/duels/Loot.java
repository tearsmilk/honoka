package dev.tearsmilk.miharu.duels;

import dev.tearsmilk.miharu.arena.world.ArenaInstance;
import dev.tearsmilk.miharu.core.util.Items;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Лут сундуков (SkyWars): при старте матча все сундуки арены заполняются из таблицы
 * modules/duels/config.yml → loot-tables.&lt;имя&gt;.
 */
public final class Loot {

    private record Entry(ConfigurationSection item, int weight, int min, int max) {
    }

    private final DuelsModule module;

    Loot(DuelsModule module) {
        this.module = module;
    }

    /** @return сколько сундуков заполнено */
    public int fill(ArenaInstance inst, String table) {
        ConfigurationSection t = module.config().getConfigurationSection("loot-tables." + table);
        if (t == null) {
            module.plugin().getLogger().warning("Нет таблицы лута loot-tables." + table);
            return 0;
        }
        List<Entry> entries = new ArrayList<>();
        int total = 0;
        for (Map<?, ?> m : t.getMapList("items")) {
            MemoryConfiguration c = new MemoryConfiguration();
            ConfigurationSection s = c.createSection("i", m);
            int w = Math.max(1, s.getInt("weight", 10));
            entries.add(new Entry(s, w, Math.max(1, s.getInt("min", s.getInt("amount", 1))), Math.max(1, s.getInt("max", s.getInt("amount", 1)))));
            total += w;
        }
        if (entries.isEmpty()) {
            return 0;
        }
        int rollsMin = t.getInt("rolls.min", 4);
        int rollsMax = Math.max(rollsMin, t.getInt("rolls.max", 7));
        Location min = inst.origin();
        Location max = min.clone().add(inst.arena().size());
        int filled = 0;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int cx = min.getBlockX() >> 4; cx <= max.getBlockX() >> 4; cx++) {
            for (int cz = min.getBlockZ() >> 4; cz <= max.getBlockZ() >> 4; cz++) {
                Chunk chunk = inst.world().getChunkAt(cx, cz);
                for (BlockState st : chunk.getTileEntities()) {
                    if (!(st instanceof Chest chest)) {
                        continue;
                    }
                    Inventory inv = chest.getBlockInventory();
                    inv.clear();
                    int rolls = r.nextInt(rollsMin, rollsMax + 1);
                    for (int i = 0; i < rolls; i++) {
                        int pick = r.nextInt(total);
                        Entry e = entries.getLast();
                        for (Entry en : entries) {
                            pick -= en.weight();
                            if (pick < 0) {
                                e = en;
                                break;
                            }
                        }
                        ItemStack item = Items.parse(e.item(), Map.of());
                        item.setAmount(r.nextInt(e.min(), e.max() + 1));
                        int slot = r.nextInt(inv.getSize());
                        for (int tries = 0; tries < inv.getSize() && inv.getItem(slot) != null; tries++) {
                            slot = (slot + 1) % inv.getSize();
                        }
                        inv.setItem(slot, item);
                    }
                    filled++;
                }
            }
        }
        return filled;
    }
}
