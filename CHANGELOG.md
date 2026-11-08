# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.16.0] - 2026-11-05

### Added
- Outlier rejection: with five or more frames, the highest and lowest value of every pixel are dropped before averaging. This removes hot pixels, satellites and aircraft trails.
- Dark frames: cover the lens and tap Dark on the preview. 16 frames at the current exposure and ISO are averaged into a dark picture, which is subtracted from stacks made at those settings. Each exposure and ISO keeps its own dark. Studio and the camera screen show whether one is in use.
- Night noise reduction (2×2) in Settings and Stacking Studio: four pixels become one, roughly halving the noise at a quarter of the size.
- Stronger denoising of dark pictures: besides smoothing colour noise, brightness noise that is too weak to be a star is flattened, while anything that stands out is left alone.

### Changed
- Alignment is stricter: star detection needs a clearer peak above the noise, and at least 8 stars and 8 matches, so noise is no longer mistaken for stars. A picture with too few stars is stacked without alignment, as for a tripod.
- Dark frames and 2×2 mode apply to bursts, batches, live stacking and Stacking Studio.

## [0.15.0] - 2026-11-05

### Added
- Boost button over the live preview. It brightens the viewfinder so you can aim in the dark, and the same switch brightens the final stack. It affects only what you see until a stack is made; the photos themselves are not changed.

### Removed
- The stacking method choice in Settings. Normal bursts always line up the stars and average. Star trails are made from Star Trails on Home, or per stack in Stacking Studio.
- The Trails toggle on the camera screen. Star Trails mode is entered from Home, and the camera screen shows a ★ Trails label while it is on.
- The Brightness boost switch in Settings. It moved to the live preview.

### Changed
- Boost is off by default. An earlier saved on setting is not carried over, so the viewfinder is not blown out in daylight.

## [0.14.1] - 2026-11-05

### Fixed
- Brightness boost turned dark stacks pink and amplified the noise. It used one stretch curve for all three colours, built from a very small brightness range.
  - Each colour now has its own black point, taken from its background level, which removes the colour cast.
  - The gain is capped for dark pictures so a nearly black image is not blown up into noise.
  - Dark pictures get their colour noise smoothed before the stretch. Brightness is left alone, so stars stay sharp.
- Gamma of the boost lowered from 0.6 to 0.7 so the background is not lifted to mid-grey.

## [0.14.0] - 2026-11-05

### Added
- Interval star trails. In Star Trails mode the phone takes a short shot, merges it into one running trails image, waits, and repeats for the chosen time, so it does not have to capture continuously.
- Three new sliders in Star Trails mode: SHOT (frames averaged per shot, 1 to 5), EVERY (continuous, 5 s to 5 min) and FOR (5 minutes to 4 hours), each with a snapping ruler.
- Details line for trails: number of shots, interval, duration, estimated trail length in pixels, whether the trails will have gaps, and the storage used if the frames are kept.
- Trail length and gap estimates use the sensor size and focal length to work out how fast stars drift across the picture.
- Screen dims while a trails session runs and is restored afterwards.
- A checkpoint image is saved every 10 shots, so a crash or a stopped session keeps most of the night. The older checkpoint is removed once a new one is checked.
- The thumbnail shows the trails image growing after every shot.
- Tapping the shutter during a session finishes and saves what has been captured instead of discarding it.
- Temperature protection pauses a session when the phone is hot and ends it if it gets too hot.

### Changed
- Star Trails mode no longer changes your frame count or stack setting. Those controls are replaced by the trails sliders while the mode is on.
- Frames of a trails session are only saved to the gallery when you keep originals; otherwise each shot is merged and dropped.

## [0.13.0] - 2026-11-05

### Added
- Home redesigned around what you are doing: a large Camera card with your newest photo, Star Trails and Stacking Studio tiles (Stacking Studio shows how many captures are ready), and a Phone Check card with manual control, exposure, RAW and live stacking status plus buttons for the Camera Tester and the tests.
- Settings and the temperature chip moved to the top of Home.
- Stacking progress with percentage and an estimated time left, in the camera screen details and in Stacking Studio. Estimates start after the first few frames.
- Batch stacking shows "Batch 2 of 4" with overall progress, and live stacking shows how many frames are stacked so far.
- The shutter ring fills with the stacking progress and turns into a check mark with a short vibration when stacking is done.
- Stacking Studio shows "Done" with a short vibration when finished.
- A "Saving and checking" step so the last part of a stack is not silent.

### Changed
- Home buttons grouped by task instead of a list of equal buttons.

## [0.12.0] - 2026-11-05

### Added
- Live stacking (Settings, Experimental): each frame is aligned and added to a running stack while the next one is captured. If stacking falls behind, capture waits instead of dropping frames.
- Live Stacking Test: shoots a short burst twice, capture only and capture with live stacking, and reports time per frame, waiting, queue length, peak memory, temperature and a verdict (handles live stacking, use batches, or not recommended). The result is stored in the device profile and shared with it.
- Delete originals after stacking is now a simple on/off toggle in Settings, with a separate Ask each time toggle. The Stack slider also has a Keep/Delete originals button.
- Estimated total time and storage size are shown in Auto mode as well as Manual.

### Changed
- Details moved to sit directly under the Back button.
- Details no longer show saved file names or stacking results. Only progress and problems appear, and problems clear themselves after a few seconds and when you return from the gallery.
- Stacking code now shares one finishing step between normal and live stacking.

## [0.11.0] - 2026-11-05

### Added
- Star Trails button on Home: opens the camera in star trails mode (manual, longest verified exposure, infinity focus, 100 or more frames, stacked every 20 frames, brightest pixels kept). A Trails toggle on the camera screen turns the mode on or off.
- Frames, Timer and Stack are sliders like Exposure, ISO and Focus.
- Frames go up to 1000, with an estimate of the storage they use.
- Snapping ruler under every slider. Sliders move freely and only pull onto a marked position when the thumb is close, with a small tick of feedback.
- AF button in the focus slider: continuous autofocus while exposure and ISO stay manual. The infinity button sits beside it.
- The newest saved photo is shown next to the shutter when the camera opens.

### Changed
- The app opens on the camera, in Auto mode, with the controls hidden. Home is reached with Back.
- The thumbnail beside the shutter opens the photo in the gallery instead of Stacking Studio. Stacking Studio is on Home.
- Stack choice is a batch size: off, after the burst, or every N frames. Settings lists common sizes; the slider covers more.
- Stacking uses integer sums so bursts above 257 frames cannot overflow, and handles up to 1200 inputs.

## [0.10.0] - 2026-11-05

### Added
- Stack while capturing: off, after the burst, or every 5, 10 or 20 frames. Each batch is stacked, then capturing continues, and the batches are combined at the end, weighted by frame count.
- Original frames after stacking: ask each time, always keep, or always delete. Originals are deleted only after the stacked image has been saved and read back successfully; frames that could not be used are kept.
- Temperature meter on the camera screen (battery temperature, with a charging mark) and a details dialog.
- Temperature protection with Cautious, Normal and Relaxed presets: warns when warm, pauses and vibrates when hot, stops the burst when too hot, and asks before resuming after cooling.
- Battery temperature and thermal status recorded for every frame in the capture log, and in the shared compatibility report.
- Diagnostics in Settings: live temperature and thermal readout, plus Share last crash and Clear.
- Crash and error log kept on the device.
- Settings are remembered: red mode, frame count, timer and stacking choices.
- Camera-style screen: slim top readout, preview with the open slider over it, one chip strip, then thumbnail, shutter with a progress ring, and Manual/Auto. The thumbnail opens Stacking Studio.

### Fixed
- App closed when Medium or Strong brightness boost was chosen. The single-frame comparison image was edited in place although Android decodes it as read-only.
- Stacking errors now show a message instead of closing the app.

### Changed
- Brightness boost is a single On or Off switch.
- Stacking Studio follows the original-frames setting after a manual stack.

## [0.9.0] - 2026-11-05

### Added
- Stacking Studio: pick a multi-frame capture, then align and average it, or build a star-trails image.
- Star-based alignment: finds stars in each frame and lines the frames up by voting on the shift (translation only).
- Brightness boost (off, medium, strong) with the same stretch applied to the stack and to a single frame, so the two compare fairly.
- Single frame versus stacked view for comparing the result.
- Results saved to Pictures/AstroCamera/STACKED and shown with how many frames were used or rejected.
- Version number and build number card at the top of Settings.

### Changed
- App requests a large memory heap so full-size frames can be stacked.
- Removed the small version line at the bottom of Settings.

## [0.8.0] - 2026-11-05

### Added
- Multi-frame capture: choose 1, 5, 10, 20, 50 or 100 frames, all taken with the same settings.
- Frame counter, progress bar and a stop button during a sequence; the total exposure is shown before you start.
- Each sequence is saved in its own folder, Pictures/AstroCamera/SESSION_<time>, with numbered frames.
- Frames are saved in the background while the next one is captured.
- Minimal processing for multi-frame manual captures (noise reduction and edge enhancement off, white balance locked where the phone allows it) so frames stack more consistently.
- RAW Capture Test in Settings, Experimental: takes a real RAW photo, checks the pixels, and saves a DNG.
- RAW capture result stored in the device profile, shown in the Camera Tester, the shared report and the profile JSON.
- Sequence details (session, frame number, total) in the private capture log.

### Changed
- Reset limits in Settings also clears the RAW result.

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
