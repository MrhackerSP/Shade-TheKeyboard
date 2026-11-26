package com.shade.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard: the updater shipped with the template placeholder
 * "OWNER/REPO" for a long time, which made every release check 404 and
 * silently disabled auto-updates. This test fails if that ever comes back.
 */
class UpdaterConfigTest {

    private fun repoFromSource(): String {
        // Read the constant via reflection-free source check is not possible in
        // unit tests; instead assert against the compiled constant exposed here.
        return Updater.repoForTests()
    }

    @Test
    fun repoIsNotTheTemplatePlaceholder() {
        val repo = repoFromSource()
        assertEquals("MrhackerSP/Shade-TheKeyboard", repo)
        assertEquals(false, repo.contains("OWNER"))
    }
}
