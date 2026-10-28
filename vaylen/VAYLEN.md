# VAYLEN — язык · плагин · песочница

> Перенесено из артефакта «VAYLEN» (claude.ai, 2026-10-27). Исходная страница: `index.html`, логотип: `logo.png`.

**VAYLEN** — язык скриптов с синтаксисом Python и плагин для Paper. Игроки пишут код на сайте, а он сразу строит дома, призывает мобов и запускает мини-игры на их участке. Админы делают на том же языке команды, события, предметы и меню сервера.

| | |
|---|---|
| Paper | 1.21 – 26.3 |
| Jar | 1 на все версии |
| Функций песочницы | 128 |
| Событий | 42 |
| Тесты | 31/31 |
| Мод игрокам | не нужен |

Работает на FishPvP · в игре `/sandbox`.

## Обновления

- **[новое · Игры] Две готовые игры с меню и магазином** — «Охотник за рудой» — кликер в сундуке с улучшениями. «Арена волн» — 20 волн мобов, боссы, магазин между волнами.
- **[новое · GUI] Меню-сундуки Menu()** — Кнопки с кликом, рамки, заливки, (ряд, столбец), on_close, глифы Nexo в заголовке. Предметы из меню не вытащить.
- **[Редактор] Открыть файл, перетащить, скачать** — Код загружается целиком с отступами — больше никаких потерянных пробелов при копировании.
- **[Редактор] Кнопка «Исправить»** — Опечатки, текст без кавычек, скобки, двоеточия, отступы, elif без if, координаты за границей — одним кликом.
- **[Редактор] Подсказки на каждом шаге** — Как Spyglass: блоки, мобы, предметы, частицы, звуки, эффекты — по месту аргумента; списки с сервера.
- **[Песочница] Свой участок и сайт-редактор** — /sandbox для всех игроков: участок 64×64, 118 функций мода «Кодинг-мир», консоль с ошибками.
- **[Ядро] Один jar на Paper 1.21 – 26.3** — Свой интерпретатор VAYLEN, очереди без зависаний, флаги, Data, 35 событий. Самопроверка 31/31 на 1.21.1, 1.21.4 и 26.3.

## Как это работает

1. **/sandbox** — в игре вводишь команду, тебя переносит на свой участок 64×64, в чат приходит ссылка.
2. **Редактор** — ссылка открывает сайт: подсветка, подсказки на каждом шаге, примеры и справка.
3. **▶ Запуск** — Ctrl+Enter: код уходит на сервер, выполняется на твоём участке.
4. **Результат** — видишь постройку в игре, вывод и ошибки в консоли сайта, с кнопкой «Исправить».

## Веб-редактор

Как Spyglass в VS Code: редактор знает, какой сейчас аргумент. После кавычки в `SetBlock(…, "` — блоки сервера, в `SummonMob(…, "` — мобы, на месте `x` — `PlayerX`. Списки берутся с самого сервера и совпадают с его версией.

**Что ещё умеет редактор:**
- Подсказка аргументов: внутри `Fill(…` видно все параметры, текущий подсвечен.
- Ошибки с номером строки; клик — переход к строке.
- «📂 Открыть файл», перетаскивание `.vln` мышью, «💾 Скачать».
- Тёмная и светлая тема, примеры в один клик, справка с поиском.

**Что чинит «Исправить»:**
- Опечатки: `prnt` → `print`, `"stne"` → `"stone"`.
- Текст без кавычек, незакрытые скобки и строки.
- Забытое `:`, пропущенный или кривой отступ, `elif` без `if`.
- Координаты за границей участка — прижмёт к краю.

Если не работает Ctrl+Space (его иногда забирает Windows): Tab после начатого слова и Ctrl+`.`. Подсказка ещё появляется сама после `(`, `,` и кавычки.

## Демо-программы с главной

### Домик

```python
# Домик с деревом
House(-3, 0, -3, 7, 6, 3)
Tree(6, 0, 4, "cherry")
print("Готово!")
```

### Спираль

```python
import math
colors = ["red", "orange", "yellow",
          "lime", "blue", "purple"]
for i in range(90):
    a = i * 0.3
    r = 2 + i / 25
    SetBlock(round(math.cos(a) * r), i // 8,
             round(math.sin(a) * r),
             colors[i % 6] + "_concrete")
```

### Лабиринт

```python
# Случайный лабиринт 6×6 клеток
Maze(-6, 0, -6, 6, 6, height=2)
SetBlock(5, 0, 6, "gold_block")
print("Найди золото!")
```

### Сердце

```python
art = [".XX...XX.", "XXXX.XXXX",
       "XXXXXXXXX", ".XXXXXXX.",
       "..XXXXX..", "...XXX...",
       "....X...."]
for r, line in enumerate(art):
    for c, ch in enumerate(line):
        if ch == "X":
            SetBlock(c - 4, 8 - r, 0, "red_wool")
```

## Скрипты сервера: контейнеры

Админы пишут файлы `.vln` в `plugins/Vaylen/scripts/`: команды, события с переключателями, задачи, таймеры, свои предметы, меню. Каждая команда и событие работает в своей очереди: `wait("2s")` не вешает сервер, а лимит времени на тик не даёт уронить TPS.

### `@command(name, aliases=[], permission=None, usage="")`

*в Denizen: command-скрипт*

Своя команда сервера. Аргументы приходят списком args, игрок — в player. Подсказки Tab — функция tab().

```python
@command("дом", aliases=["home"], usage="/дом [имя]")
def home(player, args):
    name = args[0] if args else "main"
    loc = player.flag(f"home.{name}")
    if loc is None:
        player.tell("<red>Дом не задан. /сетдом")
        return
    player.teleport(loc)
```

### `@on(event, **switches)`

*в Denizen: world-скрипт*

Обработчик события, как world-скрипт в Denizen. Событие пишется по-русски или по-английски, переключатели сужают его: block=, item=, in_area=, with=, cause=.

```python
@on("player breaks block", block="diamond_ore")
def diamond(event):
    event.player.tell("<aqua>Алмаз!")
    return {"drops": [Item("diamond", 2)]}
```

### `@task(name=None)`

*в Denizen: task-скрипт*

Переиспользуемая задача (task-скрипт). Запуск: run("имя", delay=20, args=...). Можно запускать из команд, событий, меню и NPC.

```python
@task
def fireworks_show(where):
    for i in range(10):
        Firework(where.add(0, i, 0), colors=["red", "gold"])
        wait(10)

run("fireworks_show", where=player.location, delay=40)
```

### `@item(id, material, name, lore=[], model=None, glow=False, flags={})`

*в Denizen: item-скрипт*

Свой предмет (item-скрипт): имя, описание, модель из пака (item_model / Nexo id), флаги. Выдача: Item("id"). Проверка в событиях: with="id".

```python
@item("wand", "blaze_rod", "<gold>Посох огня", lore=["<gray>ПКМ — огненный шар"], glow=True)
def wand(): pass

@on("player right clicks", with="wand")
def cast(event):
    event.player.shoot("small_fireball", speed=1.5)
```

### `@npc(name, skin=None, look_close=True)`

*в Denizen: assignment (скоро)*

Поведение NPC (assignment-скрипт). Шаги диалога — @step, переход — npc.step("имя"). NPC — свой, без Citizens: манекен игрока с моделью или скином.

```python
@npc("Рыбак Ефим", skin="Notch")
class Fisher:
    @step("start")
    def on_click(self, player, npc):
        npc.say("Принесёшь трёх карпов?")
        npc.step("wait_fish", player)

    @step("wait_fish")
    def on_click(self, player, npc):
        if player.take("cod", 3):
            npc.say("Спасибо! Держи.")
            player.give(Item("fishing_rod"))
```

### `@menu(title, rows=3, glyph=None)`

*в Denizen: inventory → Menu()*

Меню-сундук (inventory-скрипт): слоты, кнопки с обработчиками, фон глифом Nexo. Открыть: player.open("имя").

```python
@menu("<dark_green>Магазин", rows=3)
class Shop:
    slots = {13: Item("bread", 16, name="<yellow>Хлеб — 10 монет")}

    def click(self, player, slot, item):
        if slot == 13 and player.pay(10):
            player.give(Item("bread", 16))
```

### `@proc(name=None)`

*в Denizen: procedure-скрипт*

Чистая функция (procedure-скрипт), которую можно звать из тегов и других скриптов: <proc[name].context[...]>.

```python
@proc
def rank_color(points):
    return "<gold>" if points > 1000 else "<gray>"

player.tell(f"{rank_color(player.flag('points', default=0))}{player.name}")
```

### `@every(interval)`

*в Denizen: world · delta time*

Таймер (как «on delta time» в Denizen): "20t", "5s", "10m", "1h", "daily 06:00".

```python
@every("10m")
def autosave_message():
    Broadcast("<gray>Мир сохраняется…")
```

### `Data(name, default={})`

*в Denizen: data-скрипт*

Данные скрипта (data-скрипт): словарь из YAML/JSON в папке data/, читается и пишется из кода, сохраняется сам.

```python
kits = Data("kits", {"start": ["stone_sword", "bread*8"]})
for it in kits["start"]:
    player.give(Item.parse(it))
```

### `Menu()`
*в Denizen: inventory-скрипт*

Меню-сундук: кнопки с кликом, рамки, заливки, on_close. Работает и в скриптах сервера, и в песочнице.

```python
m = Menu("<gold>Лавка", rows=3)
m.border("black_stained_glass_pane", name=" ")

def buy(player, slot, click):
    if player.take("emerald", 5):
        player.give(Item("golden_apple"))

m.button(13, "golden_apple", buy, name="<gold>Яблоко — 5 изумрудов")
m.open(player)
```

## Справочник: песочница

### Функции

#### Блоки

**`SetBlock(x, y, z, block)`** — Ставит блок в точку (x, y, z). Можно указать состояние: "oak_stairs[facing=east]".

```python
SetBlock(0, 0, 0, "diamond_block")
```

**`GetBlock(x, y, z)`** — Возвращает название блока в точке, например "stone" или "air".

```python
if GetBlock(0, -1, 0) == "grass_block":
    print("Под нами трава")
```

**`IsBlock(x, y, z, block)`** — True, если в точке стоит этот блок.

```python
if IsBlock(5, 0, 5, "gold_block"):
    Say("Золото на месте!")
```

**`IsAir(x, y, z)`** — True, если в точке пусто (воздух).

```python
if IsAir(0, 0, 0):
    SetBlock(0, 0, 0, "stone")
```

**`BreakBlock(x, y, z, drop=True)`** — Ломает блок, как будто его сломал игрок. drop=False — без выпадения предмета.

```python
BreakBlock(0, 0, 0)
```

**`Fill(x1, y1, z1, x2, y2, z2, block)`** — Заполняет прямоугольную область блоками (до 1 000 000 блоков за раз).

```python
Fill(0, 0, 0, 9, 0, 9, "stone")
```

**`FillHollow(x1, y1, z1, x2, y2, z2, block)`** — Строит пустую коробку: стены, пол и потолок, а внутри воздух.

```python
FillHollow(0, 0, 0, 6, 4, 6, "glass")
```

**`Walls(x1, y1, z1, x2, y2, z2, block)`** — Строит четыре стены без пола и потолка.

```python
Walls(0, 0, 0, 8, 3, 8, "cobblestone")
```

**`Floor(x1, z1, x2, z2, y, block)`** — Кладёт горизонтальный пол на высоте y.

```python
Floor(-5, -5, 5, 5, -1, "oak_planks")
```

**`Replace(x1, y1, z1, x2, y2, z2, old, new)`** — Заменяет в области один блок на другой. Возвращает число замен.

```python
n = Replace(-10, -1, -10, 10, -1, 10, "grass_block", "sand")
print("Заменено:", n)
```

**`Clear(x1, y1, z1, x2, y2, z2)`** — Очищает область (ставит воздух).

```python
Clear(0, 0, 0, 20, 20, 20)
```

**`CountBlocks(x1, y1, z1, x2, y2, z2, block)`** — Считает, сколько таких блоков в области.

```python
print(CountBlocks(0, 0, 0, 10, 10, 10, "stone"))
```

**`TopY(x, z)`** — Высота самого верхнего непустого блока в столбце (x, z).

```python
y = TopY(10, 10)
SetBlock(10, y + 1, 10, "torch")
```

**`Line(x1, y1, z1, x2, y2, z2, block)`** — Проводит прямую линию из блоков между двумя точками.

```python
Line(0, 0, 0, 10, 10, 0, "gold_block")
```

**`Copy(x1, y1, z1, x2, y2, z2)`** — Копирует область в буфер. Возвращает число блоков.

```python
Copy(0, 0, 0, 5, 5, 5)
```

**`Paste(x, y, z)`** — Вставляет скопированную область, (x, y, z) — угол вставки.

```python
Copy(0, 0, 0, 5, 5, 5)
Paste(20, 0, 0)
```

#### Фигуры и постройки

**`Sphere(x, y, z, radius, block)`** — Сплошной шар с центром (x, y, z).

```python
Sphere(0, 10, 0, 5, "glass")
```

**`HollowSphere(x, y, z, radius, block)`** — Пустой шар (только оболочка).

```python
HollowSphere(0, 10, 0, 8, "glass")
```

**`Dome(x, y, z, radius, block)`** — Купол — верхняя половина пустого шара.

```python
Dome(0, 0, 0, 10, "white_stained_glass")
```

**`Cylinder(x, y, z, radius, height, block)`** — Сплошной цилиндр (столб), основание в (x, y, z).

```python
Cylinder(0, 0, 0, 3, 10, "quartz_block")
```

**`HollowCylinder(x, y, z, radius, height, block)`** — Труба — пустой цилиндр.

```python
HollowCylinder(0, 0, 0, 4, 12, "stone_bricks")
```

**`Circle(x, y, z, radius, block)`** — Кольцо (окружность) на высоте y.

```python
Circle(0, 0, 0, 10, "red_wool")
```

**`Disk(x, y, z, radius, block)`** — Закрашенный круг на высоте y.

```python
Disk(0, -1, 0, 8, "blue_concrete")
```

**`Pyramid(x, y, z, size, block, hollow=False)`** — Пирамида: (x, y, z) — центр основания, size — половина ширины.

```python
Pyramid(0, 0, 0, 10, "sandstone")
```

**`House(x, y, z, width=7, depth=7, height=4, wall="oak_planks", roof="spruce_planks")`** — Готовый домик с дверью, окнами, полом и крышей. (x, y, z) — угол дома.

```python
House(0, 0, 0)
House(12, 0, 0, 9, 7, 5, "stone_bricks", "dark_oak_planks")
```

**`Tower(x, y, z, height, radius=3, block="stone_bricks")`** — Круглая башня с зубцами наверху.

```python
Tower(0, 0, 0, 15)
```

**`Tree(x, y, z, kind="oak")`** — Сажает дерево: oak, birch, spruce, jungle, acacia, dark_oak, cherry, mangrove, azalea.

```python
for i in range(5):
    Tree(i * 6, 0, 10, "birch")
```

**`Maze(x, y, z, width, depth, height=3, block="hedge")`** — Строит случайный лабиринт. width и depth — число клеток, block — материал стен ("hedge" = листва).

```python
Maze(0, 0, 0, 10, 10)
```

**`Stairs(x, y, z, length, direction="north", block="stone_brick_stairs")`** — Лестница вверх в сторону north/south/east/west.

```python
Stairs(0, 0, 0, 10, "east")
```

#### Мобы и существа

**`SummonMob(x, y, z, mob, options=None)`** — Призывает моба и возвращает его номер (id). options — список или словарь: "glowing", "noai", "baby", "silent", "invulnerable", "fire", "persistent", "nogravity", {"name": "Вася", "health": 40, "effect": "speed", "item": "diamond_sword", "armor": "iron"}.

```python
zombie = SummonMob(5, 0, 5, "zombie", ["glowing", "noai"])
SummonMob(0, 0, 0, "pig", {"name": "Хрюша", "baby": True})
```

**`Summon(x, y, z, entity, nbt="")`** — Призывает любую сущность (лодку, стрелу, вагонетку...) с NBT-данными как в команде /summon.

```python
Summon(0, 5, 0, "falling_block", "{BlockState:{Name:'minecraft:sand'}}")
```

**`SpawnItem(x, y, z, item, count=1)`** — Бросает предмет на землю.

```python
SpawnItem(0, 1, 0, "diamond", 5)
```

**`KillMobs(mob="all", radius=0)`** — Убирает мобов (не игроков). radius=0 — во всём мире. Возвращает количество.

```python
KillMobs("zombie")
KillMobs("all", 20)
```

**`CountMobs(mob="all", radius=0)`** — Считает мобов. radius — расстояние от игрока, 0 — весь мир.

```python
if CountMobs("zombie") < 5:
    SummonMob(0, 0, 0, "zombie")
```

**`FindMobs(mob="all", radius=32)`** — Список id мобов рядом с игроком.

```python
for m in FindMobs("cow"):
    SetMobGlow(m)
```

**`NearestMob(mob="all", radius=64)`** — id ближайшего к игроку моба или None.

```python
m = NearestMob("zombie")
if m:
    KillMob(m)
```

**`MobExists(id)`** — True, если моб с таким id жив.

```python
while MobExists(boss):
    wait(20)
Say("Босс побеждён!")
```

**`MobType(id)`** — Тип моба, например "zombie".

```python
print(MobType(m))
```

**`MobPos(id)`** — Координаты моба списком [x, y, z].

```python
x, y, z = MobPos(m)
```

**`MobX(id)`** — Координата X моба.

```python
print(MobX(m))
```

**`MobY(id)`** — Координата Y моба.

```python
print(MobY(m))
```

**`MobZ(id)`** — Координата Z моба.

```python
print(MobZ(m))
```

**`MoveMob(id, x, y, z)`** — Телепортирует моба.

```python
MoveMob(m, 0, 10, 0)
```

**`MobWalkTo(id, x, y, z, speed=1.0)`** — Моб сам идёт к точке (если у него есть ИИ).

```python
MobWalkTo(m, 10, 0, 10)
```

**`PushMob(id, vx, vy, vz)`** — Толкает моба (задаёт скорость). vy=1 — подбросить вверх.

```python
PushMob(m, 0, 1.5, 0)
```

**`MobHealth(id)`** — Здоровье моба.

```python
print(MobHealth(m))
```

**`SetMobHealth(id, health)`** — Задаёт здоровье (и максимум, если нужно больше).

```python
SetMobHealth(m, 100)
```

**`SetMobName(id, name)`** — Даёт мобу имя над головой.

```python
SetMobName(m, "Король")
```

**`SetMobGlow(id, on=True)`** — Подсветка моба (видно сквозь стены).

```python
SetMobGlow(m)
```

**`SetMobAI(id, on)`** — Включает/выключает ИИ: без ИИ моб стоит статуей.

```python
SetMobAI(m, False)
```

**`SetBaby(id, on=True)`** — Делает моба малышом.

```python
SetBaby(m)
```

**`SetMobSpeed(id, speed)`** — Скорость ходьбы (обычно 0.2–0.3).

```python
SetMobSpeed(m, 0.5)
```

**`MobEffect(id, effect, seconds=10, level=1)`** — Накладывает эффект на моба.

```python
MobEffect(m, "levitation", 5)
```

**`MobRide(id, mount_id)`** — Сажает моба верхом на другого.

```python
chicken = SummonMob(0, 0, 0, "chicken")
zombie = SummonMob(0, 0, 0, "zombie", ["baby"])
MobRide(zombie, chicken)
```

**`KillMob(id)`** — Убивает одного моба.

```python
KillMob(m)
```

#### Игрок

**`Teleport(x, y, z)`** — Телепортирует игрока (камеру).

```python
Teleport(0, 20, 0)
```

**`Move(dx, dy, dz)`** — Сдвигает игрока на столько блоков.

```python
Move(0, 5, 0)
```

**`Look(yaw, pitch)`** — Поворачивает взгляд: yaw 0=юг, 90=запад, 180=север, -90=восток; pitch -90=вверх, 90=вниз.

```python
Look(180, 30)
```

**`LookAt(x, y, z)`** — Поворачивает взгляд на точку.

```python
LookAt(0, 0, 0)
```

**`PlayerPos()`** — Координаты игрока [x, y, z] (целые).

```python
x, y, z = PlayerPos()
```

**`LookingAt()`** — Блок под прицелом [x, y, z] или None (до 100 блоков).

```python
p = LookingAt()
if p:
    SetBlock(p[0], p[1], p[2], "gold_block")
```

**`Give(item, count=1)`** — Даёт предмет в инвентарь.

```python
Give("diamond_sword")
Give("cake", 3)
```

**`ClearInventory()`** — Очищает инвентарь.

```python
ClearInventory()
```

**`HasItem(item)`** — Сколько таких предметов у игрока (0 — нет).

```python
if HasItem("diamond") > 0:
    Say("Богач!")
```

**`SetHealth(health)`** — Задаёт здоровье игрока (20 = 10 сердец).

```python
SetHealth(10)
```

**`Heal()`** — Полностью лечит игрока.

```python
Heal()
```

**`Feed()`** — Полностью насыщает игрока.

```python
Feed()
```

**`Effect(effect, seconds=10, level=1)`** — Накладывает эффект: speed, jump_boost, night_vision, levitation, invisibility, regeneration...

```python
Effect("night_vision", 600)
```

**`ClearEffects()`** — Снимает все эффекты.

```python
ClearEffects()
```

**`Gamemode(mode)`** — Режим: "spectator" (наблюдатель), "creative", "survival", "adventure".

```python
Gamemode("creative")
```

**`SetXP(levels)`** — Задаёт уровень опыта.

```python
SetXP(30)
```

**`AddXP(levels)`** — Добавляет уровни опыта.

```python
AddXP(5)
```

**`SetSpawn(x, y, z)`** — Точка возрождения игрока.

```python
SetSpawn(0, 0, 0)
```

**`SetFlySpeed(speed)`** — Скорость полёта (обычно 0.05, наблюдатель — 0.1).

```python
SetFlySpeed(0.2)
```

**`Launch(vx, vy, vz)`** — Подбрасывает игрока (в режиме выживания/творчества).

```python
Launch(0, 2, 0)
```

**`PlayerInArea(x1, y1, z1, x2, y2, z2)`** — True, если игрок внутри области.

```python
if PlayerInArea(0, 0, 0, 10, 10, 10):
    Title("Ты в зоне!")
```

**`PlayerNear(x, y, z, radius)`** — True, если игрок ближе radius блоков к точке.

```python
if PlayerNear(0, 0, 0, 5):
    Say("Близко!")
```

**`Players()`** — Список имён всех игроков на сервере.

```python
print(Players())
```

#### Мир и погода

**`SetTime(time)`** — Время суток: число 0–24000 или "day", "noon", "evening", "night", "midnight", "morning".

```python
SetTime("night")
```

**`GetTime()`** — Текущее время суток 0–23999 (то же, что переменная Time).

```python
print(GetTime())
```

**`SetWeather(kind, seconds=600)`** — Погода: "clear", "rain", "thunder".

```python
SetWeather("thunder")
```

**`GetWeather()`** — Текущая погода: "clear", "rain" или "thunder".

```python
if GetWeather() == "rain":
    SetWeather("clear")
```

**`DayCycle(on)`** — Включает или останавливает смену дня и ночи.

```python
DayCycle(False)
```

**`Gamerule(name, value)`** — Меняет правило игры, например "doMobSpawning", "keepInventory".

```python
Gamerule("keepInventory", True)
```

**`Difficulty(level)`** — Сложность: "peaceful", "easy", "normal", "hard".

```python
Difficulty("peaceful")
```

**`Command(text)`** — Выполняет любую команду Minecraft (без /). Возвращает результат.

```python
Command("weather clear")
Command("give @p apple 5")
```

**`SetWorldSpawn(x, y, z)`** — Точка появления мира.

```python
SetWorldSpawn(0, 0, 0)
```

**`Biome(x, y, z)`** — Название биома в точке.

```python
print(Biome(0, 0, 0))
```

**`LightLevel(x, y, z)`** — Уровень освещённости 0–15.

```python
if LightLevel(0, 0, 0) < 8:
    SetBlock(0, 0, 0, "torch")
```

#### Эффекты

**`Explosion(x, y, z, power=3, fire=False, breaks=True)`** — Взрыв. power 4 — как динамит. breaks=False — без разрушений.

```python
Explosion(0, 0, 0, 5)
```

**`Lightning(x, y, z)`** — Удар молнии.

```python
Lightning(0, 0, 0)
```

**`Particle(x, y, z, particle="flame", count=20, spread=0.5)`** — Частицы: flame, heart, smoke, cloud, end_rod, happy_villager, firework, explosion, note, soul_fire_flame...

```python
Particle(0, 2, 0, "heart", 30)
```

**`Sound(sound, x=None, y=None, z=None, volume=1, pitch=1)`** — Проигрывает звук (без координат — у игрока). Например "entity.cat.ambient", "block.note_block.pling".

```python
Sound("entity.player.levelup")
```

**`Firework(x, y, z, color="red", power=1)`** — Запускает фейерверк. Цвета: red, orange, yellow, green, blue, purple, white, pink...

```python
Firework(0, 0, 0, "blue")
```

#### Сообщения и консоль

**`print(*values)`** — Пишет в консоль справа (стандартный print из Python).

```python
print("Привет!", PlayerX)
```

**`Say(text)`** — Сообщение в игровой чат.

```python
Say("Всем привет!")
```

**`Title(text, subtitle="", seconds=3)`** — Большая надпись по центру экрана.

```python
Title("Уровень 1", "Найди алмаз")
```

**`ActionBar(text)`** — Надпись над панелью предметов.

```python
ActionBar("Очки: " + str(score))
```

**`ClearConsole()`** — Очищает консоль справа.

```python
ClearConsole()
```

**`Help(name=None)`** — Справка по функции в консоли. Без аргумента — список всех функций.

```python
Help("SummonMob")
```

#### Время и ожидание

**`wait(ticks=1)`** — Пауза: 20 тиков = 1 секунда. Мир продолжает жить.

```python
for i in range(10):
    SetBlock(0, i, 0, "stone")
    wait(5)
```

**`Wait(ticks=1)`** — То же, что wait.

```python
Wait(20)
```

**`WaitSeconds(seconds)`** — Пауза в секундах (можно дробные).

```python
WaitSeconds(1.5)
```

**`sleep(seconds)`** — То же, что WaitSeconds (как time.sleep).

```python
sleep(2)
```

**`WaitUntil(condition, timeout=0)`** — Ждёт, пока функция condition не вернёт True. timeout — максимум тиков (0 — без ограничения).

```python
WaitUntil(lambda: PlayerY > 30)
Say("Ты высоко!")
```

**`Ticks()`** — Сколько тиков прошло с запуска программы.

```python
print(Ticks())
```

**`Stop()`** — Останавливает программу.

```python
if PlayerY < -50:
    Stop()
```

#### События

**`OnEvent(event, func=None)`** — Подписывает функцию на событие: tick, chat, block_break, block_place, mob_death, player_move, key. Можно как декоратор: @OnEvent("chat").

```python
def hello(msg):
    print("Ты написал:", msg)
OnEvent("chat", hello)
```

**`Every(ticks, func)`** — Вызывает func каждые ticks тиков, пока программа работает.

```python
def spawn():
    SummonMob(0, 0, 0, "chicken")
Every(40, spawn)
```

**`After(ticks, func)`** — Вызывает func один раз через ticks тиков.

```python
After(100, lambda: Say("Прошло 5 секунд"))
```

#### Полезное

**`Random(a, b)`** — Случайное целое число от a до b включительно.

```python
x = Random(-10, 10)
```

**`RandomFloat()`** — Случайное дробное число от 0 до 1.

```python
if RandomFloat() < 0.5:
    print("Орёл")
```

**`Choice(items)`** — Случайный элемент списка.

```python
SetBlock(0, 0, 0, Choice(["red_wool", "blue_wool", "lime_wool"]))
```

**`randint(a, b)`** — То же, что Random (как random.randint).

```python
n = randint(1, 6)
```

**`Distance(x1, y1, z1, x2, y2, z2)`** — Расстояние между двумя точками.

```python
print(Distance(0, 0, 0, 3, 4, 0))
```

**`DistanceToPlayer(x, y, z)`** — Расстояние от игрока до точки.

```python
if DistanceToPlayer(0, 0, 0) < 3:
    Say("Ты у старта")
```

**`Blocks(search="")`** — Список названий блоков (с фильтром).

```python
print(Blocks("wool"))
```

**`Mobs(search="")`** — Список названий мобов (с фильтром).

```python
print(Mobs())
```

#### Меню (GUI)

**`Menu(title, rows=3, type="chest")`** — Создаёт меню-сундук (rows от 1 до 6; type: chest, hopper, dispenser, dropper). В заголовке MiniMessage и глифы Nexo <glyph:id>.

```python
m = Menu("<gold>Магазин", rows=3)
m.open()
```

**`button(slot, item, on_click, name=None, lore=[], amount=1, glow=False)`** — m.button(…) — кнопка: при клике вызывается on_click(player, slot, click), click — "left", "right", "shift". Слот — номер (0…) или (ряд, столбец).

```python
def buy(player, slot, click):
    Give("bread", 8)
    print("Куплено!")

m.button(13, "bread", buy, name="<yellow>Хлеб ×8")
```

**`set(slot, item, name=None, lore=[], amount=1, glow=False)`** — m.set(…) — положить предмет в слот (без клика). None — очистить слот.

```python
m.set((1, 4), "diamond", name="<aqua>Алмаз", lore=["<gray>Редкий"])
```

**`fill(item, name=" ", all=False)`** — m.fill(…) — заполнить пустые слоты (all=True — все).

```python
m.fill("gray_stained_glass_pane", name=" ")
```

**`border(item, name=" ")`** — m.border(…) — рамка по краю сундука.

```python
m.border("black_stained_glass_pane", name=" ")
```

**`row(row, item, name=None)`** — m.row(…) — заполнить ряд (с нуля).

```python
m.row(0, "lime_stained_glass_pane", name=" ")
```

**`open(player=None)`** — m.open() — открыть меню (в песочнице — тебе; в скриптах сервера — m.open(player)).

```python
m.open()
```

**`close()`** — m.close() — закрыть меню у всех, кто его смотрит.

```python
m.close()
```

**`on_close(func)`** — m.on_close(f) — f(player) вызовется, когда меню закроют. Можно как декоратор: @m.on_close

```python
@m.on_close
def bye(player):
    print("Меню закрыто")
```

**`clear(slot=None)`** — m.clear() — очистить всё меню; m.clear(slot) — один слот.

```python
m.clear()
```

### Переменные

| Переменная | Описание |
|---|---|
| `PlayerX` | Координата X игрока (целое число) |
| `PlayerY` | Координата Y игрока (высота ног) |
| `PlayerZ` | Координата Z игрока |
| `Xplayer` | То же, что PlayerX |
| `Yplayer` | То же, что PlayerY |
| `Zplayer` | То же, что PlayerZ |
| `PlayerExactX` | Точная X игрока (дробная) |
| `PlayerExactY` | Точная Y игрока (дробная) |
| `PlayerExactZ` | Точная Z игрока (дробная) |
| `PlayerYaw` | Поворот головы по горизонтали (градусы) |
| `PlayerPitch` | Наклон головы (−90 вверх, 90 вниз) |
| `PlayerHealth` | Здоровье игрока (0–20) |
| `PlayerFood` | Сытость игрока (0–20) |
| `PlayerXP` | Уровень опыта |
| `PlayerName` | Имя игрока |
| `PlayerMode` | Режим игры: spectator, creative, survival, adventure |
| `PlayerOnGround` | True, если игрок стоит на земле |
| `PlayerInWater` | True, если игрок в воде |
| `PlayerSneaking` | True, если игрок крадётся (Shift) |
| `PlayerBlock` | Блок под ногами игрока |
| `PlayerDimension` | Измерение: overworld, the_nether, the_end |
| `LookX` | X блока под прицелом (или None) |
| `LookY` | Y блока под прицелом (или None) |
| `LookZ` | Z блока под прицелом (или None) |
| `LookBlock` | Название блока под прицелом (или None) |
| `Time` | Время суток 0–23999 (0 — утро, 6000 — полдень, 13000 — ночь) |
| `Day` | Номер игрового дня |
| `IsDay` | True днём |
| `IsNight` | True ночью |
| `Weather` | Погода: "clear", "rain", "thunder" |
| `IsRaining` | True, если идёт дождь |
| `IsThundering` | True, если гроза |
| `MobCount` | Сколько всего мобов в мире |
| `PlayerCount` | Сколько игроков на сервере |
| `Tick` | Тиков с запуска программы (20 в секунду) |

### События песочницы

| Событие | Сигнатура | Описание |
|---|---|---|
| tick | `on_tick()` | Каждый игровой тик (20 раз в секунду). |
| chat | `on_chat(message)` | Игрок написал сообщение в чат. |
| block_break | `on_block_break(x, y, z, block)` | Игрок сломал блок. |
| block_place | `on_block_place(x, y, z, block)` | Игрок поставил блок. |
| mob_death | `on_mob_death(mob, x, y, z)` | Умер моб (mob — тип, например "zombie"). |
| player_move | `on_player_move(x, y, z)` | Игрок перешёл на другой блок. |
| key | `on_key(key)` | Нажата клавиша (когда редактор закрыт): "g", "h", "1", "up", "space"... |

## Справочник: скрипты сервера

### Функции и методы

#### Флаги

**`obj.flag(name, value=…, expire=None)`** — Читает или пишет флаг на игроке, сущности, блоке (location), мире или сервере. expire: "30s", "1h", "7d". Флаги переживают рестарт.

```python
player.flag("coins", player.flag("coins", default=0) + 5)
location.flag("owner", player.uuid)
server.flag("event_on", True, expire="2h")
```

**`obj.has_flag(name)`** — Есть ли флаг (и не истёк ли он).

```python
if not player.has_flag("daily"):
    player.give(Item("emerald", 3))
    player.flag("daily", True, expire="24h")
```

**`obj.unflag(name)`** — Удаляет флаг.

```python
player.unflag("quest.fisher")
```

**`obj.flags(prefix="")`** — Все флаги объекта (с префиксом — только ветка), словарём.

```python
for name, v in player.flags("home.").items():
    player.tell(name)
```

#### Очереди и ожидание

**`wait(ticks | "5s")`** — Пауза без зависания сервера: очередь засыпает, остальной мир идёт дальше.

```python
player.title("3"); wait("1s")
player.title("2"); wait("1s")
player.title("Старт!")
```

**`wait_until(condition, timeout=None)`** — Ждать, пока условие станет истинным (проверка раз в тик).

```python
wait_until(lambda: player.location.y < 50, timeout="30s")
```

**`run(task, delay=0, **args)`** — Запустить задачу в новой очереди (параллельно). Возвращает очередь.

```python
q = run("fireworks_show", where=player.location)
```

**`queue`** — Текущая очередь: queue.id, queue.stop(), queue.player, queue.started.

```python
if player.health < 4:
    queue.stop()
```

**`Cancel`** — Вернуть из обработчика события, чтобы отменить его (как determine cancelled в Denizen).

```python
@on("player places block", in_area="spawn")
def protect(event):
    return Cancel()
```

#### Игрок (сервер)

**`player.tell(text)`** — Сообщение игроку в MiniMessage: <gold>, <bold>, <gradient:red:blue>, <glyph:id>, <click:run_command:/дом>.

```python
player.tell("<green>Привет, <bold>" + player.name)
```

**`player.give / take / has(item, amount=1)`** — Выдать, забрать (True — если хватило) и проверить предметы, в том числе свои (@item).

```python
if player.take("diamond", 3):
    player.give(Item("wand"))
```

**`player.pay / balance(amount)`** — Деньги через Vault: снять (True — если хватило) и узнать баланс.

```python
if player.pay(100):
    player.tell("Куплено")
```

**`player.open(menu, **data)`** — Открыть меню (@menu) или книгу/диалог.

```python
player.open("Shop")
```

**`player.ask(question, timeout="30s")`** — Спросить игрока и дождаться ответа в чате (очередь ждёт).

```python
name = player.ask("Как назовём питомца?")
pet.name = name
```

**`player.title / actionbar / bossbar(text, …)`** — Титры, строка над хотбаром, полоса босса с прогрессом.

```python
bar = player.bossbar("Волна 1", progress=1.0, color="red")
bar.progress = 0.5
```

**`player.permission(node)`** — Есть ли у игрока право (LuckPerms/Vault).

```python
if player.permission("vip"):
    player.give(Item("golden_apple"))
```

**`player.shoot(entity, speed=1)`** — Выпустить снаряд по взгляду: стрела, снежок, огненный шар.

```python
player.shoot("snowball", speed=2)
```

#### Места и области

**`Location(x, y, z, world="world", yaw=0, pitch=0)`** — Точка мира: .add(), .distance(), .block, .center, .above(), .flag(). Как dLocation в Denizen.

```python
spawn = Location(0, 64, 0)
spawn.block = "gold_block"
```

**`Area(name | (loc1, loc2))`** — Область (cuboid): .contains(loc), .players, .blocks(), .fill(). Именованные области задаются /vaylen area и переключателем in_area=.

```python
arena = Area("arena")
for p in arena.players:
    p.tell("Бой начался!")
```

**`Schematic(file)`** — Вставить или сохранить схематику (.schem): по слоям, с поворотом, только для одного игрока.

```python
Schematic("house.schem").paste(player.location, rotate=90, layers_per_tick=1)
```

**`ShowBlock(player, loc, block, duration=None)`** — Блок, который видит только этот игрок (подсказки, призраки построек).

```python
ShowBlock(player, target, "lime_stained_glass", duration="5s")
```

#### Сущности и NPC

**`Spawn(type, location, **props)`** — Сущность с параметрами: name, glowing, ai, health, equipment, model (BetterModel). Возвращает объект: .remove(), .walk_to(), .flag().

```python
z = Spawn("zombie", player.location.add(3, 0, 0), name="<red>Вася", health=40)
```

**`Hologram(location, lines)`** — Голограмма из text_display: строки меняются на лету, можно сделать её видимой только одному игроку.

```python
h = Hologram(loc, ["<gold>Топ рыбаков", "1. Ефим — 12 кг"])
```

**`npc.say / walk_to / look_at / animate(…)`** — Управление NPC: реплика над головой и в чат, ходьба по пути, поворот, анимация модели.

```python
npc.walk_to(Location(10, 64, 5))
npc.animate("wave")
npc.say("Сюда!")
```

#### Сервер

**`Broadcast(text)`** — Сообщение всем игрокам.

```python
Broadcast("<yellow>Турнир начнётся через 5 минут!")
```

**`Players / OnlinePlayer(name=None)`** — Все игроки онлайн или один по нику.

```python
for p in Players():
    p.give(Item("cake"))
```

**`Console(command)`** — Выполнить команду от консоли (права скрипта ограничены настройкой allowed-commands).

```python
Console(f"lp user {player.name} parent add vip")
```

**`tag("<player.name>")`** — Тег в синтаксисе Denizen для тех, кто привык к нему; внутри — то же, что атрибуты объектов.

```python
player.tell(tag("<player.location.world.name>"))
```

### События сервера

| Событие | Описание | Переключатели | В event |
|---|---|---|---|
| `player joins` | Игрок зашёл | first_time | player, message |
| `player quits` | Игрок вышел | — | player, message |
| `player chats` | Игрок пишет в чат | — | player, message |
| `player breaks block` | Игрок сломал блок | block, with, in_area | player, location, block, drops, xp |
| `player places block` | Игрок поставил блок | block, in_area | player, location, block |
| `player right clicks` | ПКМ по блоку или воздуху | block, with, in_area | player, location, block, item, hand |
| `player left clicks` | ЛКМ | block, with | player, location, block, item |
| `player clicks entity` | ПКМ по сущности | entity, with | player, entity, item |
| `player damages entity` | Игрок бьёт сущность | entity, with | player, entity, damage, cause |
| `entity dies` | Сущность умерла | entity, by | entity, killer, drops, xp |
| `player dies` | Игрок умер | cause | player, killer, message, keep_inventory |
| `player respawns` | Игрок возродился | — | player, location |
| `player enters area` | Вошёл в область | area | player, area, from, to |
| `player exits area` | Вышел из области | area | player, area |
| `player walks` | Сменил блок под ногами | in_area | player, from, to |
| `player jumps` | Прыжок | in_area | player |
| `player sneaks` | Присел / встал | — | player, sneaking |
| `player swaps hands` | F — сменить руки | with | player, main, off |
| `player drops item` | Выбросил предмет | item | player, item, entity |
| `player picks up item` | Подобрал предмет | item | player, item, entity |
| `player consumes` | Съел / выпил | item | player, item |
| `player crafts` | Скрафтил | item | player, item, recipe |
| `player fishes` | Рыбалка | state | player, hook, caught, state |
| `player opens inventory` | Открыл контейнер | type | player, inventory, location |
| `player clicks in inventory` | Клик в инвентаре | menu, slot | player, inventory, slot, item, click |
| `player changes world` | Сменил мир | to | player, from, to |
| `player levels up` | Новый уровень опыта | — | player, level |
| `entity spawns` | Сущность появилась | entity, reason, in_area | entity, location, reason |
| `entity explodes` | Взрыв | entity | entity, location, blocks |
| `block grows` | Растение выросло | block | location, block |
| `redstone changes` | Сигнал редстоуна | block | location, old, new |
| `weather changes` | Смена погоды | to | world, weather |
| `time changes` | Наступило время суток | hour | world, time |
| `server starts` | Сервер запущен | — | — |
| `script reloads` | Скрипты перезагружены | — | — |

### Объекты и теги

| Объект | Описание | Атрибуты |
|---|---|---|
| `player` | Игрок онлайн | name, uuid, location, health, food, level, gamemode, inventory, item_in_hand, world, is_op, ping, target_block, target_entity, flag(), permission() |
| `location` | Точка мира | x, y, z, world, yaw, pitch, block, biome, light, center, above(n), below(n), add(dx,dy,dz), distance(other), flag() |
| `entity` | Сущность | type, uuid, location, health, name, alive, ai, glowing, passengers, vehicle, flag(), remove(), teleport(), walk_to() |
| `item` | Предмет | material, amount, name, lore, model, enchantments, durability, script_id, flag(), with(**changes) |
| `world` | Мир | name, time, weather, players, spawn, difficulty, seed, flag() |
| `area` | Область | name, min, max, world, players, entities, contains(loc), blocks(), fill(block) |
| `npc` | NPC скрипта | name, location, step(player), say(text), walk_to(loc), look_at(target), animate(name), skin |
| `server` | Сервер | players, tps, worlds, time, flag(), online(name) |
| `queue` | Очередь скрипта | id, player, started, script, stop() |
| `event` | Событие в обработчике | player, location, block, item, entity, cancel(), + своё у каждого события (см. список) |

## Примеры

Первые два — целые игры с меню и магазином. Скопируй в редактор `/sandbox` или открой через «Примеры» на сайте. Полные тексты лежат в папке `examples/`.

| Файл | Пример | Описание |
|---|---|---|
| [`ore_hunter.vln`](examples/ore_hunter.vln) | ⛏ Игра: Охотник за рудой | Кликер в меню-сундуке: улучшения, магазин, рекорд во флаге. |
| [`wave_arena.vln`](examples/wave_arena.vln) | ⚔ Игра: Арена волн | 20 волн мобов, боссы, магазин между волнами, клавиша F. |
| [`server_1.vln`](examples/server_1.vln) | Приветствие и первый вход | Событие, флаг, MiniMessage, ожидание. |
| [`server_2.vln`](examples/server_2.vln) | Дома игрока: /сетдом и /дом | Команды, флаги с вложенными именами, подсказки Tab. |
| [`server_3.vln`](examples/server_3.vln) | Посох огня | Свой предмет с моделью из пака, событие с переключателем with=, перезарядка флагом. |
| [`server_4.vln`](examples/server_4.vln) | NPC с заданием | Шаги диалога, проверка предметов, награда, память о выполнении. |
| [`server_5.vln`](examples/server_5.vln) | Меню-магазин | Сундук-меню, оплата через Vault, фон глифом Nexo. |
| [`server_6.vln`](examples/server_6.vln) | Паркур с таймером | Области, вход и выход, очереди, рекорды в данных. |
| [`server_7.vln`](examples/server_7.vln) | Ежедневная награда | Флаг со сроком, кликабельный текст. |
| [`server_8.vln`](examples/server_8.vln) | Защита спавна | Отмена события по области, исключение для админов. |
| [`server_9.vln`](examples/server_9.vln) | Песочница: домик и фейерверк | Песочница: свой участок, функции из мода «Кодинг-мир». |
| [`server_10.vln`](examples/server_10.vln) | Турнир каждый час | Таймер, полоса босса для всех, итоги. |

## Версии

Плагин собирается на самом старом API и обходит различия версий (атрибуты, модели предметов, реестры) сам. На каждой версии ниже запускался набор из 31 проверки на живом сервере.

| Версия | Самопроверка | Игрок-бот | Примечание |
|---|---|---|---|
| 1.21.1 | 31 / 31 | песочница, меню, игры | модели предметов по id — число custom_model_data |
| 1.21.4 | 31 / 31 | — | модели по id (item_model) работают |
| 1.21.11 | работает на FishPvP | живые игроки | основной сервер |
| 26.3 | 31 / 31 | — | Java 25 |
| 1.21.2 – 1.21.10, 26.1 – 26.2 | не гонялись отдельно | — | тот же API — должны работать |

## Команды и права

| Команда | Что делает | Право |
|---|---|---|
| `/sandbox` · `/sb` | Свой участок и ссылка на редактор. `stop`, `clear`, `home`, `leave`, `run`. | `vaylen.sandbox` (у всех) |
| `/vaylen edit <скрипт>` | Открыть скрипт в веб-редакторе (ссылка как у /lp editor): подсветка, подсказки, ошибки по строкам, сохранение — и сервер перечитает скрипт. | `vaylen.admin` |
| `/vaylen reload [скрипт]` | Перечитать все скрипты или один, без рестарта. | `vaylen.admin` |
| `/vaylen list` | Скрипты, их команды, события, предметы и NPC — с ошибками, если есть. | `vaylen.admin` |
| `/vaylen debug <скрипт> [on\|off]` | Подробный вывод: какие события сработали, что вернул обработчик, время выполнения. | `vaylen.admin` |
| `/vaylen queues` | Живые очереди (кто ждёт и чего) с кнопкой «остановить». | `vaylen.admin` |
| `/vaylen area <имя> [pos1\|pos2\|show]` | Задать именованную область для in_area= и Area(). | `vaylen.admin` |
| `/vaylen npc <скрипт> [create\|remove\|move]` | Поставить NPC со скриптом там, где стоишь. | `vaylen.admin` |
| `/vaylen sandbox` | Свой участок-песочница: функции песочницы только в его границах. | `vaylen.sandbox` |
| `/vaylen run <код>` | Выполнить одну строку (для быстрых проверок). | `vaylen.admin` |

## Безопасность

**Песочница:**
- Всё только на своём участке: x и z от −32 до 31, y от −16 до 159.
- Чужие участки ломать нельзя; время и погода меняются только у тебя.
- До 200 мобов на участок, взрывы ломают только твои блоки.
- Нет доступа к файлам, сети и Java — только функции VAYLEN.

**Лимиты:**
- Очередь работает не больше 2 мс за тик, остальное — в следующем тике.
- Бесконечный цикл сам уступает тики — сервер не зависает.
- Большие заливки ставятся частями по 4096 блоков.
- Ошибка в одном скрипте не трогает остальные.

## Частые вопросы

**VAYLEN — это Python?**

Свой язык с синтаксисом Python 3: функции, классы, декораторы, генераторы списков, f-строки, try/except. Знаешь Python — знаешь VAYLEN. Нет сторонних библиотек и доступа к системе, зато есть объекты сервера, очереди, флаги и меню.

**Чем отличается от мода «Кодинг-мир»?**

Мод нужен каждому игроку и работает только в одиночке на Forge 1.20.1. VAYLEN — плагин сервера: игрокам ничего ставить не надо, редактор — на сайте, все функции мода есть в песочнице, плюс меню, флаги и скрипты сервера.

**А если я привык к Denizen?**

World-скрипт — это `@on`, task — `@task`, command — `@command`, inventory — `Menu()`. Теги можно писать как раньше через `tag("<player.name>")`.

**Сохраняется ли прогресс в играх?**

Да: флаги на игроке (`player.flag("ключ", значение)`) переживают перезапуск программы и сервера, могут истекать по времени (`expire="24h"`).

**Почему не работает Ctrl+Space?**

Иногда это сочетание забирает Windows. В редакторе есть запасные: Tab после начатого слова и Ctrl+`.`. Подсказка ещё появляется сама после `(`, `,` и кавычки.

---

VAYLEN · язык и плагин для Paper 1.21–26.3 · идеи из мода «Кодинг-мир» и Denizen. Неофициальный проект, не связан с Mojang и Microsoft.
