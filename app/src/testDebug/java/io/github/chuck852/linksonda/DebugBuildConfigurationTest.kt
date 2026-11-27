package io.github.chuck852.linksonda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugBuildConfigurationTest {
    @Test
    fun applicationIdStaysStableAcrossBuilds() {
        assertEquals("io.github.chuck852.linksonda", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun debugBuildEnablesDebugging() {
        assertTrue(BuildConfig.DEBUG)
    }
}
