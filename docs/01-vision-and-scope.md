# 01 — Vision, goals and scope

## Product vision

FRAME combines five tools that usually come as separate apps:

| Module | Purpose | Signature capability |
|---|---|---|
| 01 Dashcam | Continuous car recording | Loop recording that overwrites the oldest unprotected footage; G-sensor incident protection |
| 02 Sports | Recording a full match | Long sessions with automatic file splitting; one-tap highlights without stopping |
| 03 Pro Camera | Photo and video capture | Manual controls, lens selection, overlays, external mics |
| 04 Studio | Editing | Trim, merge, timeline, color, speed, export presets |
| 05 Library & Storage | Managing media | Retention policies, protection, cleanup history, search, backup |

**The main differentiator is Smart Loop Recording.** The app always records within a storage budget the user sets, deletes old footage by time or by free space, and never touches protected moments. Ordinary camera apps don't offer a complete, configurable retention system like this.

## POC goals (what must be proven)

1. **Continuous segmented recording.** Segment lengths of 1, 3, 5 or 10 minutes. The gap between segments is at most 1 s with the simple CameraX approach, and 0 dropped frames with the custom muxer (see [02](02-smart-loop-engine.md#segment-switching-two-options)). An 8-hour session runs without crashing.
2. **Safe Smart Loop cleanup.** Time-based and storage-based retention only ever deletes *eligible* segments.
3. **Event protection.** A manual tap, a G-sensor spike or a sports highlight protects a configurable window before and after the event.
4. **Crash recovery.** After a process kill, a reboot or a dead battery, at most the last open segment is lost, and the library index rebuilds itself from the files on disk.
5. **Honest limits.** The app explains what the OS and the device allow, such as background recording, auto-start, thermal throttling and OEM battery killers. It never fails silently.

## Assumed decisions

The four platform questions from the vision are still open, so the POC uses these defaults. Each one is reversible.

| Decision | POC choice | Why | Impact of a different choice |
|---|---|---|---|
| Target platform | **Android first, native** (Kotlin + Jetpack Compose) | CameraX, foreground services and Room make sustained recording practical. Kotlin is close to Java and Spring Boot. | iPhone first → Swift + AVFoundation. iOS stops camera capture in the background, which seriously limits dashcam mode. |
| Primary use | **Dashcam and Sports equally**, on one engine | Both need the same core: segments, retention and event markers. | Choosing one drops the other mode's UI (about 1 week saved). |
| Storage | **Offline-first, on the device** | Must work with no network; avoids privacy and cost issues early. | Optional cloud → sync queue and a Spring Boot backend in Phase 5. |
| Editing | **Basic: trim and export a clip** | Proves the engine's output is editable without building Studio yet. | Advanced timeline → Phase 4 (Media3 Transformer + composition model). |
| Min / target SDK | **API 29 / API 35** | Scoped storage; stable CameraX behavior on modern devices. | Going lower adds legacy storage and camera quirks. |

## Scope

| Module | In the POC | Later phases |
|---|---|---|
| Recording engine | Segmented recording, Smart Loop retention, protection, event buffer, crash recovery, foreground service, thermal and battery guard, recording health monitor | Dual-camera (front + rear) recording, USB/UVC cameras, SD-card output |
| Dashcam | Loop mode, protect button, G-sensor auto-protect, GPS and speed in metadata, burned-in time/speed overlay, auto-start on charger or car Bluetooth (best effort) | Trip map history, parking mode, ADAS alerts, voice commands |
| Sports | Long-session recording, highlight marker (tap, volume key, Bluetooth remote), match timer, simple score overlay, highlight list and export | Telestration, side-by-side compare, player tracking, multi-camera sync, live streaming |
| Pro Camera | Photo/video, resolution, FPS, stabilization, front/rear and lens switching, grid, timer | Manual ISO/shutter/focus/WB, histogram, focus peaking, LOG profiles, burst |
| Library & Storage | Browse by date, mode and event; protect and favorite; storage dashboard; cleanup history; trim and export | Albums, full-text and AI search, duplicate detection, cloud backup |
| Studio | Trim and export only | Multi-track timeline, audio, transitions, LUTs, speed ramps, object removal |
| AI and cloud | — | Highlight detection, transcription, smart search, opt-in backup, accounts |

## Out of scope for the POC

- iOS, desktop and web clients
- Accounts, a backend, cloud sync
- A Play Store release: the POC is distributed as an internal APK or a Play internal testing track
- Monetization
