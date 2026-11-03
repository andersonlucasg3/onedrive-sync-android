package com.anderson.onedrivesync.core

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Tenant persistence: the stored value is always a single path segment, never blank. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PrefsTenantTest {
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
    }

    @Test
    fun `tenant defaults to consumers`() {
        assertEquals("consumers", prefs.tenant)
    }

    @Test
    fun `tenant is normalized before saving`() {
        prefs.tenant = " meu-tenant/ "
        assertEquals("meu-tenant", prefs.tenant)

        prefs.tenant = "   "
        assertEquals("consumers", prefs.tenant)
    }

    @Test
    fun `normalization removes bars and spaces`() {
        assertEquals("meu-tenant", normalizeTenant(" meu-tenant/ "))
        assertEquals("consumers", normalizeTenant(""))
    }
}
