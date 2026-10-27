# 14 — Tools and tech stack (end to end)

This is every tool, library and service FRAME uses, from the developer's laptop to the Play Store and the optional cloud. Each item is tagged with the phase it's needed in.

**Phases:** **POC** = needed now · **P2–P5** = product phases (see [11-milestones.md](11-milestones.md)) · **Opt** = optional / alternative

Pin exact versions in `gradle/libs.versions.toml` (a version catalog) when the project is created. Use the latest stable release of each library at that time.

## 1. Development environment

| Tool | Purpose | Phase |
|---|---|---|
| Android Studio (latest stable) | IDE, emulator, profilers, Layout/Compose inspector | POC |
| JDK 17 (bundled JBR) | Gradle and Android builds | POC |
| JDK 21 | Spring Boot backend | P5 |
| IntelliJ IDEA | Backend development | P5 |
| Xcode | iOS app | P5 |
| Git + GitHub | Source control, PRs, Issues | POC |
| `adb`, `scrcpy` | Device control, screen mirroring for drive tests and demos | POC |
| FFmpeg / `ffprobe` (desktop) | Checking segment gaps, codecs and timestamps in tests | POC |
| MediaInfo | Quick inspection of video containers and metadata | POC |
| Perfetto UI | System traces (CPU, thermal, encoder threads) | POC |
| Docker Desktop / Podman | Local backend stack | P5 |
| Figma | UI design, prototypes, design tokens | POC |
| Mermaid (in Markdown) | Architecture and flow diagrams in `docs/` | POC |

## 2. Android app — language and build

| Tool / library | Purpose | Phase |
|---|---|---|
| Kotlin 2.x | Main language | POC |
| Kotlin Coroutines + Flow | Concurrency, reactive state | POC |
| Gradle (Kotlin DSL) + version catalog | Build | POC |
| Android Gradle Plugin | Android build | POC |
| KSP | Annotation processing (Room, Hilt) | POC |
| Gradle convention plugins (`build-logic/`) | Shared module configuration | POC |
| R8 | Code shrinking and obfuscation in release builds | POC |
| Baseline Profiles | Faster startup and less jank | P2 |
| `dependency-analysis` Gradle plugin | Enforces module boundaries, finds unused dependencies | POC |

## 3. Android app — UI

| Library | Purpose | Phase |
|---|---|---|
| Jetpack Compose + Material 3 | All UI | POC |
| Navigation Compose (type-safe routes) | Navigation between modules | POC |
| Lifecycle / ViewModel (Compose) | State holders, `collectAsStateWithLifecycle` | POC |
| Coil 3 | Thumbnails (video frame decoding) | POC |
| Accompanist / Compose permissions (or a hand-rolled helper) | Runtime permission flows | POC |
| Compose Material icons (extended) | Icons | POC |
| Glance | Home-screen widget (Start dashcam, storage left) | POC |
| `TileService` (framework) | Quick Settings tile | POC |
| Wear Compose + Horologist | Watch highlight button | P3 |

## 4. Android app — camera, media and recording

| Library / API | Purpose | Phase |
|---|---|---|
| CameraX `camera-core`, `camera-camera2`, `camera-lifecycle` | Camera session, preview, photo | POC |
| CameraX `camera-video` | `Recorder` / `VideoCapture` (segment writer Option A) | POC |
| CameraX `camera-effects` (`OverlayEffect`) | Burned-in time, speed and score overlays | POC |
| CameraX `camera-extensions` | Night / HDR / bokeh where the OEM supports them | P3 |
| Camera2 interop | FPS ranges, physical lenses, manual ISO/shutter/focus | POC (partial) / P3 |
| `MediaCodec` (framework) | Hardware H.264/HEVC encoding (Option B) | POC |
| Media3 Muxer (`FragmentedMp4Muxer`) | Crash-safe fragmented MP4 segments (Option B) | POC |
| `AudioRecord` + AAC `MediaCodec` | Audio capture and level meter (Option B) | POC |
| Media3 ExoPlayer + `media3-ui-compose` | Playback across segments, event markers, speed control | POC |
| Media3 Transformer | Trim, concatenate, highlight reels, overlays, export presets | POC |
| Media3 Effect | Text overlays, color adjustments, LUTs | P4 |
| OpenGL ES / AGSL shaders | Focus peaking, histogram, zebras | P3 |
| RTMP library (for example a maintained RootEncoder) | Live streaming | P5 |

> **FFmpeg on the device:** avoid it. `ffmpeg-kit` has been retired, and bundling FFmpeg brings LGPL/GPL licensing duties and a large APK. Media3 covers the POC's needs. Use FFmpeg only on the desktop (tests) and on the backend (workers).

## 5. Android app — data, background work and platform

| Library / API | Purpose | Phase |
|---|---|---|
| Room (SQLite) | Index of sessions, segments, events, telemetry, logs | POC |
| Proto DataStore | Retention policies, profiles, preferences | POC |
| kotlinx.serialization | Sidecar JSON, telemetry JSONL | POC |
| WorkManager | Periodic cleanup, clip extraction, thumbnails, uploads | POC |
| Foreground service (`camera\|microphone\|location`) | Continuous recording with the screen off | POC |
| `SensorManager` (linear acceleration) | G-sensor incident detection | POC |
| Play Services Location (`FusedLocationProviderClient`) | GPS and speed | POC |
| `PowerManager` thermal API + `BatteryManager` | Thermal and battery guard | POC |
| `StatFs` / `StorageManager` | Free and total space, storage budget | POC |
| `MediaStore` | Exporting to `Movies/FRAME` | POC |
| Bluetooth (`BluetoothDevice.ACTION_ACL_CONNECTED`) | Auto-start on car Bluetooth | POC |
| Hilt | Dependency injection (the POC uses a small manual `AppContainer`; switch to Hilt as modules grow) | P2 |
| Timber + file logging tree | Logs, exportable diagnostics | POC |
| `BiometricPrompt` | App lock | P2 |
| Tink / Jetpack Security + Android Keystore | Encrypting protected clips | P2 |
| `java.security.MessageDigest` (SHA-256) | Tamper-evident hash chain | P2 |
| Android `PdfDocument` | Incident report PDF | P2 |
| Google Maps SDK / Maps Compose, or osmdroid / MapLibre | Trip and incident maps (MapLibre avoids per-load fees) | P2 |
| Wi-Fi Direct / Nearby Connections API | Phone-to-phone sharing, multi-phone match sync | P2 / P5 |
| Wearable Data Layer API | Phone ↔ watch messages | P3 |
| `DevicePolicyManager` (device owner / lock task) | Kiosk "dedicated dashcam" mode | P2 |

## 6. On-device AI / ML (Phase 5)

| Library | Purpose |
|---|---|
| ML Kit (face detection, object detection) | Privacy blur, simple object tracking |
| LiteRT (formerly TensorFlow Lite) | Custom models: plate detection, audio event (cheer/whistle) classifier |
| MediaPipe Tasks | Pose / object tracking for sports analysis |
| whisper.cpp (Android build) | On-device speech-to-text for transcript search |
| YAMNet (LiteRT) | Audio event classification for auto-highlights |

## 7. Testing and quality

| Tool | Purpose | Phase |
|---|---|---|
| JUnit 5 (android-junit5 plugin) | Unit tests | POC |
| Kotest (property-based testing) | Retention invariants with random segment/event layouts | POC |
| MockK | Mocking | POC |
| Turbine | Testing Flows | POC |
| Robolectric | JVM tests for Android components | POC |
| Room `MigrationTestHelper` | Database migration tests | POC |
| Compose UI Test | UI tests | POC |
| Gradle Managed Devices | Emulators in CI (API 29 / 34 / 35) | POC |
| Firebase Test Lab | Real-device matrix (Pixel, Samsung, Xiaomi) | POC |
| Macrobenchmark + Baseline Profile generator | Startup, time to first frame, jank | POC / P2 |
| LeakCanary | Memory leaks (debug builds) | POC |
| StrictMode | Disk and network access on the main thread (debug builds) | POC |
| detekt + ktlint (via Spotless) | Static analysis and formatting | POC |
| Android Lint | Android-specific checks | POC |
| Long-run harness (shell + `adb` + `ffprobe`) | 8-hour recording, gap and recovery checks | POC |
| Testcontainers | Backend integration tests (Postgres, MinIO) | P5 |
| k6 or Gatling | Backend load tests | P5 |

## 8. CI/CD and release

| Tool | Purpose | Phase |
|---|---|---|
| GitHub Actions | CI: lint, tests, builds, emulator tests | POC |
| Self-hosted runner with phones attached | Nightly long-run device tests | POC |
| Gradle Build Cache (or Develocity) | Faster CI builds | Opt |
| Renovate or Dependabot | Dependency updates | POC |
| Gradle dependency verification | Supply-chain checks on artifacts | P2 |
| Play App Signing | Release signing key management | POC |
| Gradle Play Publisher or Fastlane `supply` | Uploading AABs to Play tracks | POC |
| Google Play Console (internal → closed → production, staged rollout) | Distribution | POC |
| Firebase App Distribution | Optional quick tester builds outside Play | Opt |
| Conventional Commits + release-please | Changelog and versioning | Opt |

## 9. Monitoring and analytics

| Tool | Purpose | Phase |
|---|---|---|
| Android vitals (Play Console) | Crashes, ANRs, battery, wake locks | POC |
| Firebase Crashlytics **or** Sentry (opt-in only) | Crash reports with breadcrumbs | P2 |
| On-device diagnostics zip | Logs + recording timeline sent by the user | POC |
| Privacy-friendly analytics (opt-in, for example PostHog self-hosted) | Feature usage | Opt |

## 10. Backend and cloud (Phase 5, optional)

| Tool / service | Purpose |
|---|---|
| Java 21 + Spring Boot 3 | API (modular monolith) |
| Spring Web, Spring Data JPA, Spring Validation | REST endpoints, persistence |
| Spring Security + OAuth2 Resource Server (JWT) | Authentication and authorization |
| Spring Modulith | Enforces module boundaries in the monolith |
| PostgreSQL | Users, devices, clip metadata, shared sports events |
| Flyway | Database migrations |
| Redis | Rate limiting, caching, short-lived share tokens |
| S3-compatible object storage (AWS S3, Cloudflare R2, or MinIO when self-hosted) | Video storage, presigned multipart uploads |
| Keycloak, Auth0 or Firebase Auth | Identity provider (sign in with Google) |
| SQS or RabbitMQ | Job queue for thumbnails and reels |
| FFmpeg in a worker container | Server-side transcoding and highlight reels |
| springdoc-openapi | API docs + generated Kotlin client (OpenAPI Generator) |
| Retrofit + OkHttp + kotlinx.serialization (app side) | Calling the API from Android |
| Docker | Packaging |
| AWS ECS Fargate / Google Cloud Run, or Kubernetes later | Hosting |
| Terraform | Infrastructure as code |
| AWS Secrets Manager / GCP Secret Manager | Secrets |
| Micrometer + Prometheus + Grafana | Metrics and dashboards |
| OpenTelemetry + Grafana Tempo | Distributed tracing |
| Loki or CloudWatch Logs | Centralized logs |
| OWASP Dependency-Check / Trivy | Vulnerability scanning of dependencies and images |

## 11. iOS (Phase 5, if needed)

| Tool | Purpose |
|---|---|
| Swift + SwiftUI | App and UI |
| AVFoundation (`AVCaptureSession`, `AVAssetWriter`) | Capture and segmented writing |
| Core Motion, Core Location | G-sensor and GPS |
| SwiftData or GRDB | Local index |
| AVPlayer, AVComposition | Playback and editing |
| Kotlin Multiplatform (optional) | Sharing retention logic and models with Android |
| XCTest, XCUITest | Tests |
| Xcode Cloud or Fastlane | CI and TestFlight |

## 12. Project management and documentation

| Tool | Purpose |
|---|---|
| GitHub Projects (or Jira) | Backlog, sprints, milestones |
| GitHub Issues templates | Bug reports with device, Android version and diagnostics zip |
| `docs/` Markdown + Mermaid | Specs, architecture, ADRs (`docs/adr/`) |
| Figma | Designs and the component library |
| Notion / Confluence (optional) | Wider team notes |

## Summary: the POC toolchain

The minimum set needed to start Week 1:

- **Build:** Android Studio, Kotlin 2.x, Gradle (Kotlin DSL and a version catalog), KSP, Hilt
- **UI:** Jetpack Compose with Material 3, Navigation Compose, Coil, Glance
- **Camera and media:** CameraX (core, video, effects), MediaCodec, Media3 (Muxer, ExoPlayer, Transformer)
- **Data and background:** Room, Proto DataStore, kotlinx.serialization, WorkManager, a foreground service, Play Services Location, `SensorManager`
- **Quality:** JUnit 5, Kotest, MockK, Turbine, Robolectric, Compose UI Test, detekt, ktlint, LeakCanary
- **Delivery:** GitHub, GitHub Actions, Gradle Managed Devices, Firebase Test Lab, Play Console internal testing
- **Verification:** adb, scrcpy, ffprobe, MediaInfo, Perfetto
