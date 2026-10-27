# 09 — Platform limits and risks

Mobile recording is limited by OS rules, heat, battery and vendor customizations. FRAME has to work within these limits and **tell the user about them honestly**, never fail silently.

## Android platform limits

| Limit | Affects | What FRAME does |
|---|---|---|
| Camera/mic/location are "while-in-use" permissions; a foreground service started **from the background** can't use them (Android 11+, stricter on 14+) | Auto-start on charger / Bluetooth / boot | Show a high-priority or full-screen notification, "Tap to start dashcam". QS tile and widget taps count as user interaction. Kiosk/device-owner mode for dedicated phones (Phase 2). |
| `BOOT_COMPLETED` can't launch camera or microphone FGS (Android 15) | Start on boot | Same notification approach |
| Foreground service types must be declared and match (Android 14) | Recording service | `camera|microphone|location` declared; type passed in `startForeground` |
| The user can dismiss FGS notifications on Android 14+ (the service keeps running) | Visibility of the REC state | Re-post the status through the Recording notification channel; show the REC dot in the app |
| Doze / App Standby | Periodic cleanup while idle | WorkManager with flexible windows; cleanup also runs when a segment finishes and when the app starts |
| Scoped storage | Where files go | App-specific external dir for recordings; MediaStore for exports |
| Camera can be taken over by another app (for example, a phone call that uses the camera) | Recording continuity | Detect `CameraState` CLOSED/ERROR → finalize, notify, and offer to resume |
| Play policy on `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Store release | Declare the dashcam use case; fall back to opening settings manually |

## Device and OEM risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| OEM battery killers (Xiaomi MIUI/HyperOS, Samsung, Oppo, Vivo, Huawei) stop the service | High | High | OEM-specific onboarding; detect an unexpected stop at the next launch and show the fix steps; test matrix includes at least one Xiaomi and one Samsung |
| Overheating on a windshield in sunlight | High | High | Thermal guard steps down resolution/FPS, dims the screen, warns; recommend a vent mount in onboarding |
| Battery drain without a charger | Medium | Medium | Low-battery stop at 5%; warn when not charging in dashcam mode |
| Encoder gives up after hours (some devices' HW encoders) | Medium | High | Health monitor restarts the session after a stall; the long-run test catches it per device |
| Segment gap with CameraX stop/start | Certain (Option A) | Medium | Option B gapless writer |
| Plain MP4 unplayable after a crash | Certain (Option A) | Medium | Short segments (at most 1 lost); fMP4 in Option B |
| Storage fills with protected clips | Medium | High | Warn early, show protected usage, stop cleanly at the hard floor; never delete protected clips |
| SD-card / external storage removed mid-recording | Low (not in POC) | High | Out of POC scope; internal storage only |
| Flash wear from constant overwrite | Low | Low | Modern phone storage handles it well; 3–5 min segments limit metadata churn |
| CameraX device quirks (FPS ranges, 4K stabilization, lens enumeration) | Medium | Medium | Capability probe at first launch; hide unsupported options rather than fail |

## Privacy, security and legal

| Topic | Approach |
|---|---|
| Data location | Everything on the device by default. No analytics or crash reporting unless the user opts in. |
| Audio consent | Audio toggle per mode; first-run notice that recording conversations may need consent in some regions |
| Dashcam laws | Notice that rules differ by country (windshield mounting, publishing footage of others, GDPR in the EU when footage identifies people) |
| Location data | Stored locally; removed from exports unless the user chooses to include it |
| Library access | App lock (PIN / biometric) in Phase 2; excluded from Android backup (`android:allowBackup` limited to settings) |
| Evidence integrity | Hash chain per segment (Phase 2), so exported incidents can be verified |
| Sharing | Share = exported copy; originals are never shared directly |

## Honest-limits UX

- **Capabilities screen** (Settings → "What your phone supports"): resolution/FPS, stabilization, slow-motion speeds, number of lenses, whether background auto-start is possible, and OEM battery status.
- **Status chips on the recording screen:** "Background OK", "Battery optimization ON — recording may stop", "Thermal: warm".
- **Post-session summary** when anything went wrong: "Recording paused for 42 s at 08:41 — camera was used by another app."
