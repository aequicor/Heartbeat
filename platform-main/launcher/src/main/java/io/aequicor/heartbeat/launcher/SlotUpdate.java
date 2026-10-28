package io.aequicor.heartbeat.launcher;

import java.io.IOException;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Предлагает селектору слотов jar лаунчера из активного поезда ({@code kind=launcher}), чтобы следующий запуск работал
 * на нём. Сам лаунчер слоты не меняет: запись в соседний слот, проверка подписи и порога уровня, активация пробного
 * слота — дело селектора ({@code Selector#stageUpdate}), у которого ключи, состояние слотов и право владельца.
 * Связь — JDK-интерфейс {@link Predicate}: загрузчики лаунчера и селектора разные, общих классов у них нет.
 *
 * <p>Запрос — строки: {@value #JAR} (путь к jar в хранилище поезда), {@value #ID}, {@value #VERSION}, {@value #SHA256},
 * {@value #KIND}, {@value #LAUNCHER_API} и {@value #SIGNATURE} (поля записи манифеста). Запись без подписи не
 * предлагается: селектор такую всё равно не примет.
 */
final class SlotUpdate {
    static final String JAR = "jar";
    static final String ID = "id";
    static final String VERSION = "version";
    static final String SHA256 = "sha256";
    static final String KIND = "kind";
    static final String LAUNCHER_API = "launcherApi";
    static final String SIGNATURE = "signature";

    private SlotUpdate() {
    }

    /**
     * Предлагает jar лаунчера поезда {@code train}.
     *
     * @return лаунчер принят в пробный слот; {@code false} — записи нет, она без подписи или селектор отказал
     *         (тот же jar уже установлен, слот занят, подпись или уровень не прошли)
     */
    static boolean offer(TrainStore store, String train, Predicate<Map<String, String>> stager) throws IOException {
        for (TrainManifest.Bundle bundle : store.readManifest(train).bundles()) {
            if (!TrainManifest.KIND_LAUNCHER.equals(bundle.kind())) {
                continue;
            }
            if (bundle.signature() == null) {
                System.err.println("Launcher entry " + bundle.id() + " of train " + train + " is unsigned, skipped");
                return false;
            }
            String sha = TrainManifest.normalizedSha(bundle);
            return stager.test(Map.of(
                JAR, store.jarPath(sha).toString(),
                ID, bundle.id(),
                VERSION, bundle.version(),
                SHA256, sha,
                KIND, bundle.kind(),
                LAUNCHER_API, Integer.toString(bundle.launcherApi()),
                SIGNATURE, bundle.signature()));
        }
        return false;
    }
}
