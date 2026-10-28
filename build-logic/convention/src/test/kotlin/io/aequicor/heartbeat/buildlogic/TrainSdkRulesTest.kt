package io.aequicor.heartbeat.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrainSdkRulesTest {
    private val pkg = "io/aequicor/heartbeat/buildlogic/sdkfixture"

    private fun classBytes(name: String): ByteArray =
        assertNotNull(javaClass.classLoader.getResourceAsStream("$pkg/$name.class"), name).use { it.readBytes() }

    private fun rules(vararg names: String): String = sdkKeepRules(names.asSequence().map(::classBytes))

    /** The body of the `-keep` rule of [className] (or `null` when there is none). */
    private fun rule(text: String, className: String): String? {
        val header = "class io.aequicor.heartbeat.buildlogic.sdkfixture.$className"
        val start = text.lineSequence().indexOfFirst { it.endsWith(header) || it.endsWith("$header {") }
        if (start < 0) return null
        return text.lineSequence().drop(start).takeWhile { it != "}" }.joinToString("\n")
    }

    @Test
    fun `public members are kept and internal ones are not`() {
        val api = assertNotNull(rule(rules("Api"), "Api"))
        listOf("getVisible()", "getMutable()", "setMutable(java.lang.String)", "shown(int)", "shown\$default", "inlined()")
            .forEach { assertTrue(it in api, "$it in\n$api") }
        assertFalse("getHidden" in api, api)
        assertFalse("hiddenFun" in api, api)
        assertFalse("InternalMutable" in api, api)
        assertFalse("secret" in api, api)
    }

    @Test
    fun `published api stays`() {
        val api = assertNotNull(rule(rules("Api"), "Api"))
        assertTrue("published()" in api, api)
        assertTrue("getPublishedProperty()" in api, api)
    }

    @Test
    fun `protected members and constants are kept`() {
        val api = assertNotNull(rule(rules("Api"), "Api"))
        assertTrue("inherited()" in api, api)
        assertTrue("*** Companion;" in api, api)
        assertTrue("staticFun()" in api, api)
        val companion = assertNotNull(rule(rules("Api\$Companion"), "Api\$Companion"))
        assertTrue("staticFun()" in companion, companion)
    }

    @Test
    fun `internal classes are dropped unless published`() {
        assertEquals(null, rule(rules("HiddenClass"), "HiddenClass"))
        assertNotNull(rule(rules("PublishedClass"), "PublishedClass"))
    }

    @Test
    fun `objects enums and interfaces are kept`() {
        val singleton = assertNotNull(rule(rules("Singleton"), "Singleton"))
        assertTrue("*** INSTANCE;" in singleton && "call()" in singleton, singleton)
        assertFalse("<init>" in singleton, singleton)
        val mode = assertNotNull(rule(rules("Mode"), "Mode"))
        assertTrue("values()" in mode && "valueOf(java.lang.String)" in mode && "*** On;" in mode, mode)
        assertTrue("act()" in assertNotNull(rule(rules("Contract"), "Contract")))
    }

    @Test
    fun `file facades keep public declarations only`() {
        val facade = assertNotNull(rule(rules("FixtureKt"), "FixtureKt"))
        assertTrue("topLevel(java.lang.String)" in facade && "getTopLevelProperty()" in facade, facade)
        assertFalse("hiddenTopLevel" in facade, facade)
        // the hidden overload shares the name: the public one still keeps its `$default` entry point
        assertTrue("overloaded\$default(java.lang.String,int,int,java.lang.Object)" in facade, facade)
    }

    @Test
    fun `rules are deterministic`() {
        val names = arrayOf("Api", "Singleton", "FixtureKt", "Mode")
        assertEquals(rules(*names), rules(*names.reversedArray()))
    }

    @Test
    fun `descriptor parameters are converted to java types`() {
        assertEquals("", descriptorParameters("()V"))
        assertEquals("int,java.lang.String[],long[][]", descriptorParameters("(I[Ljava/lang/String;[[J)Z"))
    }
}
