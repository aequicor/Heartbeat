package io.aequicor.heartbeat.selector;

import java.lang.instrument.Instrumentation;

/**
 * Java-агент установленного приложения ({@code -javaagent:selector.jar}). Агент запоминает {@link Instrumentation} и
 * отдаёт его запущенному лаунчеру ({@link SlotLauncher}): jar поезда дописываются в системный загрузчик классов, как в
 * разработке, а сам лаунчер живёт в собственном загрузчике и поэтому заменяем слотом.
 */
public final class SelectorAgent {
    private static volatile Instrumentation instrumentation;

    private SelectorAgent() {
    }

    /** Точка входа агента: запоминает {@link Instrumentation}. */
    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
    }

    static Instrumentation instrumentation() {
        return instrumentation;
    }
}
