# Miharu

PvP-ядро для Paper / Leaf **1.21.11** (Java 25). Автор — **tearsmilk**.

Один плагин, каждый модуль — своя папка в коде (`dev.tearsmilk.miharu.<модуль>`) и свои конфиги в `plugins/Miharu/modules/<модуль>/`.

| Модуль | Что делает | Конфиги |
|---|---|---|
| `pvp` | Бой **1.8** (быстрое: без кулдауна, таблица урона 1.8, блок мечом, крит в спринте, отдача 1.8, гэпплы и реген 1.8) и **1.9** (медленное: ванильная зарядка, щиты) | `modules/pvp/config.yml` |
| `arena` | Настройка арен в мире, сохранение схематик (WorldEdit `.schem` или ванильные `.nbt`), временные миры под игры | `modules/arena/config.yml`, `arenas/`, `schematics/` |
| `game` | Запуск/остановка игр, снимок инвентаря (переживает краш), своё табло и таб, изоляция игроков, возврат (в т.ч. на лобби через Velocity) | `modules/game/config.yml` |
| `bots` | Пакетные фейк-игроки (без сущности на сервере), ИИ easy/medium/hard, дуэль с ботом и клик-тест, меню, статистика | `modules/bots/config.yml`, `kits.yml`, `menu.yml` |

Статистика пишется в `plugins/Miharu/data.db` (SQLite) или в MySQL (`config.yml → database`).

## Сборка

```bash
./scripts/install-paper-server.sh   # один раз: Mojang-mapped сервер Paper 1.21.11 в ~/.m2 (для NMS ботов)
mvn package                         # → target/Miharu-0.1.0.jar
```

## Как это работает

1. `/arena create <id>` → `/arena pos1`, `/arena pos2` (или `pos1 x y z`) → `/arena spawn player`, `/arena spawn bot` → `/arena void` → `/arena mode bot-duel` / `clicktest` → `/arena save`.
2. Игрок: `/bot` → режим → версия PvP (1.8 / 1.9) → сложность.
3. Создаётся пустой мир `miharu_game_*`, туда вставляется схематика, игрок переносится, табло и таб — свои.
4. После конца игры или выхода игрок возвращается, мир удаляется через 5 секунд (`delete-delay-seconds`).

## Команды

| Команда | Право |
|---|---|
| `/bot`, `/bot duel [1.8\|1.9] [easy\|medium\|hard] [арена]`, `/bot clicktest ...`, `/bot stats`, `/bot top`, `/leave` | `miharu.bot` (всем) |
| `/arena create\|pos1\|pos2\|spawn\|void\|mode\|display\|icon\|info\|save\|cancel\|edit\|delete\|list\|enable\|disable` | `miharu.arena` |
| `/pvp [1.8\|1.9\|off] [игрок]` | показать — всем, менять — `miharu.pvp.admin` |
| `/miharu reload\|modules\|games\|start\|stop` | `miharu.admin` |

## Плейсхолдеры (PlaceholderAPI)

`%miharu_pvp_version%`, `%miharu_ingame%`, `%miharu_game_mode%`, `%miharu_games_running%`,
`%miharu_bot_wins%`, `%miharu_bot_wins_<сложность>%`, `%miharu_bot_losses[_<сложность>]%`,
`%miharu_clicktest_best%`, `%miharu_clicktest_best_cps%`.

## Интеграции

Необязательные: WorldEdit / FAWE, PlaceholderAPI, Nexo (`material: "nexo:<id>"` в меню и китах, глифы в заголовках меню).
