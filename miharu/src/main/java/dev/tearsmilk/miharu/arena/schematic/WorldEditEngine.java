package dev.tearsmilk.miharu.arena.schematic;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.Location;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Движок WorldEdit (.schem, Sponge v3). Класс грузится только если WorldEdit/FAWE установлен. */
public final class WorldEditEngine implements SchematicEngine {

    @Override
    public String name() {
        return "worldedit";
    }

    @Override
    public String extension() {
        return ".schem";
    }

    @Override
    public void save(File file, Location min, Location max) throws Exception {
        com.sk89q.worldedit.world.World world = BukkitAdapter.adapt(min.getWorld());
        BlockVector3 a = BlockVector3.at(min.getBlockX(), min.getBlockY(), min.getBlockZ());
        BlockVector3 b = BlockVector3.at(max.getBlockX(), max.getBlockY(), max.getBlockZ());
        CuboidRegion region = new CuboidRegion(world, a, b);
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
        clipboard.setOrigin(region.getMinimumPoint());
        try (EditSession session = WorldEdit.getInstance().newEditSession(world)) {
            ForwardExtentCopy copy = new ForwardExtentCopy(session, region, clipboard, region.getMinimumPoint());
            copy.setCopyingEntities(false);
            Operations.complete(copy);
        }
        try (ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(new FileOutputStream(file))) {
            writer.write(clipboard);
        }
    }

    @Override
    public void paste(File file, Location origin) throws Exception {
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) {
            throw new IllegalArgumentException("Неизвестный формат схематики: " + file.getName());
        }
        Clipboard clipboard;
        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
            clipboard = reader.read();
        }
        // ставим так, чтобы нижний угол лёг в origin, где бы ни был origin внутри схематики
        BlockVector3 offset = clipboard.getOrigin().subtract(clipboard.getMinimumPoint());
        BlockVector3 to = BlockVector3.at(origin.getBlockX(), origin.getBlockY(), origin.getBlockZ()).add(offset);
        try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(origin.getWorld()))) {
            Operation op = new ClipboardHolder(clipboard).createPaste(session).to(to).ignoreAirBlocks(true).copyEntities(false).build();
            Operations.complete(op);
        }
    }
}
