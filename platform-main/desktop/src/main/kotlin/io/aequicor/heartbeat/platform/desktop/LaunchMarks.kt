package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.launcher.LaunchTimeline

/**
 * Метки времени запуска приложения в общий протокол лаунчера ([LaunchTimeline]): файл
 * `launch-timeline-<pid>@<старт JVM>.jsonl` в логах данных собирает метки селектора (`selector.*`), лаунчера
 * (`launcher.*`) и приложения (`app.*`) одного запуска; измерение времени до окна, см. Этап 0 нативного лончера.
 * Запись не бросает исключений: протокол сам отключается при сбое, запуск не страдает.
 */
internal fun markLaunch(stage: String) {
    LaunchTimeline.mark(stage)
}
