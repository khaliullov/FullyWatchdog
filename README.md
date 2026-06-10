# FullyWatchdog

Минимальный watchdog APK для YaOS / Android TV: через `JobScheduler` регулярно проверяет, что поверх экрана открыт Fully Kiosk Browser, и при необходимости запускает `de.ozerov.fully/.FullyActivity`.

## Root (Права суперпользователя)

Root-права **желательны, но не обязательны**.

Watchdog может использовать `su` (если доступен) для:
- Принудительной остановки зависших процессов (`am force-stop`).
- Гарантированного запуска Activity из фона в обход ограничений YaOS (`am start`).

Если Root отсутствует, приложение полагается на стандартные механизмы:
- `JobScheduler`
- `UsageStatsManager`
- `BOOT_COMPLETED`
- `startActivity` API

## Особенности работы на YaOS / YaOS Limitations

Этот watchdog адаптирован для устройств YaOS, где стандартные методы киоска (Device Owner, Accessibility) часто ограничены.

**Ключевые отличия:**
- **JobScheduler вместо Foreground Service**: JobScheduler показал себя более надежным на YaOS, чем долгоживущий Foreground Service. Он устойчив к системным оптимизациям и корректно перезапускается системой.
- **Двухуровневая детекция (ActivityManager + UsageEvents)**: Простая проверка наличия процесса неэффективна (процесс может «висеть» без окна). Используется каскад проверок:
    1. `ActivityManager.getRunningTasks()` (если доступно)
    2. Fallback на `UsageEvents`
  Это обеспечивает подтверждение того, что окно Fully действительно на переднем плане, и улучшает совместимость с разными версиями Android TV и YaOS.
- **Защита от «Шторма»**: Двухуровневый лимит перезапусков (Soft: 5 попыток / Hard: 2 попытки с force-kill) предотвращает бесконечный цикл загрузки.
- **System Whitelist**: Автоматическое игнорирование системных окон YaOS (Настройки, Лаунчер, Голосовой помощник), чтобы не прерывать действия пользователя или системные апдейты.
- **Jitter (Джиттер)**: Добавлен случайный разброс времени запуска (до 5с) для снижения пиковой нагрузки.

## Известные сценарии отказа (Known Failure Mode)

**Процесс Fully может быть жив, когда FullyActivity отсутствует.**

Пример в YaOS:
- Процесс `de.ozerov.fully` в состоянии `foreground` -> запущен.
- Но `de.ozerov.fully/.FullyActivity` -> не отображается (перекрыта лаунчером или вылетела).

Реальный кейс, зафиксированный на YaOS:
```text
Process:
de.ozerov.fully:foreground

Foreground Activity:
com.spocky.projengmenu/.ui.home.MainActivity
```
В этом состоянии Fully запущен в памяти, но режим киоска фактически нарушен. Watchdog детектирует отсутствие нужной Activity и принудительно возвращает её на передний план.

Этот watchdog проверяет именно **foreground activity**, а не просто наличие процесса в памяти. Это критически важное наблюдение для стабильной работы киоска на YaOS.

## Требования

- JDK/Gradle через Android Studio или bundled Gradle wrapper.
- Android SDK, установленный в `local.properties`.
- Для первого запуска Gradle может понадобиться интернет.

## Сборка

### GitHub Actions (Автоматическая сборка)
В репозитории настроен GitHub Action, который автоматически собирает APK при каждом пуше в ветку `main`.
Вы можете найти готовые артефакты в разделе **Actions** вашего репозитория на GitHub.

### Как создать новый Release
Для автоматического создания релиза на GitHub с прикрепленным APK (рекомендуемый способ):
1. Создайте тег версии (например, `v1.0.1`):
   ```bash
   git tag v1.0.1
   ```
2. Отправьте тег в репозиторий:
   ```bash
   git push origin v1.0.1
   ```
После этого GitHub Actions автоматически соберет проект и создаст страницу релиза с готовым APK в разделе **Releases**.

### Быстрая debug-сборка
```bash
./gradlew :app:assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

### Быстрая release-сборка
Без настройки подписи Gradle соберёт unsigned APK:
```bash
./gradlew :app:assembleRelease
```
APK: `app/build/outputs/apk/release/app-release-unsigned.apk`

### Подписанный Release через Android Studio
1. Открой проект в Android Studio.
2. Выбери `Build` → `Generate Signed App Bundle / APK`.
3. Выбери `APK`.
4. Создай или укажи keystore.
5. Выбери variant `release`.
6. Собери APK (обычно в `app/release/app-release.apk`).

### Подписанный Release через CLI
Создай keystore один раз:
```bash
keytool -genkeypair -v -keystore fully-watchdog-release.jks -alias fully-watchdog -keyalg RSA -keysize 2048 -validity 10000
```
Затем подпиши unsigned APK:
```bash
./gradlew :app:assembleRelease
zipalign -p 4 app/build/outputs/apk/release/app-release-unsigned.apk app-release-aligned.apk
apksigner sign --ks fully-watchdog-release.jks --ks-key-alias fully-watchdog --out app-release-signed.apk app-release-aligned.apk
apksigner verify --verbose app-release-signed.apk
```

## Установка на ТВ

```bash
adb install -r app-release-signed.apk
adb shell appops set su.leandr.watchdog.fully GET_USAGE_STATS allow
adb shell appops set su.leandr.watchdog.fully SYSTEM_ALERT_WINDOW allow
adb shell appops set su.leandr.watchdog.fully WRITE_SETTINGS allow
adb shell monkey -p su.leandr.watchdog.fully 1

# Важная проверка после установки:
adb shell appops get su.leandr.watchdog.fully GET_USAGE_STATS
# Ожидаемый результат:
# GET_USAGE_STATS: allow
```

## Диагностика и тесты

- **Логирование в файл**: Приложение пишет детальный лог в `/sdcard/Android/data/su.leandr.watchdog.fully/files/watchdog.log`. 
  Лог ротируется при достижении 1МБ и **автоматически полностью очищается раз в неделю** для экономии места.
  Путь к логу отображается в нижней части настроек MainActivity.
- **Принудительный запуск Job**: 
  `adb shell cmd jobscheduler run -f su.leandr.watchdog.fully 1001`
- **Проверка запланированных задач**: 
  `adb shell dumpsys jobscheduler | grep -A 20 su.leandr.watchdog.fully`
- **Состояние в реальном времени**: MainActivity показывает **Watchdog Health** (`Healthy`, `Stale` или `Stalled`), причину последнего запуска и статистику.

## Интерфейс приложения

Главный экран приложения (MainActivity) обновляется автоматически каждые 5 секунд:
- **Watchdog Health**: Статус планировщика. Если задача не запускалась вовремя (интервал + джиттер + запас), статус сменится на `Stale`.
- **Reason**: Причина последнего срабатывания (например, `PERIODIC_CHECK` или `RECOVERY`).
- **Statistics**: 
    - `OK`: количество успешных проверок.
    - `Recoveries`: сколько раз watchdog возвращал Fully на передний план.
    - `Soft Relaunches`: плановые перезапуски для профилактики утечек WebView.
    - `Crashes`: детекция холодного старта после падения процесса.
- **Last Action**: Текст последней команды, выполненной через Broadcast.

## Чек-лист восстановления (Recovery Checklist)

Если watchdog не возвращает Fully на экран:
1. Проверьте, что ADB включен и доступен.
2. Проверьте разрешение `GET_USAGE_STATS`: 
   `adb shell appops set su.leandr.watchdog.fully GET_USAGE_STATS allow`
3. Убедитесь, что Job запланирован: 
   `adb shell dumpsys jobscheduler | grep su.leandr.watchdog.fully`
4. Проверьте правильность имени пакета Fully в `FullyWatchdogConfig.kt`.
5. Запустите watchdog вручную для теста:
   `adb shell cmd jobscheduler run -f su.leandr.watchdog.fully 1001`

## Управление через Broadcast (Kill-switch)

В APK есть защищённый broadcast receiver:

```text
su.leandr.watchdog.fully/.WatchdogControlReceiver
```

Все команды требуют secret token:

```text
fully-watchdog-2580
```

Токен задан в:

```text
app/src/main/java/su/leandr/watchdog/fully/FullyWatchdogConfig.kt
```

Перед production-сборкой лучше заменить `CONTROL_TOKEN` на свой.

- **Выключить watchdog**:
  ```bash
  adb shell am broadcast -n su.leandr.watchdog.fully/.WatchdogControlReceiver -a su.leandr.watchdog.fully.action.DISABLE --es token fully-watchdog-2580
  ```
- **Включить watchdog**:
  ```bash
  adb shell am broadcast -n su.leandr.watchdog.fully/.WatchdogControlReceiver -a su.leandr.watchdog.fully.action.ENABLE --es token fully-watchdog-2580
  ```
- **Переключить состояние watchdog**:

  ```bash
  adb shell am broadcast -n su.leandr.watchdog.fully/.WatchdogControlReceiver -a su.leandr.watchdog.fully.action.TOGGLE --es token fully-watchdog-2580
  ```

- **Запустить проверку сейчас**:

  ```bash
  adb shell am broadcast -n su.leandr.watchdog.fully/.WatchdogControlReceiver -a su.leandr.watchdog.fully.action.RUN_NOW --es token fully-watchdog-2580
  ```

  Команда включает watchdog, если он был выключен, и планирует job с нулевой задержкой.

- **Симуляция утечки памяти (Memory Leak Test)**:

  ```bash
  adb shell am broadcast \
    -n su.leandr.watchdog.fully/.WatchdogControlReceiver \
    -a su.leandr.watchdog.fully.action.RUN_NOW \
    --es token fully-watchdog-2580 \
    --es reason DEBUG:SIMULATE_LEAK
  ```

  Заставляет Watchdog считать, что приложение превысило лимит памяти, что приведет к Hard Kill (`force-stop`) и перезапуску.

- **Принудительно убить и перезапустить Fully (Hard Kill)**:

  ```bash
  adb shell am broadcast -n su.leandr.watchdog.fully/.WatchdogControlReceiver -a su.leandr.watchdog.fully.action.KILL_FULLY --es token fully-watchdog-2580
  ```

  Команда принудительно завершает процесс Fully (`pkill -9` + `am force-stop`) и немедленно инициирует его запуск. Полезно для тестирования механизмов восстановления или при "зависании" контента внутри WebView.

### Изменить настройки watchdog

Например, проверка примерно каждые `30` секунд, deadline `60` секунд, soft relaunch Fully раз в `2` часа:

```bash
adb shell am broadcast \
  -n su.leandr.watchdog.fully/.WatchdogControlReceiver \
  -a su.leandr.watchdog.fully.action.SET_CONFIG \
  --es token fully-watchdog-2580 \
  --el interval_ms 30000 \
  --el override_deadline_ms 60000 \
  --el soft_relaunch_ms 7200000
```

Отключить soft relaunch:

```bash
adb shell am broadcast \
  -n su.leandr.watchdog.fully/.WatchdogControlReceiver \
  -a su.leandr.watchdog.fully.action.SET_CONFIG \
  --es token fully-watchdog-2580 \
  --el soft_relaunch_ms 0
```

### Менять Android `Settings.System`

Обычный APK не может менять `Settings.Secure` и `Settings.Global` без системной подписи или `WRITE_SECURE_SETTINGS`. Но он может менять часть `Settings.System`, если выдать `WRITE_SETTINGS`:

```bash
adb shell appops set su.leandr.watchdog.fully WRITE_SETTINGS allow
```

Пример: отключить автоотключение экрана через `screen_off_timeout = 0`:

```bash
adb shell am broadcast \
  -n su.leandr.watchdog.fully/.WatchdogControlReceiver \
  -a su.leandr.watchdog.fully.action.PUT_SYSTEM_SETTING \
  --es token fully-watchdog-2580 \
  --es setting_key screen_off_timeout \
  --es setting_value 0
```

Пример: поставить таймаут экрана `24` часа:

```bash
adb shell am broadcast \
  -n su.leandr.watchdog.fully/.WatchdogControlReceiver \
  -a su.leandr.watchdog.fully.action.PUT_SYSTEM_SETTING \
  --es token fully-watchdog-2580 \
  --es setting_key screen_off_timeout \
  --es setting_value 86400000
```

Последняя выполненная control-команда отображается в UI в поле `Last action`.

## Полезные настройки (FullyWatchdogConfig.kt)

Главные параметры лежат в:

```text
app/src/main/java/su/leandr/watchdog/fully/FullyWatchdogConfig.kt
```

- `CONTROL_TOKEN` — секретный ключ для управления.
- `DEFAULT_WATCHDOG_INTERVAL_MS` — интервал проверки (30с).
- `MAX_JITTER_MS` — максимальный случайный джиттер (5с).
- `DEFAULT_SOFT_RELAUNCH_INTERVAL_MS` — периодический перезапуск Fully (раз в 4ч).
- `STORM_SOFT_MAX` / `STORM_HARD_MAX` — лимиты попыток восстановления.
- `DEFAULT_FULLY_PACKAGE` / `DEFAULT_FULLY_ACTIVITY_CLASS` — пакет и класс целевого приложения.

## Диагностика

1. **Лог-файл**: `/sdcard/Android/data/su.leandr.watchdog.fully/files/watchdog.log` (ротация 1МБ + еженедельная очистка).
2. **Очередь задач**: `adb shell dumpsys jobscheduler | grep -E "1001|1002" | grep su.leandr.watchdog.fully`
3. **Текущее состояние (prefs)**: `adb shell run-as su.leandr.watchdog.fully cat shared_prefs/fully_watchdog.xml`

## Валидация стабильности на YaOS

На YaOS (Tuvio/M9) система показывает >95% успеха
(из 1400+ проверок за двое суток 1350+ завершились статусом OK).
Использование Root-fallback позволяет гарантированно поднимать Fully Kiosk
даже при агрессивных фоновых ограничениях.

Система протестирована и подтверждена на следующем стеке:

- **Модель ТВ**: Tuvio TD32HFBSV1
- **Платформа/Плата**: TD32HFBCV1.C320Y23-M9-XHM9DSS01
- **ОС**: YaOS (на базе Android TV)
- **Fully Kiosk Browser**: v1.56.2
- **Android WebView**: 113.0.5672.163
