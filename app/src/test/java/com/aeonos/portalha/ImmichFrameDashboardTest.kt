package com.aeonos.portalha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmichFrameDashboardTest {
    @Test
    fun buildUrlAddsHttpSchemeWhenMissing() {
        assertEquals(
            "http://192.168.1.50:8080",
            ImmichFrameDashboard.buildUrl("192.168.1.50:8080", "")
        )
    }

    @Test
    fun buildUrlAppendsAuthenticationSecret() {
        assertEquals(
            "http://frame.local?authsecret=mySecret",
            ImmichFrameDashboard.buildUrl("frame.local", "mySecret")
        )
    }

    @Test
    fun buildUrlPreservesExistingQueryParameters() {
        assertEquals(
            "https://frame.local/?client=Portal&authsecret=my+secret%26value",
            ImmichFrameDashboard.buildUrl("https://frame.local/?client=Portal", "my secret&value")
        )
    }

    @Test
    fun buildUrlReplacesExistingAuthenticationSecret() {
        assertEquals(
            "https://frame.local/?client=Portal&authsecret=newSecret",
            ImmichFrameDashboard.buildUrl("https://frame.local/?authsecret=old&client=Portal", "newSecret")
        )
    }

    @Test
    fun isEnabledRequiresToggleAndUrl() {
        assertTrue(ImmichFrameDashboard.isEnabled(true, "frame.local"))
        assertFalse(ImmichFrameDashboard.isEnabled(false, "frame.local"))
        assertFalse(ImmichFrameDashboard.isEnabled(true, "   "))
    }
}
