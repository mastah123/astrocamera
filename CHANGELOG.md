# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
