package com.anderson.onedrivesync.update

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Semver precedence with pre-release support, one case per row in the test report. */
@RunWith(Parameterized::class)
class VersionTest(
    private val current: String,
    private val candidate: String,
    private val expected: Boolean,
) {

    @Test
    fun `isNewerVersion follows semver precedence`() {
        assertEquals("$current -> $candidate", expected, isNewerVersion(current, candidate))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} -> {1} = {2}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf("1.0.0", "1.0.0", false),
            arrayOf("1.0.0", "1.0.1", true),
            arrayOf("1.0.0-beta.1", "1.0.0", true),
            arrayOf("1.0.0", "1.0.0-beta.1", false),
            arrayOf("2.0", "10.0", true),
            arrayOf("v1.0.0", "v1.2.0", true),
            arrayOf("1.0.0", "v1.0.1", true),
            arrayOf("v2.0.0", "2.0.0", false),
            arrayOf("1.0.0", "1.1.0", true),
            arrayOf("1.0.0", "2.0.0", true),
            arrayOf("1.0.0-alpha", "1.0.0-beta", true),
            arrayOf("1.0.0-beta.2", "1.0.0-beta.10", true),
            arrayOf("1.0.0-beta.10", "1.0.0-beta.2", false),
            arrayOf("1.0.0", "1.0.0-rc.1", false),
            arrayOf("1.0", "1.0.1", true),
            arrayOf("1.0.0+build5", "1.0.0", false),
            arrayOf("1.0.0", "1.0.0+20260101", false),
            arrayOf("1.0.0", "", false),
            arrayOf("", "2.0.0", false),
            arrayOf("garbage", "2.0.0", false),
            arrayOf("1.0.0", "not-a-version", false),
            arrayOf("1.2.3.4", "1.2.3.5", false),
            arrayOf("1.0.0", "1.0.0.1", false),
        )
    }
}
