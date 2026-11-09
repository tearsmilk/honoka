package dev.tearsmilk.miharu.duels;

import org.bukkit.block.Block;

import java.util.List;

/** Игра со стройкой: взрывы фильтруются по правилу explosion-block-damage и запоминаются для отката. */
interface BuildGame {

    /** explosion-block-damage: placed — оставить в списке только блоки игроков. */
    void filterExplosion(List<Block> blocks);

    /** Взрыв ломает всё — запомнить блоки для отката раунда. */
    void trackExplosion(List<Block> blocks);
}
