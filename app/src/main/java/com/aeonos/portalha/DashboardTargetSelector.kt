package com.aeonos.portalha

enum class DashboardTarget { IMMICH_FRAME, HOME_ASSISTANT, UNCONFIGURED }

object DashboardTargetSelector {
    fun select(immichUrl: String, haUrl: String): DashboardTarget = when {
        immichUrl.isNotBlank() -> DashboardTarget.IMMICH_FRAME
        haUrl.isNotBlank() -> DashboardTarget.HOME_ASSISTANT
        else -> DashboardTarget.UNCONFIGURED
    }
}
