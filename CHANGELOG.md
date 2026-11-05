# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
