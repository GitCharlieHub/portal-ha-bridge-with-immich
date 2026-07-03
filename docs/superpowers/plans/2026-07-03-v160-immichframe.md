# Portal HA Bridge v1.6.0 with ImmichFrame Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the fork from Portal HA Bridge v1.6.0 and add only an in-dashboard ImmichFrame mode.

**Architecture:** The v1.6.0 tag is authoritative for existing behavior, especially camera ownership and lifecycle. ImmichFrame is a second URL loaded by the existing foreground `DashboardActivity` WebView.

**Tech Stack:** Android, Kotlin, WebView, Gradle, JUnit.

## Global Constraints

- Preserve Portal HA Bridge v1.6.0 functionality.
- Exclude newer features, including intercom and announce/broadcast controls.
- Do not alter v1.6.0 camera, streaming, MQTT, boot, or service mechanisms.

### Task 1: Restore v1.6.0

**Files:** Replace all release-tracked files; preserve `docs/superpowers/**`.

- [ ] Fetch the `v1.6.0` tag into the fork.
- [ ] Restore tracked project files from the tag.
- [ ] Verify later features are absent.
- [ ] Commit the baseline restoration.

### Task 2: Add Tested Dashboard Policy

**Files:**
- Create: `app/src/main/java/com/aeonos/portalha/DashboardPolicy.kt`
- Create: `app/src/test/java/com/aeonos/portalha/DashboardPolicyTest.kt`

- [ ] Write tests for initial mode, fallback, URL normalization, and HA-only media permissions.
- [ ] Run the focused test and observe failure because the policy is absent.
- [ ] Implement pure policy functions using exact URI origin matching.
- [ ] Run the focused test and observe success.

### Task 3: Add ImmichFrame Settings

**Files:**
- Modify: `app/src/main/java/com/aeonos/portalha/Prefs.kt`
- Modify: `app/src/main/java/com/aeonos/portalha/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`

- [ ] Add `immich_frame_enabled` and `immich_frame_url` preferences.
- [ ] Add and wire one enable switch and one URL field.
- [ ] Build the debug application.

### Task 4: Add Foreground Dashboard Mode

**Files:**
- Modify: `app/src/main/java/com/aeonos/portalha/DashboardActivity.kt`
- Modify: `app/src/main/res/layout/activity_dashboard.xml`

- [ ] Add a drawer button that switches the existing WebView between HA and ImmichFrame.
- [ ] Keep v1.6.0 Activity/service lifecycle calls unchanged.
- [ ] Deny ImmichFrame media permissions; allow only exact configured HA origin requests in HA mode.
- [ ] Run unit tests and build the debug APK.

### Task 5: Verify And Document

**Files:** Modify `README.md`.

- [ ] Document that this is v1.6.0 plus ImmichFrame and excludes newer features.
- [ ] Prove protected camera/service files have no diff from `v1.6.0`.
- [ ] Run `./gradlew clean testDebugUnitTest assembleDebug`, `git diff --check`, and inspect status.
- [ ] Commit the implementation.
