# Astro Camera

[![Build](https://github.com/YOUR_USER/YOUR_REPO/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/YOUR_USER/YOUR_REPO/actions/workflows/build.yml)

An Android astrophotography camera that uses the real capabilities of your phone's camera hardware.

> Understand your camera. Unlock its capabilities. Capture the night sky.

Astro Camera does not assume every phone is the same. It inspects the device through the Camera2 API and shows what the hardware can actually do before you shoot.

## Status

Version 0.2.0 covers **Stage 1: capability detection** plus a **long exposure probe**.

| Stage | Scope | State |
|-------|-------|-------|
| 1 | Capability detection and compatibility report | Done |
| 1.5 | Long exposure probe and software exposure plan | Done |
| 2 | Camera preview and manual controls | Planned |
| 3 | Astrophotography capture mode | Planned |
| 4 | Multi-frame capture | Planned |
| 5 | Image stacking | Planned |
| 6 | Gallery and processing | Planned |
| 7 | Optimization and device compatibility | Planned |

## Features

- Detects every camera on the device, including logical and physical lenses
- Reports exposure range, ISO range, manual and infinity focus, RAW/DNG, sensor size, output formats and hardware level
- Readiness summary on the home screen
- Long Exposure Test: requests real manual exposures, reads back what the camera used, and shows whether hardware or software (stacking) long exposure is possible
- Shareable plain-text reports
- Dark interface with a red night-vision mode
- Fully offline. The camera permission is requested only when you run the Long Exposure Test

## Requirements

- Android 9 (API 28) or newer
- To build: JDK 17 and Gradle 8.7+, or Android Studio

## Build

```bash
gradle assembleDebug      # debug APK
gradle assembleRelease    # release APK (signed if signing variables are set)
```

APKs are written to `app/build/outputs/apk/`.

## Continuous integration

`.github/workflows/build.yml` builds debug and release APKs on every push and pull request to `master`, and uploads them as the `astro-camera-apks` artifact.

### Release signing

Release APKs are signed automatically when these repository secrets exist (Settings → Secrets and variables → Actions):

| Secret | Value |
|--------|-------|
| `KEYSTORE_BASE64` | Base64 of your `.jks` keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Generate a keystore and get the Base64 value with:

```bash
bash scripts/create-keystore.sh
```

Without these secrets the release APK is built unsigned. Never commit the keystore.

## Project structure

```
app/src/main/java/com/astrocam/app/
├── MainActivity.kt        Entry point and navigation
├── camera/                Camera2 detection and report logic
└── ui/                    Compose screens and theme
```

## Changelog

See [CHANGELOG.md](CHANGELOG.md). This project follows [Semantic Versioning](https://semver.org).
