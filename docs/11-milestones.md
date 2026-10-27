# 11 — Milestones

The POC takes **8 weeks for one developer**, with one demo at the end of each two-week block. The engine comes first, so every later week builds on something that already records reliably.

```mermaid
gantt
    title FRAME POC — 8 weeks
    dateFormat  YYYY-MM-DD
    axisFormat  %b %d
    section Foundation
    Project setup, modules, CI, capability probe :a1, 2026-11-02, 4d
    Spike: segment writer A vs B                 :a2, after a1, 3d
    Recording service + segmented recording (A)  :a3, 2026-11-09, 5d
    section Engine
    Room index + sidecars + recovery scanner     :b1, 2026-11-16, 5d
    Retention engine + cleanup + tests           :b2, 2026-11-23, 5d
    Gapless fMP4 writer (Option B)               :b3, 2026-11-30, 7d
    section Modes
    Dashcam: G-sensor, GPS, overlay, auto-start  :c1, 2026-12-07, 5d
    Sports: match, highlights, score overlay     :c2, 2026-12-14, 5d
    section Library and hardening
    Library, player, storage manager, export     :d1, 2026-12-14, 5d
    Long-run tests on 3 devices + fixes          :d2, 2026-12-21, 5d
    milestone POC demo                           :milestone, m1, 2026-12-24, 0d
```

| Week | Dates (2026) | Deliverable | Demo |
|---|---|---|---|
| 1 | Nov 2–6 | Gradle modules, CI (lint, unit tests, APK), camera capability probe, spike comparing segment writer A vs B | — |
| 2 | Nov 9–13 | `RecordingService` with segmented recording (Option A), notification controls, preview attach/detach | **Demo 1:** 1 h of screen-off segmented recording |
| 3 | Nov 16–20 | Room DB + sidecar JSON + recovery scanner; health monitor | — |
| 4 | Nov 23–27 | Retention engine, cleanup worker, property-based tests, policy settings UI | **Demo 2:** loop recording with automatic cleanup and protected clips |
| 5 | Nov 30–Dec 4 (+2 days) | Gapless MediaCodec + fragmented MP4 writer (Option B) behind a feature flag | — |
| 6 | Dec 7–11 | Dashcam: G-sensor, GPS telemetry, burned-in overlay, auto-start notification, QS tile | **Demo 3:** drive test |
| 7 | Dec 14–18 | Sports: match setup, highlights, score overlay, reel export · Library, player, storage manager, trim/export | **Demo 4:** recorded match + highlight reel |
| 8 | Dec 21–24 | 8 h runs on the Pixel, Samsung and Xiaomi devices; fix blockers; exit-criteria report | **POC sign-off** |

The dates assume a start on Monday 2026-11-02. They shift as a block if the start moves.

## After the POC (product phases)

| Phase | Focus | Rough size |
|---|---|---|
| 1 — Recording foundation | (POC hardened) photo/video, controls, library, segments, safe cleanup | done in the POC + 2–3 weeks of polish |
| 2 — Dashcam and Sports | Parking mode, trip summaries, hash chain, incident PDF, app lock, score auto-highlights, Quick Share, kiosk mode | 6–8 weeks |
| 3 — Pro camera | Manual ISO/shutter/WB/focus, histogram, peaking, external mics, profiles and schedules, Wear OS | 6 weeks |
| 4 — Studio | Multi-track timeline, audio tracks, transitions, LUTs, speed ramps, export presets | 10–12 weeks |
| 5 — Advanced and cloud | AI highlights, privacy blur, transcription, opt-in cloud backup (Spring Boot + PostgreSQL + object storage), multi-phone sync, live streaming, iOS | 12+ weeks |
