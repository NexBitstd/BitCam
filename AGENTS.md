# AGENTS.md

## Назначение

Этот репозиторий уже не является single-project модом. Корень теперь только координирует сборку, а реальная логика разделена на общий код и загрузчики.

Агент должен работать с этим проектом как с multi-project Gradle репозиторием.

## Текущая структура

- `build.gradle.kts`
  - корневой агрегатор
  - сам по себе не содержит кода мода
- `settings.gradle.kts`
  - автоматически сканирует `versions/*/version.properties`
  - подключает все найденные version sets без ручного `includeLoaderSet(...)`
  - подключает loader-модули через shared build scripts в `versions/shared/*.gradle.kts`
  - уже подготовлен под PlasmoVoice-style pluginManagement с `gg.essential.defaults` и `gg.essential.multi-version.root`
- `versions/<mc-version>/version.properties`
  - version-local metadata и loader versions
  - сюда нужно класть `minecraft_version`, `fabric_api_version`, `neoforge_version`, `paper_dev_bundle_version` и подобные свойства
- `versions/shared/`
  - общие Gradle build scripts для всех версий
  - `fabric.gradle.kts`, `neoforge.gradle.kts`, `paper.gradle.kts`
  - если меняется логика сборки loader-а, править нужно здесь, а не в `versions/<mc>/<loader>/build.gradle.kts`
- `common/`
  - общий Java-код
  - здесь должна лежать логика, не завязанная на Fabric, NeoForge или Paper API
- `protocol/`
  - общий бинарный протокол control и UDP media пакетов
- `client-common/`
  - общий клиентский media stack
  - захват камеры, JPEG encoding, UDP client, remote frame store
- `server-common/`
  - общий серверный media stack
  - session registry, UDP server, proximity routing
- `versions/1.21.8/fabric/`
  - Fabric-специфичный bootstrap
  - Fabric metadata, signaling runtime, client render/keybind/commands
- `versions/1.21.8/neoforge/`
  - NeoForge-специфичный bootstrap
  - `neoforge.mods.toml`, payload registration, client render/keybind/commands
- `versions/1.21.8/paper/`
  - Paper plugin bootstrap
  - `plugin.yml`, plugin-message signaling, shared UDP server

## Куда класть код

- Общую бизнес-логику класть в `common/src/main/java/...`
- Код, который использует Fabric API, класть только в `versions/<mc>/fabric/...`
- Код, который использует NeoForge API, класть только в `versions/<mc>/neoforge/...`
- Код, который использует Bukkit/Paper API, класть только в `versions/<mc>/paper/...`
- Клиентский код разрешён только в Fabric и NeoForge
- Paper считать только серверной платформой
- Platform interfaces и общие сервисы держать в `common`
- Реализации этих interfaces держать в loader-модулях как adapters
- Signaling packet definitions держать в `protocol`
- Общую media-логику клиента держать в `client-common`
- Общую media-логику сервера держать в `server-common`

## Что не делать

- Не добавлять новый код обратно в старый `src/...`
- Не импортировать loader-specific API в `common`
- Не импортировать Minecraft/Fabric/NeoForge/Paper networking/rendering API в `protocol`, `client-common`, `server-common`
- Не класть клиентский код в Paper-модуль
- Не предполагать, что один и тот же common-код автоматически совместим с разными версиями Minecraft без проверки
- Не менять версии зависимостей наугад: здесь есть связка `Gradle + plugin versions + loader versions`
- Не возвращать version-specific `build.gradle.kts` обратно внутрь `versions/<mc>/<loader>/`, если нет очень сильной причины
- Не поднимать bytecode target выше Java 21 для ветки Minecraft `1.21.8`: текущий проект должен оставаться совместимым с Java 21 runtime
- Не пытаться лечить IntelliJ sync ошибку `DefaultGroovyMethods.collect(...)` даунгрейдом `Gradle` или `Fabric Loom`, если пользователь явно хочет оставаться на актуальном toolchain
- Для текущей связки `Gradle 9.4.0 + Fabric Loom 1.15.4 + ModDevGradle 1.0.11` NeoForge должен убирать свои IDEA run configurations после `afterEvaluate`, иначе IntelliJ sync падает внутри `gradle-idea-ext`

## Как собирать

- Полная сборка:
  - `./gradlew build`
- Печать найденной matrix:
  - `./gradlew printVersionMatrix`
- Сборка текущей фокусной версии:
  - `./gradlew buildCurrentVersion`
- Проверка только Fabric:
  - `./gradlew :mc1_21_8:fabric:build`
- Проверка только NeoForge:
  - `./gradlew :mc1_21_8:neoforge:build`
- Проверка только Paper:
  - `./gradlew :mc1_21_8:paper:build`

Сборка в текущем состоянии проходит через `./gradlew build`.

## IntelliJ IDEA профили

- Shared run configs лежат в `.run/`
- Сейчас добавлены профили для:
  - Fabric client/server `1.21.8`
  - NeoForge client/server `1.21.8`
  - Paper server `1.21.8`
- Эти профили запускают Gradle задачи:
  - `:mc1_21_8:fabric:runClient`
  - `:mc1_21_8:fabric:runServer`
  - `:mc1_21_8:neoforge:runClient`
  - `:mc1_21_8:neoforge:runServer`
  - `:mc1_21_8:paper:runServer`

## Как этим пользоваться сейчас

- Control/signaling канал:
  - `bitcam:control`
- Media transport:
  - отдельный UDP socket, который поднимается серверной частью
- Fabric client:
  - keybind `V` переключает стрим
  - client commands:
    - `/bitcam toggle`
    - `/bitcam cameras`
    - `/bitcam camera <index>`
- NeoForge client:
  - keybind `V` переключает стрим
  - client commands:
    - `/bitcam toggle`
    - `/bitcam cameras`
    - `/bitcam camera <index>`
- Server config:
  - при первом старте создаётся `bitcam-server.properties` в config/data dir платформы
- Client config:
  - при первом старте создаётся `bitcam-client.properties`

## Runtime map

- Fabric:
  - server runtime: `FabricBitCamServerRuntime`
  - client runtime: `FabricBitCamClientRuntime`
- NeoForge:
  - server runtime: `NeoForgeBitCamServerRuntime`
  - client runtime: `NeoForgeBitCamClientRuntime`
- Paper:
  - server runtime: `PaperBitCamServerRuntime`

Если задача касается handshake, UDP routing, frame fragmentation или remote frame assembly, почти наверняка изменения должны идти не в loader-модуль, а в `protocol`, `client-common` или `server-common`.

## Как добавлять новую версию Minecraft

Базовый ручной workflow сейчас такой:

1. Скопировать `versions/1.21.8/` в новую папку, например `versions/1.21.9/`
2. Обновить `versions/1.21.9/version.properties`
3. При необходимости поправить только исходники и metadata/resources внутри `versions/1.21.9/{fabric,neoforge,paper}/`
4. Если нужна новая loader-specific build логика для всех версий, править `versions/shared/*.gradle.kts`
5. При необходимости добавить новые shared `.run` профили под эту версию

Корневые `settings.gradle.kts` и `build.gradle.kts` под новую версию вручную править не нужно: версия подхватится автоматически.

## Текущие ограничения

- Version-specific свойства теперь лежат в `versions/<mc-version>/version.properties`
- Корневая сборка умеет одновременно видеть и собирать несколько версий Minecraft
- `active_minecraft_version` в root `gradle.properties` теперь только helper для задач вроде `buildCurrentVersion`, а не архитектурное ограничение
- Практический максимум для runtime/bytecode этой ветки сейчас Java 21, даже если локально установлены Java 25/26
- Текущий build toolchain этой ветки зафиксирован на `Gradle 9.4.0 + Fabric Loom 1.15.4`; NeoForge IntelliJ sync стабилизируется не сменой версий, а cleanup-патчем `runConfigurations` в `versions/shared/neoforge.gradle.kts`
- Версии Gradle plugins (`fabric-loom`, `moddevgradle`, `paperweight-userdev`, `run-paper`) пока остаются глобальными на весь репозиторий
- В `pluginManagement` уже добавлены upstream-репозитории и plugin ids из PlasmoVoice/Essential toolchain, но полноценный source preprocessing через `gg.essential.multi-version` ещё не включён на subproject-уровне
- Если в будущем разным Minecraft веткам понадобятся несовместимые major-версии самих Gradle plugins, тогда уже придётся либо усложнять build logic, либо подключать Stonecutter/отдельные composite builds
- `common/` сейчас один на весь репозиторий; если между версиями появятся несовместимые отличия даже в "общем" коде, потребуется либо version-specific common, либо preprocessor/Stonecutter
- Текущий video path использует JPEG over UDP. Это baseline implementation, не финальный production codec
- Для dedicated server `udp.host` в `bitcam-server.properties` нужно вручную выставлять в публичный IP/hostname. Значение по умолчанию `127.0.0.1` годится только для локальной разработки
- Cross-loader runtime собирается, но реальные multiplayer compatibility tests между Fabric client <-> Paper server / NeoForge client <-> Fabric server ещё не автоматизированы
- Текстурный billboard renderer реализован отдельно в Fabric и NeoForge; если меняется визуальный формат стрима, нужно синхронно проверить оба renderer path

## Stonecutter

Stonecutter пока не подключён.

Текущая структура уже поддерживает directory-driven multi-version matrix без ручного редактирования root include list и стала ближе к PlasmoVoice за счёт shared loader build scripts.

Stonecutter по-прежнему может понадобиться позже, если:

- начнут расходиться исходники между версиями в одном и том же loader/common коде
- понадобится preprocessor для мелких API-расхождений вместо копирования целых папок версий

Если агенту ставят задачу на настоящую мультиверсионность, нужно сначала решить один из вариантов:

- довключить `gg.essential.multi-version` / upstream Essential preprocessing для конкретного subtree
- подключить Stonecutter
- ввести отдельный `common` на каждую версию
- вынести version-specific API слои из общего кода

## Практическое правило для агента

Если задача не требует конкретного loader API, начинать изменения с `common`.

Если задача касается:

- entrypoint регистрации
- команд платформы
- событий загрузчика
- client-only hooks
- plugin metadata

то изменения почти наверняка должны идти в конкретный loader-модуль, а не в `common`.

## Интерфейсы и adapters

В проекте нужно придерживаться схемы `ports/adapters`.

- `common` задаёт интерфейсы платформы и общий bootstrap
- loader-модули реализуют эти интерфейсы
- entrypoint загрузчика только создаёт adapter и передаёт его в общий код

Общее правило:

- `common` не должен импортировать Fabric, NeoForge или Paper классы
- если сервис в `common` требует платформенную возможность, она должна приходить через interface
- не строить giant-interface "на все случаи"; лучше несколько узких interfaces по ответственности
