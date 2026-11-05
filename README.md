# Astro Camera

[![Build](https://github.com/YOUR_USER/YOUR_REPO/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/YOUR_USER/YOUR_REPO/actions/workflows/build.yml)

An Android astrophotography camera that uses the real capabilities of your phone's camera hardware.

> Understand your camera. Unlock its capabilities. Capture the night sky.

Astro Camera does not assume every phone is the same. It inspects the device through the Camera2 API and shows what the hardware can actually do before you shoot.

## Status

Version 0.7.0 covers **Stages 1 to 3**: capability detection, an experimental **long exposure probe**, a live preview with manual controls, and single-frame capture with a self-timer.

| Stage | Scope | State |
|-------|-------|-------|
| 1 | Capability detection and compatibility report | Done |
| 1.5 | Long exposure probe and software exposure plan | Done |
| 2 | Camera preview and manual controls | Done |
| 3 | Astrophotography capture mode (single frame, self-timer) | Done |
| 4 | Multi-frame capture | Planned |
| 5 | Image stacking | Planned |
| 6 | Gallery and processing | Planned |
| 7 | Optimization and device compatibility | Planned |

## Features

- Capture button with self-timer; photos saved to Pictures/AstroCamera with their settings logged
- Live preview with manual exposure, ISO and focus, limited to what the phone really does
- Detects every camera on the device, including logical and physical lenses
- Reports exposure range, ISO range, manual and infinity focus, RAW/DNG, sensor size, output formats and hardware level
- Readiness summary on the home screen
- Settings → Experimental → Long Exposure Test: requests real manual exposures from 1/100 s up to 5 minutes, confirms frames get brighter, and shows whether hardware or software (stacking) long exposure is possible
- Verified device profile: the real exposure limit per camera, how it was proven, shareable as JSON
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
├── camera/                Camera2 detection, exposure probe and reports
└── ui/                    Compose screens and theme
```

## Changelog

See [CHANGELOG.md](CHANGELOG.md). This project follows [Semantic Versioning](https://semver.org).
