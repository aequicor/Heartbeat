package io.aequicor.heartbeat.selector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Точка входа установленного приложения: выбирает слот лаунчера и запускает его ({@link SlotLauncher}). Слот, который
 * не удалось даже подготовить, отвергается, и в том же процессе запускается следующий: подтверждённый слот, затем
 * встроенный в установщик лаунчер (jar по пути {@value #EMBEDDED}). Ошибка лаунчера или приложения после передачи
 * управления не перехватывается: она засчитывается как пробный запуск без подтверждения при следующем старте.
 *
 * <p>Слоты меняет только владелец ({@link Selector#becomeOwner()}); вспомогательные процессы того же исполняемого
 * файла (первый аргумент начинается с {@code --}) и второй экземпляр читают слоты и ничего не пишут.
 */
public final class SelectorMain {
    /** Системное свойство для лаунчера: запущенный слот пробный и не подтверждён. */
    static final String TRIAL = "heartbeat.slot.trial";
    /** Лаунчер ставит его перед отказом: операция с данными отложена до подтверждённого лаунчера, слот не виноват. */
    static final String DEFERRED = "heartbeat.slot.deferred";
    /** Путь к jar встроенного лаунчера: он лежит в установщике файлом, а не на classpath. */
    static final String EMBEDDED = "heartbeat.selector.embedded";

    private SelectorMain() {
    }

    public static void main(String[] args) throws Exception {
        Selector selector = new Selector(SelectorPaths.slotsDir(), trustedKeys());
        boolean owner = !isAuxiliary(args) && selector.becomeOwner();
        run(selector, args, SelectorAgent.instrumentation(), embeddedJar(), owner);
    }

    static void run(Selector selector, String[] args, java.lang.instrument.Instrumentation inst, Path embedded,
                    boolean owner) throws Exception {
        Set<String> failed = new HashSet<>();
        while (true) {
            Choice choice = selector.choose(failed);
            Path jar = choice.kind() == Choice.Kind.EMBEDDED ? embedded : choice.jar();
            if (jar == null) {
                throw new IllegalStateException("No launcher to start: slots are empty and " + EMBEDDED + " is not set");
            }
            // a trial launcher must not touch irreversible data (DB restore, migration) until it is confirmed
            System.setProperty(TRIAL, Boolean.toString(owner && choice.trial()));
            System.clearProperty(DEFERRED);
            try {
                SlotLauncher.launch(jar, args, inst, confirmation(selector, choice, owner), owner ? stager(selector) : null);
                return;
            } catch (SlotLauncher.NotStartedException e) {
                if (choice.kind() == Choice.Kind.EMBEDDED) {
                    throw e;
                }
                failed.add(choice.slot());
                if (e.permanent()) {
                    System.err.println("Slot " + choice.slot() + " is abandoned: " + e.getMessage());
                    update(() -> selector.abandon(choice.slot()));
                } else {
                    System.err.println("Slot " + choice.slot() + " is skipped for this start: " + e.getMessage());
                }
            } catch (Exception e) {
                if (!(choice.trial() && Boolean.getBoolean(DEFERRED))) {
                    throw e;
                }
                // a trial launcher postponed an irreversible data operation: it did not fail, the confirmed launcher
                // does that operation, so the trial slot is released without a mark and the next launcher starts
                System.err.println("Slot " + choice.slot() + " waits for a confirmed launcher: " + e.getMessage());
                failed.add(choice.slot());
                update(() -> selector.release(choice.slot()));
            }
        }
    }

    /** Запись состояния слотов не должна мешать запуску: сбой пишется в журнал. */
    private static void update(SlotUpdateAction action) {
        try {
            action.run();
        } catch (IOException | RuntimeException e) {
            System.err.println("Slot state was not updated: " + e);
        }
    }

    private interface SlotUpdateAction {
        void run() throws IOException;
    }

    /** Приём предложений нового лаунчера: сбой записи в слот пишется в журнал и не мешает работе. */
    private static Predicate<Map<String, String>> stager(Selector selector) {
        return request -> {
            try {
                return selector.stageUpdate(request);
            } catch (IOException | RuntimeException e) {
                System.err.println("Launcher update failed: " + e);
                return false;
            }
        };
    }

    private static Runnable confirmation(Selector selector, Choice choice, boolean owner) {
        if (!owner || !choice.trial()) {
            return () -> { };
        }
        return () -> {
            try {
                selector.confirm();
            } catch (IOException | RuntimeException e) {
                // the launch is counted as unconfirmed, which is the safe direction
                System.err.println("Slot " + choice.slot() + " was not confirmed: " + e);
            }
        };
    }

    /** Зашитые ключи; если их не прочитать, ни один слот не доверенный и работает встроенный лаунчер. */
    private static TrustedKeys trustedKeys() {
        try {
            return TrustedKeys.builtIn();
        } catch (IOException e) {
            System.err.println("Trusted keys are unavailable, only the embedded launcher starts: " + e);
            return TrustedKeys.of(java.util.List.of());
        }
    }

    private static Path embeddedJar() {
        String value = System.getProperty(EMBEDDED);
        return value == null ? null : Path.of(value);
    }

    /** Вспомогательный процесс того же исполняемого файла (воркер, проверка): первый аргумент — флаг {@code --…}. */
    static boolean isAuxiliary(String[] args) {
        return args.length > 0 && args[0].startsWith("--");
    }
}
