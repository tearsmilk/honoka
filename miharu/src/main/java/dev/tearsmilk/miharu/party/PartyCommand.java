package dev.tearsmilk.miharu.party;

import dev.tearsmilk.miharu.Miharu;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /party (/p): invite (i), accept (a), kick (k), leave (l), list, promote (p), delete (d), help (h), chat (c).
 * /pc &lt;сообщение&gt; — чат пати.
 */
final class PartyCommand implements BasicCommand {

    private final Miharu plugin;
    private final PartyModule module;

    PartyCommand(Miharu plugin, PartyModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    BasicCommand chatCommand() {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                if (source.getSender() instanceof Player p) {
                    chat(p, String.join(" ", args));
                } else {
                    plugin.messages().send(source.getSender(), "players-only");
                }
            }
        };
    }

    private static String sub(String a) {
        return switch (a.toLowerCase(Locale.ROOT)) {
            case "i", "inv", "invite", "add" -> "invite";
            case "a", "accept", "join" -> "accept";
            case "k", "kick" -> "kick";
            case "l", "leave", "quit" -> "leave";
            case "list", "ls", "info" -> "list";
            case "p", "promote", "leader" -> "promote";
            case "d", "delete", "disband" -> "delete";
            case "c", "chat" -> "chat";
            case "h", "help", "?" -> "help";
            default -> "";
        };
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player p)) {
            plugin.messages().send(source.getSender(), "players-only");
            return;
        }
        String cmd = args.length == 0 ? "help" : sub(args[0]);
        String arg = args.length > 1 ? args[1] : null;
        switch (cmd) {
            case "invite" -> invite(p, arg);
            case "accept" -> accept(p, arg);
            case "kick" -> kick(p, arg);
            case "leave" -> leave(p);
            case "list" -> list(p);
            case "promote" -> promote(p, arg);
            case "delete" -> delete(p);
            case "chat" -> chat(p, args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "");
            case "help" -> plugin.messages().sendList(p, "party.help");
            default -> {
                // /p <ник> — то же, что invite
                if (Bukkit.getPlayerExact(args[0]) != null) {
                    invite(p, args[0]);
                } else {
                    plugin.messages().sendList(p, "party.help");
                }
            }
        }
    }

    private Party leaderParty(Player p) {
        Party pt = module.party(p);
        if (pt == null) {
            plugin.messages().send(p, "party.not-in-party");
            return null;
        }
        if (!pt.leader().equals(p.getUniqueId())) {
            plugin.messages().send(p, "party.not-leader");
            return null;
        }
        return pt;
    }

    private void invite(Player p, String name) {
        if (name == null) {
            plugin.messages().send(p, "party.usage-invite");
            return;
        }
        Player to = Bukkit.getPlayerExact(name);
        if (to == null) {
            plugin.messages().send(p, "player-not-found", "player", name);
            return;
        }
        if (to == p) {
            plugin.messages().send(p, "party.self");
            return;
        }
        Party pt = module.party(p);
        if (pt != null && !pt.leader().equals(p.getUniqueId())) {
            plugin.messages().send(p, "party.not-leader");
            return;
        }
        if (module.party(to) != null) {
            plugin.messages().send(p, "party.already-in-party", "player", to.getName());
            return;
        }
        if (pt != null && pt.size() >= module.maxSize()) {
            plugin.messages().send(p, "party.full", "max", module.maxSize());
            return;
        }
        if (pt == null) {
            pt = module.create(p); // первое приглашение сразу создаёт пати
            plugin.messages().send(p, "party.created");
        }
        if (module.hasInvite(pt, to)) {
            plugin.messages().send(p, "party.already-invited", "player", to.getName());
            return;
        }
        module.invite(pt, p, to);
        module.send(pt, "party.invited", "player", to.getName(), "from", p.getName());
        plugin.messages().send(to, "party.invite-received", "player", p.getName(), "seconds", module.config().getInt("invite-seconds", 60));
        to.playSound(to.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
    }

    private void accept(Player p, String from) {
        if (module.party(p) != null) {
            plugin.messages().send(p, "party.leave-first");
            return;
        }
        PartyModule.Invite inv = module.invite(p, from);
        Party pt = inv == null ? null : module.byId(inv.party());
        if (pt == null) {
            plugin.messages().send(p, "party.no-invite");
            return;
        }
        if (pt.size() >= module.maxSize()) {
            plugin.messages().send(p, "party.full", "max", module.maxSize());
            return;
        }
        module.join(pt, p);
        module.send(pt, "party.joined", "player", p.getName(), "size", pt.size(), "max", module.maxSize());
    }

    private UUID memberByName(Party pt, String name) {
        for (UUID id : pt.members()) {
            if (PartyModule.name(id).equalsIgnoreCase(name)) {
                return id;
            }
        }
        return null;
    }

    private void kick(Player p, String name) {
        Party pt = leaderParty(p);
        if (pt == null) {
            return;
        }
        if (name == null) {
            plugin.messages().send(p, "party.usage-kick");
            return;
        }
        UUID id = memberByName(pt, name);
        if (id == null || id.equals(p.getUniqueId())) {
            plugin.messages().send(p, "party.not-member", "player", name);
            return;
        }
        module.send(pt, "party.kicked", "player", PartyModule.name(id));
        Player t = Bukkit.getPlayer(id);
        module.remove(pt, id);
        if (t != null) {
            plugin.messages().send(t, "party.you-kicked", "player", p.getName());
        }
    }

    private void leave(Player p) {
        Party pt = module.party(p);
        if (pt == null) {
            plugin.messages().send(p, "party.not-in-party");
            return;
        }
        module.send(pt, "party.left", "player", p.getName());
        module.remove(pt, p.getUniqueId());
    }

    private void list(Player p) {
        Party pt = module.party(p);
        if (pt == null) {
            plugin.messages().send(p, "party.not-in-party");
            return;
        }
        plugin.messages().send(p, "party.list-header", "size", pt.size(), "max", module.maxSize());
        for (UUID id : pt.members()) {
            Player m = Bukkit.getPlayer(id);
            plugin.messages().send(p, id.equals(pt.leader()) ? "party.list-leader" : "party.list-member",
                    "player", PartyModule.name(id), "status", plugin.messages().raw(m != null ? "party.online" : "party.offline"));
        }
    }

    private void promote(Player p, String name) {
        Party pt = leaderParty(p);
        if (pt == null) {
            return;
        }
        if (name == null) {
            plugin.messages().send(p, "party.usage-promote");
            return;
        }
        UUID id = memberByName(pt, name);
        if (id == null || id.equals(p.getUniqueId())) {
            plugin.messages().send(p, "party.not-member", "player", name);
            return;
        }
        pt.leader(id);
        module.send(pt, "party.new-leader", "player", PartyModule.name(id));
    }

    private void delete(Player p) {
        Party pt = leaderParty(p);
        if (pt == null) {
            return;
        }
        module.send(pt, "party.deleted", "player", p.getName());
        module.disband(pt);
    }

    private void chat(Player p, String msg) {
        Party pt = module.party(p);
        if (pt == null) {
            plugin.messages().send(p, "party.not-in-party");
            return;
        }
        if (msg.isBlank()) {
            plugin.messages().send(p, "party.usage-chat");
            return;
        }
        // текст игрока — без MiniMessage-тегов
        String safe = msg.replace("<", "‹").replace(">", "›").replace("&", "&\u200B").replace("§", "");
        module.send(pt, "party.chat", "player", p.getName(), "message", safe);
        plugin.getLogger().info("[Party " + PartyModule.name(pt.leader()) + "] " + p.getName() + ": " + msg);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return List.of("invite", "accept", "kick", "leave", "list", "promote", "delete", "chat", "help");
        }
        if (args.length == 2) {
            String s = sub(args[0]);
            List<String> names = new ArrayList<>();
            if (s.equals("invite")) {
                Bukkit.getOnlinePlayers().forEach(pl -> names.add(pl.getName()));
            } else if ((s.equals("kick") || s.equals("promote")) && source.getSender() instanceof Player p && module.party(p) != null) {
                module.party(p).members().forEach(id -> names.add(PartyModule.name(id)));
            }
            return names;
        }
        return List.of();
    }
}
