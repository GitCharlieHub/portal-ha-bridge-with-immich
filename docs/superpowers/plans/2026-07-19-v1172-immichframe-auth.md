# v1.17.2 ImmichFrame Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add optional ImmichFrame dashboard mode with Authentication Secret support on top of upstream Portal HA Bridge v1.17.2.

**Architecture:** Keep `DashboardActivity` as the single foreground/kiosk activity so v1.17.2 camera self-heal and dashboard return behavior remain intact. Add a pure Kotlin URL builder for ImmichFrame auth-secret query handling, store settings in `Prefs`, and expose them on the existing settings screen.

**Tech Stack:** Android/Kotlin, WebView, SharedPreferences, JUnit unit tests, Gradle Android plugin.

## Global Constraints

- Base branch starts from upstream tag `v1.17.2`.
- Do not reintroduce the old broadcast foreground workaround.
- ImmichFrame must run inside the existing dashboard WebView.
- Authentication Secret is appended as `authsecret` only when configured.
- Existing Home Assistant dashboard behavior remains the default when ImmichFrame mode is disabled.

---

### Task 1: ImmichFrame URL Builder

**Files:**
- Create: `app/src/main/java/com/aeonos/portalha/ImmichFrameDashboard.kt`
- Create: `app/src/test/java/com/aeonos/portalha/ImmichFrameDashboardTest.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: `ImmichFrameDashboard.buildUrl(rawUrl: String, authSecret: String): String`
- Produces: `ImmichFrameDashboard.isEnabled(enabled: Boolean, rawUrl: String): Boolean`

- [ ] Write failing tests for URL normalization and `authsecret` query appending.
- [ ] Add JUnit dependency and the production helper.
- [ ] Run `testDebugUnitTest` and verify tests pass.

### Task 2: Settings Storage and UI

**Files:**
- Modify: `app/src/main/java/com/aeonos/portalha/Prefs.kt`
- Modify: `app/src/main/java/com/aeonos/portalha/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`

**Interfaces:**
- Consumes: `Prefs.immichFrameEnabled`, `Prefs.immichFrameUrl`, `Prefs.immichFrameAuthSecret`

- [ ] Add preferences for enabled/url/auth secret.
- [ ] Add settings controls under Home Assistant settings.
- [ ] Save settings with the existing Save button and restart service as before.

### Task 3: Dashboard Integration

**Files:**
- Modify: `app/src/main/java/com/aeonos/portalha/DashboardActivity.kt`

**Interfaces:**
- Consumes: `ImmichFrameDashboard.buildUrl(...)`
- Consumes: `ImmichFrameDashboard.isEnabled(...)`

- [ ] Load ImmichFrame URL in the existing WebView when enabled.
- [ ] Keep Home Assistant URL as fallback/default.
- [ ] Update resume reload detection to compare against the selected dashboard URL.

### Task 4: Verification and Delivery

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `README.md`

- [ ] Bump app version for the forked build.
- [ ] Document ImmichFrame URL and Authentication Secret.
- [ ] Run unit tests and assemble debug APK.
- [ ] Commit, push, and open a PR.
