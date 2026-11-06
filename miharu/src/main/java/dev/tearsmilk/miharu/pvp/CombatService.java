package dev.tearsmilk.miharu.pvp;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кто и на какой версии боя сейчас дерётся.
 * Приоритет: принудительно (игры, /pvp) → мир (worlds:) → default-version.
 */
public final class CombatService {

    private static final double VANILLA_ATTACK_SPEED = 4.0;

    private final Map<CombatVersion, CombatProfile> profiles = new EnumMap<>(CombatVersion.class);
    private final Map<UUID, CombatProfile> forced = new ConcurrentHashMap<>();
    private final Map<CombatVersion, ConfigurationSection> sections = new EnumMap<>(CombatVersion.class);
    private final Map<String, CombatVersion> worlds = new HashMap<>();
    private @Nullable CombatVersion defaultVersion;

    public void load(YamlConfiguration cfg) {
        profiles.clear();
        for (CombatVersion v : CombatVersion.values()) {
            ConfigurationSection s = dev.tearsmilk.miharu.core.config.Configs.child(cfg.getConfigurationSection("versions"), v.id());
            if (s == null) {
                s = new org.bukkit.configuration.MemoryConfiguration();
            }
            profiles.put(v, new CombatProfile(v, s));
            sections.put(v, s);
        }
        worlds.clear();
        ConfigurationSection w = cfg.getConfigurationSection("worlds");
        if (w != null) {
            for (String world : w.getKeys(false)) {
                CombatVersion v = CombatVersion.parse(String.valueOf(dev.tearsmilk.miharu.core.config.Configs.value(w, world)));
                if (v != null) {
                    worlds.put(world, v);
                }
            }
        }
        defaultVersion = CombatVersion.parse(cfg.getString("default-version", "none"));
        Bukkit.getOnlinePlayers().forEach(this::refresh);
    }

    public CombatProfile profile(CombatVersion version) {
        return profiles.get(version);
    }

    public @Nullable CombatVersion version(Player p) {
        CombatProfile f = forced.get(p.getUniqueId());
        if (f != null) {
            return f.version;
        }
        CombatVersion v = worlds.get(p.getWorld().getName());
        return v != null ? v : defaultVersion;
    }

    public @Nullable CombatProfile profile(Player p) {
        CombatProfile f = forced.get(p.getUniqueId());
        if (f != null) {
            return f;
        }
        CombatVersion v = version(p);
        return v == null ? null : profiles.get(v);
    }

    /** Принудительно ставит версию (null — снять). Используется играми и /pvp. */
    public void force(Player p, @Nullable CombatVersion version) {
        force(p, version == null ? null : profiles.get(version));
    }

    /** Принудительно ставит готовый профиль (например, с настройками кита). */
    public void force(Player p, @Nullable CombatProfile profile) {
        if (profile == null) {
            forced.remove(p.getUniqueId());
        } else {
            forced.put(p.getUniqueId(), profile);
        }
        refresh(p);
    }

    /**
     * Профиль версии с переопределениями (секция combat: в ките).
     * Ключи те же, что в modules/pvp/config.yml → versions.&lt;версия&gt;.
     */
    public CombatProfile derive(CombatVersion version, @Nullable ConfigurationSection overrides) {
        if (overrides == null || overrides.getKeys(true).isEmpty()) {
            return profiles.get(version);
        }
        CombatProfile base0 = profiles.get(version);
        if (base0 != null && base0.vanilla) {
            return base0; // ванильная версия: переопределения боя из китов не применяются
        }
        org.bukkit.configuration.MemoryConfiguration merged = new org.bukkit.configuration.MemoryConfiguration();
        ConfigurationSection base = sections.get(version);
        if (base != null) {
            for (String key : base.getKeys(true)) {
                if (!base.isConfigurationSection(key)) {
                    merged.set(key, base.get(key));
                }
            }
        }
        for (String key : overrides.getKeys(true)) {
            if (!overrides.isConfigurationSection(key)) {
                merged.set(key, overrides.get(key));
            }
        }
        return new CombatProfile(version, merged);
    }

    public void forget(Player p) {
        forced.remove(p.getUniqueId());
    }

    /** Применяет атрибуты текущей версии (скорость атаки, неуязвимость после удара). */
    public void refresh(Player p) {
        CombatProfile profile = profile(p);
        AttributeInstance speed = p.getAttribute(Attribute.ATTACK_SPEED);
        if (profile == null) {
            if (speed != null) {
                speed.setBaseValue(VANILLA_ATTACK_SPEED);
            }
            p.setMaximumNoDamageTicks(20);
            return;
        }
        if (speed != null) {
            speed.setBaseValue(profile.attackSpeed);
        }
        p.setMaximumNoDamageTicks(profile.maxNoDamageTicks);
        prepareInventory(p, profile);
    }

    /** Сброс к ванили (при выключении плагина). */
    public void reset(Player p) {
        AttributeInstance speed = p.getAttribute(Attribute.ATTACK_SPEED);
        if (speed != null) {
            speed.setBaseValue(VANILLA_ATTACK_SPEED);
        }
        p.setMaximumNoDamageTicks(20);
    }

    /** Мечам в 1.8 добавляется анимация блока (ПКМ) — как блок мечом в 1.8. */
    public void prepareInventory(Player p, CombatProfile profile) {
        if (!profile.swordBlocking) {
            return;
        }
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && CombatMath.isSword(item.getType()) && !item.hasData(DataComponentTypes.CONSUMABLE)) {
                inv.setItem(i, prepare(item, profile));
            }
        }
    }

    public ItemStack prepare(ItemStack item, CombatProfile profile) {
        if (item == null || !profile.swordBlocking || !CombatMath.isSword(item.getType())) {
            return item;
        }
        item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                .consumeSeconds(3600f)
                .animation(ItemUseAnimation.BLOCK)
                .sound(Key.key("minecraft", "intentionally_empty"))
                .hasConsumeParticles(false)
                .build());
        return item;
    }

    /** Блокирует ли игрок мечом прямо сейчас. */
    public boolean isSwordBlocking(Player p, CombatProfile profile) {
        if (!profile.swordBlocking || !p.isHandRaised()) {
            return false;
        }
        ItemStack active = p.getActiveItem();
        return CombatMath.isSword(active.getType());
    }
}
