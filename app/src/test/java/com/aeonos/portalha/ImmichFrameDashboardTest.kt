package com.aeonos.portalha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmichFrameDashboardTest {
    @Test
    fun buildUrlAddsHttpSchemeWhenMissing() {
        assertEquals(
            "http://frame.local:8080",
            ImmichFrameDashboard.buildUrl("frame.local:8080", "")
        )
    }

    @Test
    fun buildUrlAppendsAuthenticationSecret() {
        assertEquals(
            "http://frame.local?authsecret=example",
            ImmichFrameDashboard.buildUrl("frame.local", "example")
        )
    }

    @Test
    fun buildUrlPreservesExistingQueryParameters() {
        assertEquals(
            "https://frame.local/?client=Portal&authsecret=example+value%26more",
            ImmichFrameDashboard.buildUrl("https://frame.local/?client=Portal", "example value&more")
        )
    }

    @Test
    fun buildUrlReplacesExistingAuthenticationSecret() {
        assertEquals(
            "https://frame.local/?client=Portal&authsecret=example-new",
            ImmichFrameDashboard.buildUrl("https://frame.local/?authsecret=example-old&client=Portal", "example-new")
        )
    }

    @Test
    fun isEnabledRequiresToggleAndUrl() {
        assertTrue(ImmichFrameDashboard.isEnabled(true, "frame.local"))
        assertFalse(ImmichFrameDashboard.isEnabled(false, "frame.local"))
        assertFalse(ImmichFrameDashboard.isEnabled(true, "   "))
    }

    @Test
    fun selectedDashboardPrefersConfiguredImmichFrame() {
        assertEquals(
            "http://frame.local?authsecret=example",
            DashboardTarget.selectedUrl(
                immichFrameEnabled = true,
                immichFrameUrl = "frame.local",
                immichFrameAuthSecret = "example",
                haUrl = "http://homeassistant.local:8123",
            )
        )
    }

    @Test
    fun selectedDashboardFallsBackToHomeAssistantWhenImmichFrameIsOff() {
        assertEquals(
            "http://homeassistant.local:8123",
            DashboardTarget.selectedUrl(
                immichFrameEnabled = false,
                immichFrameUrl = "frame.local",
                immichFrameAuthSecret = "example",
                haUrl = "homeassistant.local:8123",
            )
        )
    }
}
