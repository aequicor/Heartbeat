package io.aequicor.heartbeat.feature.aistudio.impl.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LearningSignalsTest {
    @Test
    fun `windows encoding failures and mojibake are recognised`() {
        val samples = listOf(
            "UnicodeEncodeError: 'charmap' codec can't encode character '\\u2713' in position 0",
            "java.nio.charset.MalformedInputException: Input length = 1",
            "warning: unmappable character (0xD0) for encoding windows-1252",
            "Привет as cp1252: ÐŸÑ€Ð¸Ð²ÐµÑ‚",
            "Ð\u009fÑ\u0080Ð¸Ð²ÐµÑ\u0082",
            "Gr��e aus K�ln",
        )
        for (sample in samples) {
            assertEquals(setOf(LearningSignal.TextEncoding), detectLearningSignals(sample), sample)
        }
    }

    @Test
    fun `shell dialect and line ending failures are recognised`() {
        assertEquals(
            setOf(LearningSignal.ShellDialect),
            detectLearningSignals("'grep' is not recognized as an internal or external command,"),
        )
        assertEquals(
            setOf(LearningSignal.ShellDialect),
            detectLearningSignals("The token '&&' is not a valid statement separator in this version."),
        )
        assertEquals(
            setOf(LearningSignal.LineEndings),
            detectLearningSignals("./build.sh: line 2: \$'\\r': command not found"),
        )
    }

    @Test
    fun `ordinary output raises nothing and the hint names every problem`() {
        assertEquals(emptySet(), detectLearningSignals("BUILD SUCCESSFUL in 4s\nПривет, мир"))
        val hint = learningHint(setOf(LearningSignal.LineEndings, LearningSignal.TextEncoding))
        assertTrue("text encoding" in hint && "CRLF" in hint && "remember" in hint)
    }
}
