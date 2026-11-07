package dev.tearsmilk.miharu.game;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Состояние игрока до входа в игру. Пишется на диск (modules/game/snapshots/&lt;uuid&gt;.yml),
 * чтобы вещи не пропали даже после краша сервера.
 */
public final class PlayerSnapshot {

    private ItemStack[] contents;
    private int heldSlot;
    private double health;
    private int food;
    private float saturation;
    private float exhaustion;
    private int level;
    private float exp;
    private GameMode gameMode;
    private Collection<PotionEffect> effects;
    private String world;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private boolean allowFlight;
    private boolean flying;
    private float walkSpeed;
    private transient org.bukkit.scoreboard.Scoreboard scoreboard;

    public static PlayerSnapshot capture(Player p) {
        PlayerSnapshot s = new PlayerSnapshot();
        s.contents = cloneAll(p.getInventory().getContents());
        s.heldSlot = p.getInventory().getHeldItemSlot();
        s.health = p.getHealth();
        s.food = p.getFoodLevel();
        s.saturation = p.getSaturation();
        s.exhaustion = p.getExhaustion();
        s.level = p.getLevel();
        s.exp = p.getExp();
        s.gameMode = p.getGameMode();
        s.effects = new ArrayList<>(p.getActivePotionEffects());
        Location l = p.getLocation();
        s.world = l.getWorld().getName();
        s.x = l.getX();
        s.y = l.getY();
        s.z = l.getZ();
        s.yaw = l.getYaw();
        s.pitch = l.getPitch();
        s.allowFlight = p.getAllowFlight();
        s.flying = p.isFlying();
        s.walkSpeed = p.getWalkSpeed();
        s.scoreboard = p.getScoreboard();
        return s;
    }

    private static ItemStack[] cloneAll(ItemStack[] items) {
        ItemStack[] out = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            out[i] = items[i] == null ? null : items[i].clone();
        }
        return out;
    }

    public Location location() {
        World w = Bukkit.getWorld(world);
        if (w == null) {
            return null;
        }
        return new Location(w, x, y, z, yaw, pitch);
    }

    /** Возвращает всё, кроме позиции (её выставляет GameManager). */
    public void restore(Player p) {
        p.getInventory().clear();
        p.getInventory().setContents(cloneAll(contents));
        p.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, heldSlot)));
        p.setGameMode(gameMode == null ? GameMode.SURVIVAL : gameMode);
        for (PotionEffect e : p.getActivePotionEffects()) {
            p.removePotionEffect(e.getType());
        }
        if (effects != null) {
            p.addPotionEffects(effects);
        }
        var max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        p.setHealth(Math.max(0.5, Math.min(max == null ? 20 : max.getValue(), health)));
        p.setFoodLevel(food);
        p.setSaturation(saturation);
        p.setExhaustion(exhaustion);
        p.setLevel(level);
        p.setExp(exp);
        p.setAllowFlight(allowFlight);
        p.setFlying(allowFlight && flying);
        p.setWalkSpeed(walkSpeed <= 0 ? 0.2f : walkSpeed);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setVelocity(new org.bukkit.util.Vector());
        if (scoreboard != null) {
            p.setScoreboard(scoreboard);
        } else {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        p.updateInventory();
    }

    public void save(File file) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack i : contents) {
            list.add(i);
        }
        y.set("contents", list);
        y.set("held", heldSlot);
        y.set("health", health);
        y.set("food", food);
        y.set("saturation", (double) saturation);
        y.set("exhaustion", (double) exhaustion);
        y.set("level", level);
        y.set("exp", (double) exp);
        y.set("gamemode", gameMode.name());
        y.set("effects", new ArrayList<>(effects));
        y.set("world", world);
        y.set("x", x);
        y.set("y", this.y);
        y.set("z", z);
        y.set("yaw", (double) yaw);
        y.set("pitch", (double) pitch);
        y.set("allow-flight", allowFlight);
        y.set("flying", flying);
        y.set("walk-speed", (double) walkSpeed);
        file.getParentFile().mkdirs();
        y.save(file);
    }

    @SuppressWarnings("unchecked")
    public static PlayerSnapshot load(File file) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        PlayerSnapshot s = new PlayerSnapshot();
        List<ItemStack> list = (List<ItemStack>) y.getList("contents", new ArrayList<>());
        s.contents = list.toArray(new ItemStack[0]);
        s.heldSlot = y.getInt("held");
        s.health = y.getDouble("health", 20);
        s.food = y.getInt("food", 20);
        s.saturation = (float) y.getDouble("saturation", 5);
        s.exhaustion = (float) y.getDouble("exhaustion", 0);
        s.level = y.getInt("level");
        s.exp = (float) y.getDouble("exp");
        try {
            s.gameMode = GameMode.valueOf(y.getString("gamemode", "SURVIVAL"));
        } catch (IllegalArgumentException e) {
            s.gameMode = GameMode.SURVIVAL;
        }
        s.effects = (List<PotionEffect>) y.getList("effects", new ArrayList<>());
        s.world = y.getString("world", "world");
        s.x = y.getDouble("x");
        s.y = y.getDouble("y");
        s.z = y.getDouble("z");
        s.yaw = (float) y.getDouble("yaw");
        s.pitch = (float) y.getDouble("pitch");
        s.allowFlight = y.getBoolean("allow-flight");
        s.flying = y.getBoolean("flying");
        s.walkSpeed = (float) y.getDouble("walk-speed", 0.2);
        return s;
    }
}
