# MythSupport — хэндовер для нового чата

Плагин Minecraft (Paper/**Purpur 1.21.11**, **Java 25**). Автор: fame/noterdam. Пакет `ink.myata.mythsupport`.
Назначение: заменять ванильных мобов на мобов MythicMobs и управлять их спавном/деспавном.

## Стек и сборка
- **Gradle Kotlin DSL**, враппер **внутри проекта** (`./gradlew build`). Враппер указывает на **Gradle 9.1.0** — это важно: на Java 25 более старый Gradle (8.14.x) падает с ошибкой вида `25.0.4.1`.
- Зависимости (`compileOnly`): `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`, `io.lumine:Mythic-Dist:5.7.0` (версию MythicMobs сверять на mvn.lumine.io под сервер).
- Репозитории: papermc, lumine, mavenCentral.
- Результат: `build/libs/mythsupport-1.0.0.jar` → в `plugins/` рядом с MythicMobs и ModelEngine.
- `plugin.yml`: `depend: [MythicMobs, ModelEngine]`, команда `/mythsupport reload` (право `mythsupport.admin`).

## ВАЖНО: ничего не удалось скомпилировать/протестировать
Среда прошлого чата блокировала прокси-сервером репозитории PaperMC, Lumine и services.gradle.org (403), а JDK 25 там не было (только 21). Поэтому:
- Код написан, но **не проверен компиляцией** против Paper/MythicMobs API.
- Первым делом в новом чате (или локально у пользователя): `./gradlew build`, разобрать ошибки компиляции.
- **Проверить предположения по MythicMobs 5.x API** (возможные источники ошибок):
  - `MythicBukkit.inst().getMobManager().getMythicMob(String)` → `Optional<MythicMob>`
  - `MythicMob.spawn(AbstractLocation, double)` → `ActiveMob`
  - `BukkitAdapter.adapt(org.bukkit.Location)` → `AbstractLocation`
  - `activeMob.getEntity().getBukkitEntity()` → `org.bukkit.entity.Entity`
  Если имена методов отличаются в версии пользователя — поправить здесь.
- `Bukkit.getCurrentTick()` — используется как счётчик тиков (есть в Paper/Purpur).

## Файлы
- `src/main/java/ink/myata/mythsupport/MythSupportPlugin.java` — enable/disable, запуск фонового спавнера по таймеру, команда reload.
- `AmbientSpawner.java` — `BukkitRunnable`: держит `count` мобов в радиусе вокруг игрока, деспавнит по таймеру. Метит мобов через PersistentDataContainer ключом `mythsupport:ambient`.
- `EggSuppressListener.java` — яйца призыва (1 яйцо = 1 спавн) + подавление ванильного спавна.
- `src/main/resources/config.yml` — конфиг (ниже).
- `src/main/resources/plugin.yml`.

## Модель поведения (текущая)
1. **Фоновый спавнер** (`ambient-spawner`): каждые `tick-interval` тиков держит до `count` мифик-мобов из `mobs` в радиусе `radius-chunks` вокруг каждого игрока; подсыпает не более `max-spawn-per-cycle` за цикл, не ближе `min-distance`; моб удаляется через `despawn-after` тиков после того, как рядом (в радиусе) не осталось игроков. Мобы помечаются PDC-тегом, поэтому переживают reload (счётчик деспавна при этом сбрасывается).
2. **Подавление ванили** (`suppress-vanilla`): список `EntityType`, чей natural/спавнерный спавн отменяется (яйца и CUSTOM не трогаются).
3. **Яйца призыва** (`egg-replacements`): карта `EntityType -> список мифик-мобов`, рандом из списка, трата 1 яйца в выживании/приключении, в креативе без лимита. Эти мобы НЕ учитываются спавнером и не деспавнятся.

## Текущий config.yml (дефолт)
```yaml
worlds: []
level: 1
debug: false
ambient-spawner:
  enabled: true
  radius-chunks: 4
  count: 20
  min-distance: 12
  tick-interval: 20
  max-spawn-per-cycle: 5
  despawn-after: 200
  mobs: [cprowler_em, ElectricCreeper, FlashCreeper, IlussionCreeper, new_creeper, SporeCreeper, UmberCreeper]
suppress-vanilla: [ZOMBIE, CREEPER]
egg-replacements:
  ZOMBIE: [cprowler_em]
  CREEPER: [ElectricCreeper, FlashCreeper, IlussionCreeper, new_creeper, SporeCreeper, UmberCreeper]
```

## Имена мифик-мобов (из конфигов MythicMobs пользователя — лежат в plugins/MythicMobs/Mobs/, НЕ в этом плагине)
- Зомби-босс: `cprowler_em` (требует ModelEngine-модель `em_darklurker`, предмет `imboots_cprowler`, кучу скиллов — см. его файл).
- Криперы (внутренние имена именно такие): `ElectricCreeper`, `FlashCreeper`, `IlussionCreeper` (с двумя «s»!), `new_creeper` (нижний регистр), `SporeCreeper`, `UmberCreeper`.

## История/эволюция (чтобы понимать, почему так)
1. Началось как «при спавне зомби надевать модельку» → вышло в плагин-мост: отмена ванильного спавна + спавн мифик-моба через API.
2. Обобщили на карту «ванильный тип → список мифик-мобов» (рандом). Добавили криперов.
3. Починили бесконечное яйцо в выживании (яйца вынесены в `PlayerInteractEvent`, трата вручную).
4. Был параметр `chance` + `remove-on-skip` — **заменён** на фоновый спавнер по количеству в радиусе + деспавн по таймеру (последняя правка, этот код).

## Прочее, что обсуждали (не в плагине)
- Зомби горят на солнце: у моба в MythicMobs `Options.PreventSunburn: false`.
- Спавн-рейт ядра Purpur: `bukkit.yml` (`spawn-limits.monsters`, `ticks-per.monster-spawns`), `spigot.yml` (`mob-spawn-range`), `config/paper-world-defaults.yml` (`entities.spawning` + despawn-ranges). Перезапуск, не /reload.
- Спам в консоли `Named entity ... died` — это Paper логирует смерть именованных сущностей (у мобов есть Display). Не от плагина. Отключается настройкой Paper `log-named-deaths` (точное место в paper-world-defaults так и не подтвердили — проверить грепом по `named` в конфиге).
- «Белая вспышка на весь экран» от флеш-крипера: через ItemsAdder глиф (font_image) в `sendtitle` — серверно. Core/post-шейдеры в пак через IA добавить можно, но триггерить по событию на конкретного игрока нельзя без клиентского мода (у пользователя есть Oculus Fabric-мод) или плагина ScreenShader.

## TODO / на проверку в новом чате
1. Собрать `./gradlew build`, починить возможные несовпадения MythicMobs API.
2. Проверить на сервере: держится ли ~count мобов в радиусе; деспавнятся ли после ухода; тратится ли 1 яйцо в выживании.
3. Возможные улучшения: отдельный `count`/`mobs` на мир или на биом; спавнить только в темноте/ночью; лимит глобально, а не на игрока; очистка наших мобов при выгрузке чанка.
