package com.aeonos.portalha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardPolicyTest {
    @Test
    fun `configured ImmichFrame is the initial mode`() {
        assertEquals(
            DashboardMode.IMMICH_FRAME,
            initialDashboardMode(immichEnabled = true, immichUrl = "http://frame.local")
        )
    }

    @Test
    fun `disabled or blank ImmichFrame falls back to Home Assistant`() {
        assertEquals(
            DashboardMode.HA_DASHBOARD,
            initialDashboardMode(immichEnabled = false, immichUrl = "http://frame.local")
        )
        assertEquals(
            DashboardMode.HA_DASHBOARD,
            initialDashboardMode(immichEnabled = true, immichUrl = "  ")
        )
    }

    @Test
    fun `URL without a scheme uses HTTP`() {
        assertEquals("http://frame.local:8080", normaliseDashboardUrl("frame.local:8080"))
        assertEquals("https://frame.local", normaliseDashboardUrl("https://frame.local"))
    }

    @Test
    fun `only exact Home Assistant origin receives media permission`() {
        val haUrl = "https://ha.local:8123/lovelace"

        assertTrue(isAllowedMediaOrigin("https://ha.local:8123", haUrl))
        assertTrue(isAllowedMediaOrigin("https://ha.local:8123/", haUrl))
        assertFalse(isAllowedMediaOrigin("https://evil.local:8123", haUrl))
        assertFalse(isAllowedMediaOrigin("https://ha.local", haUrl))
        assertFalse(isAllowedMediaOrigin("http://ha.local:8123", haUrl))
        assertFalse(isAllowedMediaOrigin("https://ha.local.evil.test:8123", haUrl))
    }

    @Test
    fun `SSL exceptions are limited to local network hosts`() {
        assertTrue(isLocalDashboardUrl("https://192.168.1.20:3000"))
        assertTrue(isLocalDashboardUrl("https://10.0.0.5"))
        assertTrue(isLocalDashboardUrl("https://frame.local"))
        assertFalse(isLocalDashboardUrl("https://example.com"))
        assertFalse(isLocalDashboardUrl("not a URL"))
    }

    @Test
    fun `only intent links may leave the WebView`() {
        assertTrue(isAllowedExternalNavigation("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(isAllowedExternalNavigation("file:///data/local/tmp/secret"))
        assertFalse(isAllowedExternalNavigation("market://details?id=untrusted"))
    }
}
