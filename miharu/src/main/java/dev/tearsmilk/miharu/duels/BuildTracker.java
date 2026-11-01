package dev.tearsmilk.miharu.duels;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Item;
import org.bukkit.entity.Projectile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Что игроки построили/сломали за раунд — чтобы откатить арену перед следующим. */
final class BuildTracker {

    private final Set<Location> placed = new HashSet<>();
    private final Map<Location, BlockState> originals = new LinkedHashMap<>();

    void place(Block block, BlockState replaced) {
        Location l = block.getLocation();
        originals.putIfAbsent(l, replaced);
        placed.add(l);
    }

    boolean isPlaced(Block block) {
        return placed.contains(block.getLocation());
    }

    void broke(Block block) {
        Location l = block.getLocation();
        originals.putIfAbsent(l, block.getState());
        placed.remove(l);
    }

    void revert(World world) {
        List<BlockState> states = new ArrayList<>(originals.values());
        for (int i = states.size() - 1; i >= 0; i--) {
            states.get(i).update(true, false);
        }
        originals.clear();
        placed.clear();
        for (Item item : world.getEntitiesByClass(Item.class)) {
            item.remove();
        }
        for (Projectile p : world.getEntitiesByClass(Projectile.class)) {
            p.remove();
        }
    }
}
