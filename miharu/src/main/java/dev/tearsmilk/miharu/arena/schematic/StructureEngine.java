package dev.tearsmilk.miharu.arena.schematic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.util.Random;

/** Встроенный движок на ванильных структурах (.nbt) — работает без WorldEdit. */
public final class StructureEngine implements SchematicEngine {

    @Override
    public String name() {
        return "structure";
    }

    @Override
    public String extension() {
        return ".nbt";
    }

    @Override
    public void save(File file, Location min, Location max) throws Exception {
        StructureManager sm = Bukkit.getStructureManager();
        Structure s = sm.createStructure();
        BlockVector size = new BlockVector(
                max.getBlockX() - min.getBlockX() + 1,
                max.getBlockY() - min.getBlockY() + 1,
                max.getBlockZ() - min.getBlockZ() + 1);
        s.fill(min.toBlockLocation(), size, false);
        sm.saveStructure(file, s);
    }

    @Override
    public void paste(File file, Location origin) throws Exception {
        Structure s = Bukkit.getStructureManager().loadStructure(file);
        s.place(origin.toBlockLocation(), false, StructureRotation.NONE, Mirror.NONE, 0, 1f, new Random());
    }
}
