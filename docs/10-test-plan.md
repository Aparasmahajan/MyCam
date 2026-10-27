# 10 — Test plan and success criteria

The POC passes when the recording engine meets every **exit criterion** below on all three reference devices.

## Exit criteria

| # | Criterion | Target |
|---|---|---|
| E1 | Continuous dashcam recording, screen off, on charger | 8 h, no crash, no unplanned stop |
| E2 | Segment gap | Option A at most 1 s; Option B 0 dropped frames (checked by timestamp continuity) |
| E3 | Protected clips lost to cleanup | **0**, under all test scenarios |
| E4 | The active segment deleted | **0** |
| E5 | Footage lost after a forced kill or power loss | At most 1 segment (A) or at most 2 s (B) |
| E6 | Index rebuild after deleting the DB | All files rediscovered with correct session/event links |
| E7 | Free space stays at or above the reserve during loop recording | Always, except for the documented hard-floor stop |
| E8 | Event window correctness | Protected range covers at least `[t − before, t + after]` for 100% of events |
| E9 | Battery use (screen off, 1080p30, on charger) | Battery level does not drop on a 2.4 A charger |
| E10 | Thermal handling | Steps down before the OS throttles the camera; no thermal shutdown in a 2 h warm-room test |

## Device matrix

| Device class | Example | Why |
|---|---|---|
| Reference Pixel | Pixel 7 / 8 | Close to stock Android behavior |
| Samsung mid-range | Galaxy A54 / A55 | The largest market share; One UI battery rules |
| Aggressive OEM | Xiaomi Redmi Note 12/13 | MIUI/HyperOS background killing |
| Low-end (stretch) | Any 4 GB RAM Android 10–11 device | Encoder and thermal limits |

## Test scenarios

### Retention and cleanup (unit + instrumented)

| Scenario | Expected |
|---|---|
| 100 segments, 30 older than the retention period, 5 of those protected | 25 deleted, the 5 protected kept |
| Free space below the reserve, only protected segments left | No delete; warning; clean stop at the hard floor |
| Cleanup triggered while a segment is `WRITING` | The active segment never selected |
| Two cleanup triggers at the same moment | Run one after another (mutex); no double delete, no crash |
| File delete fails (read-only, missing) | Row rolled back or marked; logged; continues |
| Event window spanning 3 segments, with the window still open | All 3 protected, including the one finished after the event |
| User unprotects an old segment | Deleted on the next cleanup, after the confirmation dialog |
| Policy changed from 7 days → 12 h | Next cleanup deletes the newly expired segments; preview shows the count first |

Retention logic is pure Kotlin behind interfaces (`Clock`, `StorageStats`, `FileDeleter`), so it can be unit tested quickly with fake time and fake disk sizes. **Property-based tests** (Kotest) generate random segment and event layouts and check that invariants E3, E4 and E7 always hold.

### Recording robustness (device)

| Scenario | Expected |
|---|---|
| 8 h loop recording, screen off | E1, E2, E7 |
| `adb shell am kill` / force-stop during recording | E5; orphan recovered at the next launch |
| Pull the battery / hard power-off (or `adb reboot`) | E5 |
| Incoming phone call during recording | Recording continues (audio may pause); logged |
| Another app opens the camera | Clean finalize; notification; resume offered |
| Low storage simulated (fill the disk with a dummy file) | Cleanup works; hard-floor stop if needed |
| Thermal stress (warm room / heat lamp, 2 h) | Steps down, no crash, logged |
| Rotate the device, lock and unlock, switch apps repeatedly | No interruption |
| Switch lens/camera during recording | New segment, same session, under 1.5 s gap |

### Dashcam and Sports features

| Scenario | Expected |
|---|---|
| Shake / drop test on a soft surface at the High sensitivity setting | IMPACT event within 200 ms; segments protected |
| 30 min drive at Medium sensitivity on a normal road | At most 1 false-positive impact |
| GPS overlay compared with a reference GPS app | Speed within ±3 km/h at steady speed |
| 3 h football match, 15 highlights from a mix of tap, volume key and BT remote | 15 highlights, correct windows, reel exports in under 2× real time per minute of output |
| Score changes during the match | Stored with the correct time; overlay matches |

## Tooling

- **Long-run harness:** an instrumented test plus an `adb` script that starts recording, then every 10 min collects `dumpsys battery`, `dumpsys thermalservice` and app log excerpts, and at the end verifies files (ffprobe on a desktop).
- **Timestamp-continuity check:** `ffprobe -show_frames` across segment boundaries to measure gaps (E2).
- **Macrobenchmark:** cold start under 1 s; time to first recorded frame under 1.5 s.
