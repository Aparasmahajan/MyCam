# FRAME — Proof of Concept Specification

*Camera · Recorder · Studio* — as of 2026-10-26

FRAME is one Android app with five modules: **Dashcam**, **Sports**, **Pro Camera**, **Studio** (editor) and **Library & Storage Manager**. All five share one camera engine and one media library. They are not five separate apps.

The POC has one main job: to show that a phone can **record for 8+ hours in short segments and automatically delete old, unprotected footage, without ever losing a protected clip or the file that is currently being written**. Everything else in the product depends on this engine.

## Documents

| # | Document | What it covers |
|---|---|---|
| 01 | [Vision, goals and scope](01-vision-and-scope.md) | Product vision, POC goals, assumed decisions, in/out of scope |
| 02 | [Smart Loop recording engine](02-smart-loop-engine.md) | Segments, retention policy, cleanup algorithm, protection, event buffer, recovery |
| 03 | [Dashcam mode](03-dashcam-mode.md) | Loop recording, G-sensor, GPS overlay, auto-start, background limits |
| 04 | [Sports mode](04-sports-mode.md) | Long matches, highlight markers, timer, scoreboard overlay |
| 05 | [Camera, library and storage manager](05-camera-library-storage.md) | Capture controls, library browsing, storage dashboard, trim and export |
| 06 | [Extra features (recommended)](06-extra-features.md) | Features I recommend adding: required ones and nice-to-have ones |
| 07 | [Architecture and tech stack](07-architecture.md) | Module layout, layers, libraries, threading, file layout |
| 08 | [Data model](08-data-model.md) | Room entities, relationships, indexes |
| 09 | [Platform limits and risks](09-platform-limits-and-risks.md) | Android background rules, thermals, battery, OEM killers, privacy and law |
| 10 | [Test plan and success criteria](10-test-plan.md) | Scenarios, device matrix, measurable pass criteria |
| 11 | [Milestones](11-milestones.md) | Week-by-week plan for the 8-week POC, plus the later phases |
| 12 | [Open questions](12-open-questions.md) | Decisions still needed and next steps |
| 13 | [End-to-end architecture plan](13-architecture-plan.md) | Principles, system context, layers, runtime flows, backend, security, observability, CI/CD, ADRs |
| 14 | [Tools and tech stack](14-tools-and-tech-stack.md) | Every tool, library and service from the laptop to the Play Store and the cloud, tagged by phase |
| 15 | [Gapless writer spike](15-gapless-writer-spike.md) | Design and verification plan for the zero-gap, crash-safe segment writer |

## POC at a glance

- **Platform:** Android 10+ (API 29), native Kotlin and Jetpack Compose, CameraX. iOS comes later.
- **Storage:** offline-first. Everything stays on the device, and no account is needed.
- **Duration:** about 8 weeks for one developer.
- **Exit criteria:** an 8-hour loop-recording run on 3 test devices with no lost protected clips, no crashes, and at most one segment lost after a forced kill. See [10-test-plan.md](10-test-plan.md).
