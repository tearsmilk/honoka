package dev.tearsmilk.miharu.core.kit;

import dev.tearsmilk.miharu.pvp.CombatProfile;
import dev.tearsmilk.miharu.pvp.CombatVersion;

import java.util.List;

/**
 * Полное описание кита дуэли: вещи + правила + версия и настройки боя + арены + бот.
 *
 * @param arenaMode режим арены (duel, sumo, bridge, mlg)
 * @param arenas    белый список арен (пусто — любая арена режима)
 */
public record KitSpec(
        String id,
        String display,
        CombatVersion version,
        CombatProfile profile,
        Kit kit,
        KitRules rules,
        String arenaMode,
        List<String> arenas,
        Bot bot
) {

    /** Как бот играет этим китом. */
    public record Bot(boolean enabled, int heals, double healAmount, double healBelow, int healCooldownTicks) {
        public static final Bot DISABLED = new Bot(false, 0, 0, 0, 0);
        public static final Bot DEFAULT = new Bot(true, 0, 8, 8, 30);
    }
}
