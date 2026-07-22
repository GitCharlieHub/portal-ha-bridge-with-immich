package com.aeonos.portalha

object DashboardTarget {
    fun selectedUrl(
        immichFrameEnabled: Boolean,
        immichFrameUrl: String,
        immichFrameAuthSecret: String,
        haUrl: String,
    ): String {
        return if (ImmichFrameDashboard.isEnabled(immichFrameEnabled, immichFrameUrl)) {
            ImmichFrameDashboard.buildUrl(immichFrameUrl, immichFrameAuthSecret)
        } else {
            haUrl.trim().takeIf { it.isNotEmpty() }?.let { ImmichFrameDashboard.normalise(it) } ?: ""
        }
    }
}
