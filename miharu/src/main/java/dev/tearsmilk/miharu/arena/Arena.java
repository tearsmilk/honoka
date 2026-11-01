package dev.tearsmilk.miharu.arena;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Арена = схематика + точки спавна относительно её нижнего угла.
 * Файл: modules/arena/arenas/&lt;id&gt;.yml, схематика: modules/arena/schematics/.
 */
public final class Arena {

    public record Spawn(double x, double y, double z, float yaw, float pitch) {
        public Location at(World world, Location origin) {
            return new Location(world, origin.getX() + x, origin.getY() + y, origin.getZ() + z, yaw, pitch);
        }

        public static Spawn relative(Location abs, Vector min) {
            return new Spawn(abs.getX() - min.getX(), abs.getY() - min.getY(), abs.getZ() - min.getZ(), abs.getYaw(), abs.getPitch());
        }
    }

    private final String id;
    private String display;
    private boolean enabled = true;
    private final Set<String> modes = new LinkedHashSet<>();
    private String schematic;
    private Vector size = new Vector();
    private final Map<String, Spawn> spawns = new LinkedHashMap<>();
    private double voidY = -5;
    private Material icon = Material.GRASS_BLOCK;

    public Arena(String id) {
        this.id = id;
        this.display = id;
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public void display(String display) {
        this.display = display;
    }

    public boolean enabled() {
        return enabled;
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<String> modes() {
        return modes;
    }

    public boolean supports(String mode) {
        return modes.contains(mode);
    }

    public String schematic() {
        return schematic;
    }

    public void schematic(String schematic) {
        this.schematic = schematic;
    }

    public Vector size() {
        return size;
    }

    public void size(Vector size) {
        this.size = size;
    }

    public Map<String, Spawn> spawns() {
        return spawns;
    }

    public Spawn spawn(String key) {
        return spawns.get(key);
    }

    /** Высота «пустоты» относительно низа схематики. Ниже — падение. */
    public double voidY() {
        return voidY;
    }

    public void voidY(double voidY) {
        this.voidY = voidY;
    }

    public Material icon() {
        return icon;
    }

    public void icon(Material icon) {
        this.icon = icon;
    }

    public void save(File file) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.set("display", display);
        y.set("enabled", enabled);
        y.set("modes", modes.stream().toList());
        y.set("schematic", schematic);
        y.set("size.x", size.getBlockX());
        y.set("size.y", size.getBlockY());
        y.set("size.z", size.getBlockZ());
        y.set("void-y", voidY);
        y.set("icon", icon.name());
        for (Map.Entry<String, Spawn> e : spawns.entrySet()) {
            String p = "spawns." + e.getKey() + ".";
            Spawn s = e.getValue();
            y.set(p + "x", s.x());
            y.set(p + "y", s.y());
            y.set(p + "z", s.z());
            y.set(p + "yaw", (double) s.yaw());
            y.set(p + "pitch", (double) s.pitch());
        }
        y.save(file);
    }

    public static Arena load(String id, File file) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        Arena a = new Arena(id);
        a.display = y.getString("display", id);
        a.enabled = y.getBoolean("enabled", true);
        a.modes.addAll(y.getStringList("modes"));
        a.schematic = y.getString("schematic");
        a.size = new Vector(y.getInt("size.x"), y.getInt("size.y"), y.getInt("size.z"));
        a.voidY = y.getDouble("void-y", -5);
        Material icon = Material.matchMaterial(y.getString("icon", "GRASS_BLOCK"));
        a.icon = icon == null ? Material.GRASS_BLOCK : icon;
        ConfigurationSection sp = y.getConfigurationSection("spawns");
        if (sp != null) {
            for (String key : sp.getKeys(false)) {
                ConfigurationSection s = sp.getConfigurationSection(key);
                if (s != null) {
                    a.spawns.put(key, new Spawn(s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                            (float) s.getDouble("yaw"), (float) s.getDouble("pitch")));
                }
            }
        }
        return a;
    }
}
