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

    /** Подставляет {ключ} и парсит MiniMessage + &-коды. Курсив у предметов выключается отдельно. */
    public static Component mm(String raw, Map<String, ?> placeholders) {
        return MM.deserialize(legacy(apply(raw, placeholders)));
    }

    public static Component mm(String raw) {
        return MM.deserialize(legacy(raw == null ? "" : raw));
    }

    private static final java.util.regex.Pattern BUNGEE_HEX =
            java.util.regex.Pattern.compile("[&§][xX]((?:[&§][0-9a-fA-F]){6})");
    private static final java.util.regex.Pattern CODE =
            java.util.regex.Pattern.compile("[&§](#[0-9a-fA-F]{6}|[0-9a-fA-FklmnorKLMNOR])");

    /**
     * &-коды → MiniMessage: &#RRGGBB, &x&R&R&G&G&B&B, &0-&f, &l &o &n &m &k &r (и то же с §).
     * Как в обычных кодах, новый цвет сбрасывает жирный/курсив и т.п.
     */
    public static String legacy(String s) {
        if (s == null || (s.indexOf('&') < 0 && s.indexOf('§') < 0)) {
            return s;
        }
        java.util.regex.Matcher bm = BUNGEE_HEX.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (bm.find()) {
            String hex = bm.group(1).replaceAll("[&§]", "");
            bm.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("&#" + hex));
        }
        bm.appendTail(sb);
        java.util.regex.Matcher m = CODE.matcher(sb.toString());
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String c = m.group(1);
            String tag;
            if (c.startsWith("#")) {
                tag = "<reset><" + c.toUpperCase(java.util.Locale.ROOT) + ">";
            } else {
                tag = switch (Character.toLowerCase(c.charAt(0))) {
                    case '0' -> "<reset><black>";
                    case '1' -> "<reset><dark_blue>";
                    case '2' -> "<reset><dark_green>";
                    case '3' -> "<reset><dark_aqua>";
                    case '4' -> "<reset><dark_red>";
                    case '5' -> "<reset><dark_purple>";
                    case '6' -> "<reset><gold>";
                    case '7' -> "<reset><gray>";
                    case '8' -> "<reset><dark_gray>";
                    case '9' -> "<reset><blue>";
                    case 'a' -> "<reset><green>";
                    case 'b' -> "<reset><aqua>";
                    case 'c' -> "<reset><red>";
                    case 'd' -> "<reset><light_purple>";
                    case 'e' -> "<reset><yellow>";
                    case 'f' -> "<reset><white>";
                    case 'l' -> "<b>";
                    case 'o' -> "<i>";
                    case 'n' -> "<u>";
                    case 'm' -> "<st>";
                    case 'k' -> "<obf>";
                    default -> "<reset>";
                };
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(tag));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** То же, но с PlaceholderAPI для конкретного игрока (если PAPI установлен). */
    public static Component mm(Player player, String raw, Map<String, ?> placeholders) {
        String s = apply(raw, placeholders);
        s = dev.tearsmilk.miharu.Miharu.get().hooks().papi(player, s);
        return MM.deserialize(legacy(s));
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
