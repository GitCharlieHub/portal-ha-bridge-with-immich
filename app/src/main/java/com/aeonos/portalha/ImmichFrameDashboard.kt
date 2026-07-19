package com.aeonos.portalha

import java.net.URLEncoder

object ImmichFrameDashboard {
    fun isEnabled(enabled: Boolean, rawUrl: String): Boolean =
        enabled && rawUrl.trim().isNotEmpty()

    fun buildUrl(rawUrl: String, authSecret: String): String {
        val normalized = normalise(rawUrl.trim())
        val secret = authSecret.trim()
        if (secret.isEmpty()) return normalized

        val fragmentIndex = normalized.indexOf('#')
        val beforeFragment = if (fragmentIndex >= 0) normalized.substring(0, fragmentIndex) else normalized
        val fragment = if (fragmentIndex >= 0) normalized.substring(fragmentIndex) else ""

        val queryIndex = beforeFragment.indexOf('?')
        val base = if (queryIndex >= 0) beforeFragment.substring(0, queryIndex) else beforeFragment
        val existingQuery = if (queryIndex >= 0) beforeFragment.substring(queryIndex + 1) else ""

        val params = existingQuery
            .split('&')
            .filter { it.isNotBlank() }
            .filterNot { it.substringBefore('=').equals("authsecret", ignoreCase = true) }
            .toMutableList()

        params += "authsecret=${URLEncoder.encode(secret, "UTF-8")}"
        return "$base?${params.joinToString("&")}$fragment"
    }

    fun normalise(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> "http://$url"
    }
}
