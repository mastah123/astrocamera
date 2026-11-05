# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.7.0] - 2026-11-05

### Added
- Capture button with a self-timer (off, 2, 5 or 10 seconds) and an on-screen countdown; tap the shutter during the countdown to cancel.
- Photos are saved as JPEG to Pictures/AstroCamera with the settings in the file name.
- Private capture log (captures.jsonl) recording the requested and camera-reported exposure, ISO and focus for every photo.
- Fallback to a preview-only session when a camera cannot run preview and photo streams together.

### Changed
- Exposure, ISO and focus controls now sit over the lower edge of the preview. Tap a value to bring up its slider, so nothing needs scrolling.
- Shutter, timer and Manual/Auto switch sit under the preview.

## [0.6.1] - 2026-11-05

### Fixed
- Astro Camera preview scrolled away with the controls. It now stays fixed at the top while only the controls scroll.

## [0.6.0] - 2026-11-05

### Added
- Astro Camera screen with a live Camera2 preview.
- Manual exposure, ISO and focus controls, with an Infinity button for focus.
- Exposure slider capped at the verified limit from the device profile; an unverified camera is capped at 1/30 s.
- Live readout of the exposure, ISO and frame time the camera reports.
- Auto exposure and focus mode.
- Camera permission request, and preview stops and restarts with the app lifecycle.

### Changed
- App is locked to portrait orientation.
- Home screen opens Astro Camera first.

## [0.5.0] - 2026-11-05

### Added
- Verified device profile: each camera keeps its proven long exposure limit, the request mode that proved it, and how it was proven (elapsed time, sensor timestamps, brightness or metadata only).
- Stronger evidence replaces weaker evidence when merging test results.
- Device profile card in Settings with Share (JSON) and Reset.
- Verified exposure row in the Camera Tester and in the compatibility report.
- Home screen shows the verified or unverified exposure limit and how it was proven.

### Fixed
- Brightness check missed a false pass when frames got darker at a longer exposure. Any drop of more than 10% now rejects the step.
- Dark room mode showed "NoData"; it now reads "Skipped".

### Changed
- Previous saved limits are replaced by the profile. Re-run the test once to fill it.

## [0.4.0] - 2026-11-05

### Added
- Dark room mode switch: skips brightness checks so the test works with no light.
- Camera2 request mode switch: Standard, Manual template and Repeating stream.
- Repeating mode verifies exposure using sensor frame timestamps.
- Vendor extension detection (Night, HDR, Bokeh) in the compatibility report.
- Home screen shows "timing verified" when the limit was proven by timing.

### Changed
- Brightness check is skipped automatically in dark room mode.

## [0.3.2] - 2026-11-05

### Fixed
- Long Exposure Test accepted sub-second exposures that were not applied. It now also rejects a step when the reported frame time is shorter than the exposure, or when the picture does not get brighter as exposure rises.
- Previously saved tested limits are ignored again.

### Changed
- Test ladder has finer steps (50 ms, 200 ms) to locate the real limit.
- Each result shows the reason a step was rejected and the reported frame time.

## [0.3.1] - 2026-11-05

### Fixed
- Brightness check reported "confirmed" on noisy, clipped data. It now treats near-white frames as clipped and requires steadily rising brightness.
- Long Exposure Test trusted capture metadata alone. It now measures real elapsed time and rejects exposures that finish too fast to be real.
- Previously saved tested limits are ignored, since they were measured without the timing check.
- "1 frames" wording in the software plan.

## [0.3.0] - 2026-11-05

### Added
- Settings screen with an Experimental section.
- Test depth choice for the Long Exposure Test: Quick (8 s), Extended (30 s), Deep (2 min) and Extreme (5 min).
- Brightness check that confirms longer exposures produce brighter frames.
- Cancel button and keep-screen-on while a test runs.
- Tested exposure limit saved on the device and shown on the home screen.

### Changed
- Long Exposure Test moved from the home screen to Settings, Experimental.
- Red night-vision toggle moved to Settings.
- Software plan now includes a 5 minute total.
- Result summary says whether the highest tested value was reached or where the camera stopped honoring requests.

## [0.2.0] - 2026-11-05

### Added
- Long Exposure Test that sends real manual exposure and ISO requests and compares them with the camera's capture results.
- Auto night-boost test (maximum exposure compensation, lowest frame rate).
- Software long exposure plan: frames needed for 10, 30 and 60 second totals and the expected noise improvement.
- Shareable long exposure report.
- Camera permission, requested only when the test is run.

### Changed
- Compatibility report marks exposure and ISO ranges as "reported only" when manual sensor control is not advertised.
- Compatibility report flags RAW as unconfirmed when the format is listed but the capability is not advertised.

## [0.1.0] - 2026-11-05

### Added
- Android project in Kotlin with Jetpack Compose (minSdk 28).
- Camera2 capability detection for every camera on the device.
- Camera Compatibility Report with exposure, ISO, focus, RAW/DNG, sensor, format and hardware-level details.
- Home screen with camera readiness status.
- Share action for the compatibility report as plain text.
- Dark theme and red night-vision theme.
- GitHub Actions workflow building debug and release APKs on `master`.
- Release signing driven by GitHub Secrets.
- Helper script to create a keystore and its Base64 secret value.
