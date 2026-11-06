# Miharu

PvP-ядро для Paper / Leaf **1.21.11** (Java 25). Автор — **tearsmilk**.

Один плагин, каждый модуль — своя папка в коде (`dev.tearsmilk.miharu.<модуль>`) и свои конфиги в `plugins/Miharu/modules/<модуль>/`.

| Модуль | Что делает | Конфиги |
|---|---|---|
| `pvp` | Бой **1.8** (быстрое: без кулдауна, таблица урона 1.8, блок мечом, крит в спринте, отдача 1.8, гэпплы и реген 1.8) и **1.9** — ванильный бой 1.21.11 (`vanilla: true`: плагин не меняет урон, отдачу, реген, яблоки; переопределения `combat:` в китах 1.9 не действуют) | `modules/pvp/config.yml` |
| `arena` | Настройка арен в мире, сохранение схематик (WorldEdit `.schem` или ванильные `.nbt`), временные миры под игры | `modules/arena/config.yml`, `arenas/`, `schematics/` |
| `game` | Запуск/остановка игр, снимок инвентаря (переживает краш), своё табло и таб, изоляция игроков, возврат (в т.ч. на лобби через Velocity) | `modules/game/config.yml` |
| `bots` | Пакетные фейк-игроки (без сущности на сервере), ИИ easy/medium/hard, дуэль с ботом (по киту) и клик-тест, меню, статистика | `modules/bots/config.yml`, `kits.yml`, `menu.yml` |
| `duels` | Дуэли 1 на 1 по 16 китам 1.8 (NoDebuff, Debuff, Gapple, BuildUHC, Combo, Boxing, Sumo, Classic, Archer, Soup, Bridge, MLG Rush, Leather, OP Diamond, Diamond, UHC): очередь по киту, раунды, цели Bridge, кровати MLG, суп, золотые головы | `modules/duels/config.yml`, `kits/config.yml`, `kits/<id>.yml` |
| `duels` (мини-игры) | Огненный заряд во 2-м слоте: BedWars, SkyWars, The Bridge (3/5/7), Fireball Fight, Sumo — 1x1 / 1x3 / 1x4 (каждый сам за себя); MLG Rush (5/10/20); SkyControl 2x2 (зона ожидания wait: выбор команды и версии PvP 1.8/1.9, быстрый старт, добор игроков; 3–12 точек захвата 3x3 (стекло + маяк), очки до 1200, боссбары команд, урон на чужой базе, монеты за убийства и захваты, торговец: скорость, прыгучесть, кирка, меч, броня, TNT, фаербол, стержень молнии, золотое яблоко; точки wait, red, blue, red-shop, blue-shop, red-gen, blue-gen, cp1–cp12, режим арены sc-2x2); HFA, TheFight (пустышки); GunGame 1x8 (30 уровней, 3–4 убийства — новый комплект, 27–30 — только лук (27 — без чар), PvP 1.8, за смерть уровень не падает; режим арены gg-8, точки p1–p8, старт от 2); BowSpleef 1x8 (арена из TNT, лук Бесконечность+Огонь, стрела поджигает 4–5 ближайших блоков TNT, он падает без взрыва; без урона и отдачи; перо — 3 двойных прыжка; точки p1–p8, режим арены bs-8, старт от 2); BoxPvP 1x4 / 2x4 / 3x4 / 4x4 (в командах — выбор команды в мини-лобби, классы в команде не повторяются; мини-лобби, выбор класса за 30 с — Мечник, Лучник, Танк, Поддержка, Хиллер, Ассасин; захват квадрата 3x3 шерстью и удержание 3 с; точки p1–p4, pre1–pre4, box) — 1x1, только игроки, PvP 1.8 | `modules/duels/minigames/` |
| `duels` (1.9) | Медленное PvP: киты «Паутина PvP», OP Mace, Elytra Mace и SpearMace открыты, остальные заблокированы (`locked: true` — барьер, запуск выключен); всего 16 китов (Sword & Shield, Diamond, OP Diamond, Heavy, Axe & Shield, Mace, OP Mace, Trident, Spear, Netherite Spear, Lunge Spear, Sword & Crossbow, Archer, Spear & Shield, Survival, Crystal). Щиты, топор выбивает щит, удар булавой, тотемы | `modules/duels/kits-1.9/` |
| `duels` (митапы) | Шалкер в 8-м слоте: 1x2, 1x4, 1x8, 1x16, 2x2, 2x4, 2x8. Коробки подготовки с шалкерами лута (полный сет + оружие + случайное), минута на сборы, голосование 1.8/1.9, последний игрок/команда побеждает | `modules/duels/config.yml → meetups` |
| `duels` (быстрый старт) | Во всех групповых режимах (мини-игры 1x3/1x4, BoxPvP, митапы): в очереди половина участников — таймер 30 с, потом старт с теми, кто есть; зелёный краситель «Быстрый старт» — голосование за старт сразу | `modules/duels/config.yml → quick-start` |
| `duels` (свободные арены) | Sword, SpearMace, ElytraMace (ванильное PvP 1.21.11; в ките на Sword нельзя элитры, заряды ветра, булаву, копья, на SpearMace — элитры): одна точка `spawn`, игра не начинается и не кончается, вход только со своим китом через NPC (`/ffa npc <тип>`, OP) или `/ffa <тип>`, умер — по-настоящему (вещи выпадают, экран смерти), после «Возродиться» — в лобби, ломаются только блоки игроков, выйти `/lobby` — не раньше 10 с после урона (боссбар), мир не удаляется и не чистится | `modules/duels/config.yml → free-arenas` |
| `duels` (FFA-арены 1.8) | KnockbackFFA (палка Отбрасывание I + удочка, без урона — только в пустоту), BowFFA (лук Откидывание I + Бесконечность, стрела, палка Отбрасывание I), SwordFFA (железный меч Острота I, 4 золотых яблока, удочка, железная броня Защита I): одна точка `spawn`, вход через NPC (`/ffa npc knockbackffa\|bowffa\|swordffa`), кит выдаёт сервер, умер — снова на арене | `modules/duels/config.yml → free-arenas.types` |
| `xp` | Валюта «Опыт»: за сыгранную игру и победу (суммы по режимам), скорборд лобби `{xp}`, `%miharu_xp%`, `/xp`, `/xp give\|take\|set` (OP), голограмма топа `/xptop place\|remove\|refresh` (OP) | `modules/xp/config.yml` |
| `xp` (экипировка) | Косметика → «Украшения для брони» (`/trims`): сеты отделок брони Дюна (300xp), Дебри (500xp), Поток (1000xp); перо — купить, потом применить; в играх отделка сама накладывается на любую надетую броню игрока | `modules/xp/config.yml → armor-trims` |
| `party` | Пати до 5 человек: `/party invite·accept·kick·leave·list·promote·delete·help·chat` (сокращения `/p i·a·k·l·list·p·d·h`), `/pc <текст>` — чат пати (работает и в игре) | `modules/party/config.yml` |
| `lobby` | Предметы лобби (каменный меч — дуэли 1.8, золотой — 1.9, огненный заряд — мини-игры, звезда Незера (5-й слот) — косметика: украшения брони, палки (сумо), теги; книга — статистика, шалкер — митапы, глаз Эндера — наблюдение, краситель — выйти из очереди), своё табло и таб, защита, `/lobby`, `/setlobby` | `modules/lobby/config.yml` |

Статистика пишется в `plugins/Miharu/data.db` (SQLite) или в MySQL (`config.yml → database`).

## Сборка

```bash
./scripts/install-paper-server.sh   # один раз: Mojang-mapped сервер Paper 1.21.11 в ~/.m2 (для NMS ботов)
mvn package                         # → target/Miharu-0.2.48.jar
```

## Как это работает

1. `/arena create <id>` → `/arena pos1`, `/arena pos2` (или `pos1 x y z`) → `/arena spawn player`, `/arena spawn bot` → `/arena void` → `/arena mode bot-duel` / `clicktest` → `/arena save`.
2. Игрок: `/bot` → режим → версия PvP (1.8 / 1.9) → сложность.
3. Создаётся пустой мир `miharu_game_*`, туда вставляется схематика, игрок переносится, табло и таб — свои.
4. После конца игры или выхода игрок возвращается, мир удаляется через 5 секунд (`delete-delay-seconds`).

## Дуэли и киты

Каменный меч (1.8) или золотой меч (1.9) в лобби → кит → **Бот** (→ сложность) или **Игрок** (→ очередь на этот же кит) → свободная арена.

Кит = файл `modules/duels/kits/<id>.yml`: `armor`, `items`, `fill`, `effects`, `rules` (урон, бокс до N ударов, сумо, вода = поражение, стройка, бесконечные блоки, цели/кровати, смерть = респавн, суп, кулдаун жемчуга), `combat` (свои хит-делей и отдача поверх `modules/pvp`), `bot` (можно ли с ботом, сколько раз бот лечится). В `kits/config.yml` кит включается и меняется его предмет в меню. Новый кит — новый yml.

Режимы арен: `duel` (p1, p2), `sumo` (p1, p2), `bridge` (p1, p2, goal1, goal2), `mlg` / `bedwars` / `fireball` (p1, p2, bed1, bed2), `skywars` (p1, p2, для 1x3/1x4 ещё p3, p4 + сундуки), `meetup` (p1, p2); по числу игроков — `sw-duo/trio/quatro`, `bw-*`, `br-*`, `fb-*`, `sumo-*`, `box-quatro`; митапы `mup-12…mup-28` — на каждого игрока p<N>, pre<N>, loot<N>a, loot<N>b.

## Команды

| Команда | Право |
|---|---|
| `/bot`, `/bot duel [1.8\|1.9] [easy\|medium\|hard] [арена]`, `/bot clicktest ...`, `/bot stats`, `/bot top`, `/leave` | `miharu.bot` (всем) |
| `/arena create\|pos1\|pos2\|spawn\|void\|mode\|display\|icon\|info\|save\|cancel\|edit\|delete\|list\|enable\|disable` | `miharu.arena` |
| `/duel`, `/duel <кит>`, `/duel queue <кит>`, `/duel leave`, `/duel kits`, `/duel stats` | `miharu.duel` (всем) |
| `/duel 1.8`, `/duel 1.9` — меню категории; киты 1.9 в очереди с id `m-<кит>` | `miharu.duel` |
| `/stats [игрок]` | всем |
| `/kit edit` — своя раскладка предметов в любом ките (сохранить и выйти) | всем |
| `/kit create` — свой кит (до 5) из предметов всех китов, общий для 1.8 и 1.9; играть — нижний ряд меню дуэлей 1.8 или 1.9 (PvP — по меню) | всем |
| `/spectate [игрок]`, `/lobby` | `miharu.spectate` (всем) |
| `/setlobby` | `miharu.admin` |
| `/force-start [режим]` — митап: старт с теми, кто в очереди (от 2), или пропуск подготовки | только OP |
| `/miharu adm-items` — все предметы из всех китов, только просмотр | только OP |
| `/miharu adm-mode` — режим строителя: свой сохраняемый инвентарь, креатив, полёт, защиты лобби выключены | только OP |
| `/pvp [1.8\|1.9\|off] [игрок]` | показать — всем, менять — `miharu.pvp.admin` |
| `/miharu reload\|modules\|games\|start\|stop` | `miharu.admin` |

## Плейсхолдеры (PlaceholderAPI)

`%miharu_pvp_version%`, `%miharu_ingame%`, `%miharu_game_mode%`, `%miharu_games_running%`,
`%miharu_bot_wins%`, `%miharu_bot_wins_<сложность>%`, `%miharu_bot_losses[_<сложность>]%`,
`%miharu_clicktest_best%`, `%miharu_clicktest_best_cps%`,
`%miharu_duel_wins[_<кит>]%`, `%miharu_duel_losses[_<кит>]%`, `%miharu_queue_kit%`, `%miharu_queue_size[_<кит>]%`.

## Интеграции

Необязательные: WorldEdit / FAWE, PlaceholderAPI, Nexo (`material: "nexo:<id>"` в меню и китах, глифы в заголовках меню).
