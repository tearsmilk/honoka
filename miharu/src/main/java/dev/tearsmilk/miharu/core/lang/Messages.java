package dev.tearsmilk.miharu.core.lang;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.core.config.Configs;
import dev.tearsmilk.miharu.core.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Все тексты плагина — messages.yml (MiniMessage, плейсхолдеры {ключ}). */
public final class Messages {

    private final Miharu plugin;
    private YamlConfiguration cfg;

    public Messages(Miharu plugin) {
        this.plugin = plugin;
    }

    public void load() {
        cfg = Configs.load(plugin, "messages.yml");
    }

    public String raw(String key) {
        String value = cfg.getString(key);
        return value != null ? value : "<red>" + key;
    }

    public List<String> list(String key) {
        return cfg.getStringList(key);
    }

    public Component get(String key, Object... kv) {
        return Text.mm(raw(key), map(kv));
    }

    public void send(CommandSender to, String key, Object... kv) {
        String raw = cfg.getString(key);
        if (raw == null || raw.isEmpty()) {
            return;
        }
        Map<String, Object> map = map(kv);
        map.put("prefix", cfg.getString("prefix", ""));
        to.sendMessage(Text.mm(raw, map));
    }

    public void sendList(CommandSender to, String key, Object... kv) {
        Map<String, Object> map = map(kv);
        map.put("prefix", cfg.getString("prefix", ""));
        for (String line : cfg.getStringList(key)) {
            to.sendMessage(Text.mm(line, map));
        }
    }

    public void title(Player p, String key, Object... kv) {
        String t = cfg.getString(key + ".title", "");
        String s = cfg.getString(key + ".subtitle", "");
        Map<String, Object> map = map(kv);
        p.showTitle(Title.title(Text.mm(t, map), Text.mm(s, map),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(cfg.getLong(key + ".stay", 1500)), Duration.ofMillis(300))));
    }

    public void actionBar(Player p, String key, Object... kv) {
        String raw = cfg.getString(key);
        if (raw != null && !raw.isEmpty()) {
            p.sendActionBar(Text.mm(raw, map(kv)));
        }
    }

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
