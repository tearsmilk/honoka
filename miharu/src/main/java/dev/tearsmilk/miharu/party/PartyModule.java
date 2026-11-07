package dev.tearsmilk.miharu.party;

import dev.tearsmilk.miharu.Miharu;
import dev.tearsmilk.miharu.core.module.MiharuModule;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Пати: /party (/p) invite, accept, kick, leave, list, promote, delete, help, chat; /pc — чат пати.
 * Настройки — modules/party/config.yml.
 */
public final class PartyModule extends MiharuModule implements Listener {

    /** Приглашение: в какую пати и до какого момента действует. */
    record Invite(UUID party, UUID from, long expires) {
    }

    private final Map<UUID, Party> byPlayer = new HashMap<>();
    private final Map<UUID, Party> byId = new HashMap<>();
    /** приглашённый → (id пати → приглашение) */
    private final Map<UUID, Map<UUID, Invite>> invites = new HashMap<>();

    public PartyModule(Miharu plugin) {
        super(plugin, "party");
    }

    @Override
    protected void onEnable() {
        config();
        listen(this);
        PartyCommand cmd = new PartyCommand(plugin, this);
        plugin.registerCommand("party", "Пати: /party help", List.of("p"), cmd);
        plugin.registerCommand("pc", "Чат пати", List.of("partychat"), cmd.chatCommand());
        plugin.hooks().placeholder("party_size", (player, rest) -> {
            Party pt = player == null ? null : byPlayer.get(player.getUniqueId());
            return pt == null ? "0" : String.valueOf(pt.size());
        });
        plugin.hooks().placeholder("party_leader", (player, rest) -> {
            Party pt = player == null ? null : byPlayer.get(player.getUniqueId());
            return pt == null ? "" : name(pt.leader());
        });
    }

    @Override
    protected void onDisable() {
        byPlayer.clear();
        byId.clear();
        invites.clear();
    }

    public Party party(Player p) {
        return byPlayer.get(p.getUniqueId());
    }

    public int maxSize() {
        return Math.max(2, config().getInt("max-size", 5));
    }

    static String name(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() == null ? id.toString().substring(0, 8) : op.getName();
    }

    void send(Party pt, String key, Object... kv) {
        for (UUID id : pt.members()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                plugin.messages().send(p, key, kv);
            }
        }
    }

    Party create(Player leader) {
        Party pt = new Party(leader.getUniqueId());
        byPlayer.put(leader.getUniqueId(), pt);
        byId.put(pt.id(), pt);
        return pt;
    }

    void invite(Party pt, Player from, Player to) {
        long exp = System.currentTimeMillis() + Math.max(10, config().getInt("invite-seconds", 60)) * 1000L;
        invites.computeIfAbsent(to.getUniqueId(), k -> new HashMap<>()).put(pt.id(), new Invite(pt.id(), from.getUniqueId(), exp));
    }

    /** Действующее приглашение (от конкретного игрока или последнее). */
    Invite invite(Player to, String fromName) {
        Map<UUID, Invite> map = invites.get(to.getUniqueId());
        if (map == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        map.values().removeIf(i -> i.expires() < now || !byId.containsKey(i.party()));
        Invite best = null;
        for (Invite i : map.values()) {
            Party pt = byId.get(i.party());
            boolean match = fromName == null || name(i.from()).equalsIgnoreCase(fromName) || name(pt.leader()).equalsIgnoreCase(fromName);
            if (match && (best == null || i.expires() > best.expires())) {
                best = i;
            }
        }
        return best;
    }

    boolean hasInvite(Party pt, Player to) {
        Map<UUID, Invite> map = invites.get(to.getUniqueId());
        Invite i = map == null ? null : map.get(pt.id());
        return i != null && i.expires() >= System.currentTimeMillis();
    }

    Party byId(UUID id) {
        return byId.get(id);
    }

    void join(Party pt, Player p) {
        Map<UUID, Invite> map = invites.remove(p.getUniqueId());
        pt.add(p.getUniqueId());
        byPlayer.put(p.getUniqueId(), pt);
    }

    /** Убрать игрока; лидер ушёл — лидером становится следующий; никого не осталось — пати удалена. */
    void remove(Party pt, UUID id) {
        pt.remove(id);
        byPlayer.remove(id);
        if (pt.size() == 0) {
            disband(pt);
            return;
        }
        if (id.equals(pt.leader())) {
            pt.leader(pt.members().getFirst());
            send(pt, "party.new-leader", "player", name(pt.leader()));
        }
        if (pt.size() == 1 && config().getBoolean("disband-when-alone", true)) {
            send(pt, "party.disbanded-alone");
            disband(pt);
        }
    }

    void disband(Party pt) {
        for (UUID id : pt.members()) {
            byPlayer.remove(id);
        }
        byId.remove(pt.id());
        invites.values().forEach(m -> m.remove(pt.id()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Party pt = byPlayer.get(e.getPlayer().getUniqueId());
        invites.remove(e.getPlayer().getUniqueId());
        if (pt != null) {
            send(pt, "party.quit", "player", e.getPlayer().getName());
            remove(pt, e.getPlayer().getUniqueId());
        }
    }
}
