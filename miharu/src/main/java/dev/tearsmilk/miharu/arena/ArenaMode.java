package dev.tearsmilk.miharu.arena;

import java.util.List;

/**
 * Режим, под который можно настроить арену (bot-duel, clicktest, позже duel, sumo, skywars...).
 * Регистрируется модулями, которые этот режим запускают.
 */
public record ArenaMode(String id, String display, List<String> requiredSpawns) {
}
