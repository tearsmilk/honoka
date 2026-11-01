package dev.tearsmilk.miharu.arena.schematic;

import org.bukkit.Location;

import java.io.File;

/** Сохранение/вставка схематики. Реализации: ванильные структуры (.nbt) и WorldEdit (.schem). */
public interface SchematicEngine {

    String name();

    String extension();

    /** Сохраняет область [min..max] (включительно). */
    void save(File file, Location min, Location max) throws Exception;

    /** Вставляет так, чтобы нижний угол схематики оказался в origin. */
    void paste(File file, Location origin) throws Exception;
}
