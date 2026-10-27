# 12 — Open questions and next steps

## Decisions needed

| # | Question | Default used in this spec | Needed by |
|---|---|---|---|
| Q1 | Target platform: Android first, iPhone first, both, or desktop? | Android first, native | Before Week 1 |
| Q2 | Primary use: dashcam, sports, both, or pro camera / editing? | Dashcam and Sports equally | Before Week 1 |
| Q3 | Storage: offline-only, local plus optional cloud, or cloud from day one? | Offline-first | Phase 5 planning |
| Q4 | Editing ambition: basic, advanced timeline, or professional-grade? | Basic in the POC; advanced in Phase 4 | Phase 4 planning |
| Q5 | Segment writer: is a short gap (Option A) acceptable for the first release, or is gapless (Option B) required? | Build A, then B behind a flag | End of Week 1 spike |
| Q6 | Burned-in overlay or metadata-only overlay as the default for dashcam? | Burned-in time + speed | Week 6 |
| Q7 | Default audio recording in dashcam mode: on or off? | Off | Week 6 |
| Q8 | Which test devices are available (one Pixel, one Samsung, one Xiaomi)? | To be confirmed | Week 1 |
| Q9 | Distribution for testers: APK sideload or Play internal testing? | Play internal testing | Week 2 |
| Q10 | App name and package: is "FRAME" / `io.frame.app` final? | Placeholder | Before any store listing |
| Q11 | Monetization (free, one-time pro unlock, subscription for cloud)? | Not decided; it doesn't affect the POC | Phase 5 |

## Next steps

- [ ] Confirm Q1–Q4 (or accept the defaults).
- [ ] Get the 3 test devices and a car mount + charger for drive tests.
- [ ] Create the Android project with the module layout in [07-architecture.md](07-architecture.md).
- [ ] Run the Week 1 spike: record 10 × 1-min segments with Options A and B, measure the gaps with ffprobe.
- [ ] Write the retention engine tests *before* the engine (the invariants: never delete protected or active footage, always keep the reserve).
- [ ] Set up the long-run harness script early, so every build after Week 2 gets an overnight run.
