package dev.tearsmilk.miharu.core.command;

import dev.tearsmilk.miharu.Miharu;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Простой роутер подкоманд поверх Paper BasicCommand. */
public abstract class SubCommands implements BasicCommand {

    @FunctionalInterface
    public interface Exec {
        void run(CommandSender sender, String[] args);
    }

    @FunctionalInterface
    public interface Complete {
        List<String> complete(CommandSender sender, String[] args);
    }

    public record Sub(String name, @Nullable String permission, boolean playerOnly, Exec exec, @Nullable Complete complete) {
    }

    protected final Miharu plugin;
    private final Map<String, Sub> subs = new LinkedHashMap<>();
    private final @Nullable String permission;

    protected SubCommands(Miharu plugin, @Nullable String permission) {
        this.plugin = plugin;
        this.permission = permission;
    }

    protected void sub(String name, @Nullable String perm, boolean playerOnly, Exec exec, @Nullable Complete complete) {
        subs.put(name.toLowerCase(Locale.ROOT), new Sub(name, perm, playerOnly, exec, complete));
    }

    protected void sub(String name, @Nullable String perm, boolean playerOnly, Exec exec) {
        sub(name, perm, playerOnly, exec, null);
    }

    /** Вызов без аргументов. */
    protected abstract void root(CommandSender sender);

    /** Неизвестная подкоманда (по умолчанию — помощь). */
    protected void unknown(CommandSender sender, String[] args) {
        root(sender);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            root(sender);
            return;
        }
        Sub sub = subs.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null) {
            unknown(sender, args);
            return;
        }
        if (sub.permission() != null && !sender.hasPermission(sub.permission())) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        if (sub.playerOnly() && !(sender instanceof Player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        try {
            sub.exec().run(sender, Arrays.copyOfRange(args, 1, args.length));
        } catch (NumberFormatException e) {
            plugin.messages().send(sender, "bad-number");
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (Sub sub : subs.values()) {
                if ((sub.permission() == null || sender.hasPermission(sub.permission())) && sub.name().startsWith(prefix)) {
                    out.add(sub.name());
                }
            }
            return out;
        }
        Sub sub = subs.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null || sub.complete() == null || (sub.permission() != null && !sender.hasPermission(sub.permission()))) {
            return List.of();
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        List<String> options = sub.complete().complete(sender, rest);
        String last = rest.length == 0 ? "" : rest[rest.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }

    @Override
    public @Nullable String permission() {
        return permission;
    }

    protected static String arg(String[] args, int i) {
        return i < args.length ? args[i] : null;
    }
}
