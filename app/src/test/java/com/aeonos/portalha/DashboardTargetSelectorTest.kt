package com.aeonos.portalha

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardTargetSelectorTest {
    @Test
    fun `ImmichFrame is preferred when configured`() {
        assertEquals(
            DashboardTarget.IMMICH_FRAME,
            DashboardTargetSelector.select(" http://immich.local:3000 ", "http://ha.local:8123")
        )
    }

    @Test
    fun `Home Assistant is used when ImmichFrame is blank`() {
        assertEquals(
            DashboardTarget.HOME_ASSISTANT,
            DashboardTargetSelector.select(" ", "http://ha.local:8123")
        )
    }

    @Test
    fun `blank URLs are unconfigured`() {
        assertEquals(
            DashboardTarget.UNCONFIGURED,
            DashboardTargetSelector.select("", " ")
        )
    }
}
