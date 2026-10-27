# 03 — Dashcam mode

Dashcam mode is a profile on the Smart Loop engine. It records continuously in segments, overwrites the oldest unprotected footage, and protects incidents automatically.

## User flow

1. The user mounts the phone and plugs it into the car charger.
2. Recording starts in one of four ways: a tap on **Start**, the Quick Settings tile, the home-screen widget, or the auto-start notification (on charger or car Bluetooth).
3. The screen can dim or show a minimal HUD (time, speed, REC indicator, storage left).
4. If there is an incident (a G-sensor spike or a tap on the big **SAVE** button), the surrounding footage is protected and a short vibration confirms it.
5. Recording stops when the user taps Stop, when the charger is unplugged (after a configurable delay, default 60 s), or when the car Bluetooth disconnects.

## Features in the POC

| Feature | Details |
|---|---|
| Loop recording | Segment length 3 min by default; retention follows the policy in [02](02-smart-loop-engine.md) |
| Manual protect | A big on-screen SAVE button, a volume key, or the notification action. Protects 30 s before and 30 s after. |
| G-sensor auto-protect | `TYPE_LINEAR_ACCELERATION` (gravity removed), sampled at 50 Hz. Triggers when the magnitude is above the threshold for 2 or more samples. |
| Sensitivity presets | Low 3.0 g, Medium 2.0 g, High 1.2 g, or Off. A 10 s cooldown prevents a storm of events on bumpy roads. |
| GPS + speed | `FusedLocationProviderClient` at 1 Hz while recording. Each point is stored as a `TelemetryPoint` row and also in a per-segment `.gpx`-like sidecar. |
| Burned-in overlay | Date/time, speed (km/h or mph) and an optional plate label are drawn into the video using a CameraX `OverlayEffect` (camera-effects). They can't be removed later, which is what you want for evidence. |
| Metadata overlay (alternative) | The same data kept as metadata only and drawn at playback. The user chooses between this and burned-in. |
| Auto-start (best effort) | Charger connected or a chosen Bluetooth device connected → a high-priority notification "Tap to start dashcam". See the limits below. |
| Auto-stop | Charger disconnected or BT disconnected → finalize after the delay |
| Screen-off recording | Keeps recording with the screen off, through a foreground service of type `camera|microphone|location` |
| Incident card | Each protected event shows time, location on a small static map, peak g-force, speed and the clip |

## G-sensor detection

```
a = |linear_acceleration| / 9.81          // in g
if a > threshold for ≥ 2 consecutive samples (≥ 40 ms)
   and now − lastEvent > cooldown (10 s)
→ emit Event(type = IMPACT, peakG = max(a), time = sampleTime)
```

- Sampling runs only while recording, which keeps battery use low.
- The phone mount orientation doesn't matter because the magnitude is used.
- **Hard braking** (a weaker signal along the direction of travel) is a later feature, as it needs orientation calibration.

## Auto-start: what is actually possible

| Trigger | Android 10–13 | Android 14+ | Android 15+ |
|---|---|---|---|
| App visible, tap Start | ✅ | ✅ | ✅ |
| Quick Settings tile / widget tap | ✅ | ✅ (user interaction counts as visible) | ✅ |
| Charger / BT connected while the app is in the background | ⚠️ Can start an FGS, but camera access from the background is restricted | ❌ A camera FGS can't get while-in-use access when started from the background | ❌ |
| On boot (`BOOT_COMPLETED`) | ⚠️ | ❌ | ❌ Explicitly blocked for camera/microphone FGS types |
| **POC approach** | Show a full-screen or high-priority notification; **the user taps → recording starts** | same | same |

Advanced option (later): an "old phone as dedicated dashcam" kiosk mode, where FRAME is set as the home app or runs as device owner. That lifts many limits for a phone that is permanently mounted in a car.

## UI (POC)

```
┌────────────────────────────────┐
│ ● REC 01:23:45     62 km/h     │
│                                │
│        [ camera preview ]      │
│                                │
│ 3-min loop · 41.2 GB free      │
│ 3 protected · G-sensor: Medium │
│                                │
│   [ STOP ]          [ SAVE ]   │
└────────────────────────────────┘
```

- **SAVE** takes up at least 30% of the screen width, so it can be hit without looking.
- Night mode: a dark UI with the preview dimmed to reduce glare in the car.

## Privacy and legal notes

- An **audio recording toggle** that is off by default in some regions. Recording conversations may need consent from everyone in the car.
- A first-run notice: dashcam laws differ by country (for example, restrictions on publishing footage, windshield mounting rules). The user is responsible for compliance.
- Location data stays on the device unless the user exports it.
