# 04 — Sports mode

Sports mode records an entire match (cricket, football, badminton and others) as one logical **match session** split into segments. The user can mark highlights without interrupting the recording.

## User flow

1. **New match:** choose the sport, enter the two team names (optional), and pick the period structure (for example 2 × 45 min halves, 20 overs per innings, best of 3 games).
2. Mount the phone on a tripod and frame the field. A fixed-framing lock keeps the zoom and exposure steady.
3. Tap **Start match**. Recording, the match timer and the scoreboard all start.
4. During play, tap **★ Highlight** (or a volume key or a Bluetooth remote) for a goal, wicket or great rally. Recording continues.
5. Update the score with the +/− buttons on the scoreboard overlay.
6. Use **End period** / **Start period** for halftime or innings breaks. Recording can pause during breaks, with the match session kept the same.
7. **End match** shows the highlight list. From there the user can export single highlights or an auto-joined highlight reel.

## Features in the POC

| Feature | Details |
|---|---|
| Long-duration recording | Up to 6 h tested. 5-minute segments, retention set to "Never" by default, and the reserve still enforced |
| Match session | Groups all segments, periods, highlights and the score timeline under one `Match` record |
| Highlight marker | Default 20 s before and 10 s after the tap; adjustable per sport (for example a cricket wicket 15 s / 10 s) |
| Highlight types | Goal, Wicket, Point, Save, Foul, Custom. A long press on ★ shows the type picker; a single tap saves Custom. |
| Match timer | Counts up or down, with per-period clock and stoppage time |
| Score overlay | Team names and score drawn on the preview. **Burned-in is optional**; the score is always stored as timestamped `ScoreChange` rows. |
| Fixed framing | Locks AE/AF/zoom (`CameraControl` + `FocusMeteringAction` with auto-cancel turned off) |
| Highlight list | Thumbnails, time, type, score at that moment; play, trim, export |
| Highlight reel | Joins all highlights (or a selection) into one MP4 with Media3 Transformer, with an optional title card per clip |
| Playback controls | 0.25×, 0.5×, 1×, 2× speed; frame step (in the library player) |

## Sport presets

| Sport | Periods | Default highlight window (before / after) | Score model |
|---|---|---|---|
| Football | 2 × 45 min | 20 s / 10 s | Goals |
| Cricket | Innings × overs | 15 s / 10 s | Runs / wickets |
| Badminton | Best of 3 games to 21 | 10 s / 5 s | Points per game |
| Basketball | 4 × 10/12 min | 15 s / 5 s | Points |
| Custom | User defined | 20 s / 10 s | Simple counter per team |

## Slow motion

- When the device supports high-speed capture (`CameraInfo` / `Camera2` high-speed sessions at 120 or 240 fps), the user can record a **slow-motion match**. This uses much more storage and heat, so the app shows an estimate of the cost first.
- More practical for the POC: record at 60 fps where possible and use 0.5× playback for highlights.

## Highlight reel export

```mermaid
flowchart LR
    H[Highlights<br/>selected] --> C[Compute clip ranges<br/>from segment timestamps]
    C --> T[Media3 Transformer<br/>Composition of EditedMediaItems]
    T --> O[Optional score overlay<br/>+ title cards]
    O --> E[Export preset<br/>1080p / 720p / 9:16 social]
    E --> L[Saved to library<br/>+ MediaStore Movies/FRAME]
```

## Later (not in the POC)

- Telestration: drawing on paused frames
- Side-by-side comparison of two clips
- Player / team tags on highlights, with stats per player
- Multi-phone sync: several phones film the same match, with a shared clock and highlights mirrored over Wi-Fi Direct
- Auto-highlights from audio peaks (crowd noise) and scoreboard changes
- Live streaming (RTMP) to YouTube
