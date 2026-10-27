# 05 — Camera, library and storage manager

## Pro Camera (POC slice)

The POC adds a basic but solid camera on the same engine. Full manual controls come in Phase 3.

| Feature | POC | Implementation |
|---|---|---|
| Photo capture | ✅ | CameraX `ImageCapture`, JPEG, `CAPTURE_MODE_MINIMIZE_LATENCY` or `MAXIMIZE_QUALITY` |
| Video capture | ✅ | CameraX `VideoCapture<Recorder>` (same engine; Camera profile with "Never" retention) |
| Resolution | ✅ 720p / 1080p / 4K where supported | `QualitySelector` + `QualitySelector.getSupportedQualities` |
| Frame rate | ✅ 24 / 30 / 60 where supported | `Camera2Interop` target FPS range |
| Stabilization | ✅ On/off | `Preview.Builder.setPreviewStabilizationEnabled`, `Recorder` video stabilization |
| Front/rear + lens switching | ✅ | `CameraSelector`; for multiple back lenses, enumerate physical cameras through `Camera2CameraInfo` |
| Zoom | ✅ Pinch + preset buttons (0.5×/1×/2×) | `CameraControl.setZoomRatio` |
| Tap to focus / exposure | ✅ | `FocusMeteringAction` |
| Grid lines, level | ✅ | Compose overlay; the level uses the accelerometer |
| Timer | ✅ 3 s / 10 s | Coroutine countdown |
| Aspect ratio | ✅ 4:3 / 16:9 | `ResolutionSelector` |
| Manual ISO / shutter / WB / focus | Later (Phase 3) | Camera2 interop, only when `MANUAL_SENSOR` is supported |
| Histogram, focus peaking, zebras | Later | `ImageAnalysis` + GPU shader |
| External microphone, audio meter | Partial: show the meter | `AudioRecord` peak level; route selection through `AudioManager` |

## Media library

### Browsing

- **Tabs:** All · Dashcam · Sports · Camera · Protected · Favorites
- **Grouping:** by day, and by session. A dashcam drive or a sports match shows as one card containing its segments.
- **Filters:** date range, mode, protected yes/no, has events, file size
- **Session card:** start–end time, duration, number of segments, size, event count, first-frame thumbnail
- **Player:** ExoPlayer (Media3) with seamless playback across segments, using a `ConcatenatingMediaSource2` or a playlist of segment items. Shows event markers on the scrub bar, speed control and frame step.

### Actions

| Action | Where | Notes |
|---|---|---|
| Protect / Unprotect | Segment, event, session | Unprotecting asks for confirmation if the footage is older than the retention period (it would be deleted on the next cleanup) |
| Favorite | Any item | Favorites are **not** automatically protected. That keeps the two ideas separate, and the UI says so. |
| Trim & export | Segment or event | Media3 Transformer; saves into `Movies/FRAME` through MediaStore |
| Share | Any exported file | Android share sheet; a large file shows a warning |
| Delete | Any item | Protected items need a second confirmation |
| Incident report | Dashcam event | Later: a PDF with time, map, speed, g-force and a QR code for the clip |

## Storage manager

The screen that makes Smart Loop visible and trustworthy.

```
┌───────────────────────────────────────┐
│ Storage                               │
│ ███████████████░░░░░░░░  62% used     │
│ Dashcam 18.4 GB · Sports 9.1 GB       │
│ Camera 2.3 GB · Protected 6.0 GB      │
│ Reserve 10% (12.8 GB) · Free 48.6 GB  │
│                                       │
│ At current settings you can record:   │
│   ~14 h dashcam (1080p30, 12 Mbps)    │
│                                       │
│ Policies                    [Edit >]  │
│ Dashcam: keep 3 days, 3-min segments  │
│ Sports:  keep forever                 │
│                                       │
│ Cleanup history             [View >]  │
│ Today 09:12  deleted 14 files 2.1 GB  │
│   reason: expired (older than 3 days) │
│                                       │
│ [ Clean up now ]                      │
└───────────────────────────────────────┘
```

| Element | Data source |
|---|---|
| Usage per mode | `SUM(sizeBytes)` from `segment`, grouped by mode, with a separate figure for protected |
| Free / total | `StatFs` on the app's external files directory |
| Recording time left | `(free − reserve) / currentBitrate` |
| Cleanup history | `cleanup_log` table, newest first, kept 30 days |
| "Clean up now" | Runs the same algorithm as automatic cleanup, after a preview ("Will delete 14 files, 2.1 GB") |

## File storage layout

Recordings live in **app-specific external storage** (`context.getExternalFilesDir(null)`). This needs no storage permission, the app fully owns the files (so retention can safely delete them), and other apps can't see them. Exports are copied to the shared `Movies/FRAME` folder through MediaStore.

```
Android/data/io.frame.app/files/
└── recordings/
    ├── dashcam/
    │   └── 2026-10-26/
    │       └── S0007_20261026T081500/          # session folder
    │           ├── seg_0001_081500.mp4
    │           ├── seg_0001_081500.json       # metadata sidecar
    │           ├── seg_0002_081800.mp4
    │           └── telemetry.jsonl            # GPS + g-sensor points
    ├── sports/
    │   └── 2026-10-25/M0003_.../
    ├── camera/
    └── events/                                 # extracted event clips
└── thumbnails/
```

**Warning to show users:** app-specific storage is deleted when the app is uninstalled. The app shows this before uninstall is possible (in Settings), and offers "Export protected clips" first.
