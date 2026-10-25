package io.aequicor.heartbeat.feature.worktreemode.impl.data

import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Agent-facing refusals and tool schemas of the virtual sandbox tools. */
class VirtualSandboxToolsTest {
    @Test
    fun `coded store errors become refusals with their hint and other errors stay opaque`() {
        val diverged = assertNotNull(sandboxRefusal(IllegalStateException("SandboxBranchDiverged")))
        assertTrue(diverged.isError)
        assertTrue(diverged.text.startsWith("Refused: SandboxBranchDiverged. The local branch moved independently"))
        assertEquals(
            "Refused: InvalidBranchName.",
            assertNotNull(sandboxRefusal(IllegalArgumentException("InvalidBranchName"))).text,
        )
        assertTrue(assertNotNull(sandboxRefusal(IllegalStateException("SandboxConflicted"))).text.length > 30)
        assertNull(sandboxRefusal(IllegalStateException("fatal: unable to access the remote")))
        assertNull(sandboxRefusal(IllegalStateException()))
    }

    @Test
    fun `without the remote toggle the schemas offer neither push nor fetch`() {
        fun properties(isRemote: Boolean, name: String) =
            sandboxSpecs(isRemote).single { it.name == name }.inputSchema["properties"]!!.jsonObject.keys
        assertFalse("push" in properties(false, "sandbox_commit"))
        assertTrue("force" in properties(false, "sandbox_commit"))
        assertFalse("fetch" in properties(false, "sandbox_checkout"))
        assertFalse("fetch" in properties(false, "sandbox_merge"))
        assertTrue("push" in properties(true, "sandbox_commit"))
        assertTrue("fetch" in properties(true, "sandbox_checkout"))
        assertTrue("fetch" in properties(true, "sandbox_merge"))
        assertEquals(sandboxSpecs(true).map { it.name }, sandboxSpecs(false).map { it.name })
        assertFalse(sandboxSpecs(false).any { "push=true" in it.description || "fetch=true" in it.description })
    }
}
