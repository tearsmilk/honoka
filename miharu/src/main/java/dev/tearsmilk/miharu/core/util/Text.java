package dev.tearsmilk.miharu.core.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public final class Text {

    public static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    /** Подставляет {ключ} и парсит MiniMessage. Курсив у предметов выключается отдельно. */
    public static Component mm(String raw, Map<String, ?> placeholders) {
        return MM.deserialize(apply(raw, placeholders));
    }

    public static Component mm(String raw) {
        return MM.deserialize(raw == null ? "" : raw);
    }

    /** То же, но с PlaceholderAPI для конкретного игрока (если PAPI установлен). */
    public static Component mm(Player player, String raw, Map<String, ?> placeholders) {
        String s = apply(raw, placeholders);
        s = dev.tearsmilk.miharu.Miharu.get().hooks().papi(player, s);
        return MM.deserialize(s);
    }

    public static Component item(String raw, Map<String, ?> placeholders) {
        return mm(raw, placeholders).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> lore(List<String> lines, Map<String, ?> placeholders) {
        return lines.stream().map(l -> item(l, placeholders)).toList();
    }

    public static String apply(String raw, Map<String, ?> placeholders) {
        if (raw == null) {
            return "";
        }
        if (placeholders == null || placeholders.isEmpty() || raw.indexOf('{') < 0) {
            return raw;
        }
        String s = raw;
        for (Map.Entry<String, ?> e : placeholders.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return s;
    }

    public static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    public static String time(long seconds) {
        long m = Math.max(0, seconds) / 60;
        long s = Math.max(0, seconds) % 60;
        return String.format("%02d:%02d", m, s);
    }

    public static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) {
            return String.valueOf((long) Math.rint(v));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
