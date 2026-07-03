package com.aeonos.portalha

import java.net.URI

enum class DashboardMode { IMMICH_FRAME, HA_DASHBOARD }

fun initialDashboardMode(immichEnabled: Boolean, immichUrl: String): DashboardMode =
    if (immichEnabled && immichUrl.isNotBlank()) DashboardMode.IMMICH_FRAME
    else DashboardMode.HA_DASHBOARD

fun normaliseDashboardUrl(url: String): String {
    val trimmed = url.trim()
    return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
        trimmed
    } else {
        "http://$trimmed"
    }
}

fun isAllowedMediaOrigin(requestOrigin: String, homeAssistantUrl: String): Boolean =
    runCatching {
        val request = URI(normaliseDashboardUrl(requestOrigin))
        val configured = URI(normaliseDashboardUrl(homeAssistantUrl))
        request.host != null && configured.host != null &&
            request.scheme.equals(configured.scheme, true) &&
            request.host.equals(configured.host, true) &&
            effectivePort(request) == effectivePort(configured)
    }.getOrDefault(false)

private fun effectivePort(uri: URI): Int = when {
    uri.port >= 0 -> uri.port
    uri.scheme.equals("https", true) -> 443
    else -> 80
}

fun isLocalDashboardUrl(url: String): Boolean = runCatching {
    val host = URI(normaliseDashboardUrl(url)).host ?: return false
    host == "localhost" || host == "127.0.0.1" || host == "::1" ||
        host.endsWith(".local", ignoreCase = true) ||
        host.matches(Regex("""^10\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")) ||
        host.matches(Regex("""^172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}$""")) ||
        host.matches(Regex("""^192\.168\.\d{1,3}\.\d{1,3}$""")) ||
        host.matches(Regex("""^169\.254\.\d{1,3}\.\d{1,3}$"""))
}.getOrDefault(false)

fun isAllowedExternalNavigation(url: String): Boolean =
    url.startsWith("intent:", ignoreCase = true)
