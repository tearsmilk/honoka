package dev.tearsmilk.miharu.party;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Пати: лидер + участники (порядок вступления — для передачи лидерства). */
public final class Party {

    private final UUID id = UUID.randomUUID();
    private UUID leader;
    private final Set<UUID> members = new LinkedHashSet<>();

    Party(UUID leader) {
        this.leader = leader;
        members.add(leader);
    }

    public UUID id() {
        return id;
    }

    public UUID leader() {
        return leader;
    }

    void leader(UUID id) {
        this.leader = id;
    }

    public List<UUID> members() {
        return new ArrayList<>(members);
    }

    public int size() {
        return members.size();
    }

    public boolean has(UUID id) {
        return members.contains(id);
    }

    void add(UUID id) {
        members.add(id);
    }

    void remove(UUID id) {
        members.remove(id);
    }
}
