# 13 — End-to-end architecture plan

This plan covers FRAME from end to end: the Android app, the on-device recording engine, the optional cloud backend, CI/CD, observability and security. It shows how each piece grows from the POC to the full product. For engine internals (Gradle modules, threading, `RecorderController`), see [07-architecture.md](07-architecture.md). For every tool and library, see [14-tools-and-tech-stack.md](14-tools-and-tech-stack.md).

## 1. Architecture principles

| # | Principle | What it means in practice |
|---|---|---|
| P1 | **Local-first** | Recording, retention, library and export all work with no network and no account. The cloud is opt-in (Phase 5). |
| P2 | **Files are the source of truth** | Video files plus JSON sidecars hold the data. Room is an index that can be rebuilt from them. |
| P3 | **The engine outlives the UI** | Recording runs in a foreground service. Screens attach to it and detach from it. |
| P4 | **One engine, many modes** | Dashcam, Sports and Camera are *profiles* (settings + UI) on the same engine, not separate pipelines. |
| P5 | **Never lose protected footage** | Cleanup is a separate, testable component with hard rules: never delete protected or active segments. |
| P6 | **Fail loudly** | Every stop, step-down or error is logged and shown to the user. Nothing is silent. |
| P7 | **Modular and testable** | Feature modules depend on core modules only. Logic sits behind interfaces (`Clock`, `StorageStats`, `FileDeleter`) so it can run in unit tests. |
| P8 | **Native where it matters** | Camera, encoding and background work are native per platform. Shared logic can move to Kotlin Multiplatform when iOS starts. |

## 2. System context (end to end)

```mermaid
flowchart LR
    U((User))
    subgraph Phone["Android phone"]
        APP[FRAME app<br/>UI + recording service]
        OS[Android OS<br/>CameraX · MediaCodec · sensors · GPS<br/>WorkManager · notifications]
        FS[(App storage<br/>segments + sidecars)]
        MS[(MediaStore<br/>Movies/FRAME exports)]
    end
    W[Wear OS watch<br/>Phase 3]
    P2[Other phones<br/>match sync · Phase 5]
    subgraph Cloud["Optional cloud (Phase 5)"]
        API[FRAME API<br/>Spring Boot]
        DB[(PostgreSQL)]
        OBJ[(Object storage<br/>S3 / R2 / MinIO)]
        AUTH[Identity provider]
    end
    subgraph Dev["Delivery"]
        GH[GitHub<br/>repo + Actions]
        PLAY[Google Play<br/>internal → production]
        CRASH[Crash reporting<br/>opt-in]
    end

    U --> APP
    APP <--> OS
    APP <--> FS
    APP --> MS
    W -. highlight / save .-> APP
    P2 <-. Wi-Fi Direct .-> APP
    APP -. opt-in sync .-> API
    APP -. presigned upload .-> OBJ
    API --> DB
    API --> OBJ
    API --> AUTH
    GH --> PLAY --> APP
    APP -. opt-in .-> CRASH
```

Solid lines exist in the POC. Dotted lines come in later phases.

## 3. On-device layered architecture

FRAME uses a **unidirectional data flow (UDF)** with a clean-architecture split. The recording engine is a long-lived service in the data layer.

```mermaid
flowchart TB
    subgraph Presentation["Presentation  (:feature:*)"]
        SCR[Compose screens]
        VM[ViewModels<br/>StateFlow&lt;UiState&gt; · onEvent]
    end
    subgraph Domain["Domain  (:core:domain)"]
        UC[Use cases<br/>StartRecording · MarkEvent · RunCleanup<br/>ExportClip · ProtectSegment]
    end
    subgraph Data["Data  (:core:data · :core:recording · :core:retention · :core:storage)"]
        REPO[Repositories<br/>Session · Segment · Event · Policy · Telemetry]
        ENG[Recording engine<br/>RecordingService + RecorderController]
        RET[Retention engine]
    end
    subgraph Sources["Data sources / platform"]
        ROOM[(Room)]
        DS[(Proto DataStore)]
        FILES[(Files + sidecars)]
        CAMX[CameraX / MediaCodec]
        SENS[Sensors · GPS · thermal · battery]
        WM[WorkManager]
    end

    SCR --> VM --> UC
    UC --> REPO & ENG & RET
    REPO --> ROOM & DS & FILES
    ENG --> CAMX & SENS & FILES & REPO
    RET --> REPO & FILES
    WM --> RET
```

| Layer | Responsibility | Depends on |
|---|---|---|
| Presentation | Rendering, user input, navigation; holds no business logic | Domain |
| Domain | Use cases: pure Kotlin, no Android types | Interfaces only |
| Data | Repositories, engines, mapping between entities and domain models | Platform APIs |
| Platform | Android SDK, CameraX, Room, WorkManager | — |

## 4. Module dependency graph

```mermaid
flowchart TB
    app[":app"]
    subgraph features[":feature"]
        fd[dashcam] & fs[sports] & fc[camera] & fl[library] & fst[storage] & fse[settings]
    end
    subgraph core[":core"]
        ui[ui] & dom[domain] & rec[recording] & ret[retention] & cam[camera]
        sen[sensors] & med[media] & data[data] & sto[storage] & mod[model]
    end
    app --> features
    features --> ui & dom
    dom --> mod
    rec --> cam & sen & sto & data
    ret --> data & sto
    med --> sto
    data --> mod
    sto --> mod
    app --> rec & ret & med
```

Rules, checked in CI by the Gradle `dependency-analysis` plugin and a module-graph assertion:

- `:feature:*` never depends on another `:feature:*`.
- `:core:domain` and `:core:model` have no Android dependencies (plain JVM modules).
- Only `:core:recording` touches CameraX and MediaCodec.

## 5. Key runtime flows

### 5.1 Start recording

```mermaid
sequenceDiagram
    actor User
    participant UI as Dashcam screen
    participant VM as DashcamViewModel
    participant SVC as RecordingService
    participant CAM as CameraX
    participant SW as SegmentWriter
    participant DB as Room

    User->>UI: Tap Start (or QS tile / notification)
    UI->>VM: onEvent(Start)
    VM->>SVC: startForegroundService(profile)
    SVC->>SVC: startForeground(type = camera|mic|location)
    SVC->>CAM: bind(VideoCapture [+ Preview if UI visible])
    SVC->>DB: insert RecordingSession
    SVC->>SW: openSegment(1)
    SW->>DB: insert Segment(WRITING)
    SW-->>SVC: first frame written
    SVC-->>VM: state = Recording(session, seg 1)
    VM-->>UI: show REC, timer, storage left
```

### 5.2 Segment rollover and cleanup

```mermaid
sequenceDiagram
    participant SW as SegmentWriter
    participant FS as Files
    participant DB as Room
    participant EV as EventManager
    participant RET as RetentionEngine

    SW->>SW: duration reached (at a keyframe)
    SW->>SW: open seg N+1 (WRITING)
    SW->>FS: close seg N, write sidecar
    SW->>DB: seg N → COMPLETE (size, duration)
    SW->>EV: segmentCompleted(N)
    EV->>DB: link to open event windows (protect)
    SW->>RET: trigger(SEGMENT_DONE)
    RET->>DB: query eligible (not protected, not active)
    RET->>FS: delete expired / oldest until reserve is met
    RET->>DB: rows → DELETED + CleanupLog
```

### 5.3 Event protection (G-sensor, SAVE, highlight)

```mermaid
sequenceDiagram
    participant SRC as G-sensor / SAVE / Highlight
    participant EV as EventManager
    participant DB as Room
    participant WM as WorkManager

    SRC->>EV: event(t, type)
    EV->>DB: insert Event(window = t-before .. t+after, open = true)
    EV->>DB: link segments overlapping the window (incl. WRITING)
    Note over EV: when the recording passes t+after
    EV->>DB: link the last segments, Event.open = false
    EV->>WM: enqueue ExtractClipWorker (optional)
    WM->>WM: Media3 Transformer cuts the exact window into event_*.mp4
```

### 5.4 Crash recovery at startup

```mermaid
flowchart LR
    A[App / service start] --> B{Rows in WRITING<br/>or FINALIZING?}
    B -- no --> E[Normal start]
    B -- yes --> C[Inspect file]
    C -- fMP4 --> D1[Truncate to last fragment<br/>→ COMPLETE]
    C -- MP4 without moov --> D2[→ CORRUPT]
    D1 & D2 --> F[Scan disk for files without rows<br/>→ rebuild from sidecars]
    F --> G[Run cleanup]
    G --> H{Last session crashed<br/>and resume enabled?}
    H -- yes --> I[Notification: Tap to resume]
    H -- no --> E
```

## 6. State management

- Each screen has one `UiState` data class, exposed as `StateFlow` from its ViewModel, plus one `onEvent(UiEvent)` entry point (MVI-style UDF).
- The engine exposes `StateFlow<RecorderState>` and `StateFlow<HealthStatus>`. ViewModels combine them with repository flows.
- One-off effects (snackbars, navigation) go through a `Channel` and are collected with `LaunchedEffect`.
- Configuration and process death: UI state is rebuilt from the service and the DB. The service holds the real recording state, so there's nothing to save in `SavedStateHandle` except form input.

## 7. Background execution model

| Work | Mechanism | Runs when |
|---|---|---|
| Recording | Foreground service `camera\|microphone\|location` with a persistent notification | During a session, screen on or off |
| Cleanup during recording | In-process coroutine (mutex) | After each segment, and on a low-space check |
| Cleanup while idle | `PeriodicWorkRequest` (1 h, flex 15 min) | Device idle-friendly |
| Event clip extraction, thumbnails, highlight reel | `OneTimeWorkRequest` with an expedited request for user-triggered exports | After events / on demand |
| Auto-start triggers | Broadcast receivers (power, Bluetooth ACL) → a high-priority notification | Charger / car BT connected |
| Upload / backup (Phase 5) | `WorkManager` with network + charging constraints, resumable | Opt-in only |

## 8. Data and storage architecture

```mermaid
flowchart LR
    subgraph Truth["Source of truth"]
        V[segment .mp4]
        J[sidecar .json]
        T[telemetry.jsonl]
    end
    subgraph Index["Index (rebuildable)"]
        R[(Room DB)]
    end
    subgraph Config
        P[(Proto DataStore<br/>policies · profiles · prefs)]
    end
    subgraph Shared["Shared storage"]
        M[(MediaStore Movies/FRAME<br/>exports only)]
    end
    V & J & T -- recovery scan --> R
    R -- library queries --> UI[UI]
    P --> ENG[Engine + retention]
    V -- Transformer export --> M
```

- **Location:** app-specific external storage (`getExternalFilesDir`). No storage permission needed; only FRAME can delete these files.
- **Schema evolution:** Room auto-migrations with exported schemas checked into git, and a `MigrationTestHelper` test for every version.
- **Backup:** Android Auto Backup covers only DataStore settings. Videos are excluded (too big, and private).

## 9. Cloud backend architecture (Phase 5, optional)

The backend is **not needed for the POC**. When it comes, it is a Spring Boot modular monolith, which suits your Java background and keeps operations simple. The app never streams video through the API: it uploads straight to object storage using presigned URLs.

```mermaid
flowchart LR
    APP[FRAME app] -- HTTPS + JWT --> GW[API gateway / ingress]
    GW --> API
    subgraph API["Spring Boot modular monolith"]
        AU[auth module]
        LIB[library-sync module<br/>metadata, protected flags]
        UP[upload module<br/>presigned URLs, multipart]
        SH[sharing module<br/>links, expiring]
        SP[sports-events module<br/>shared matches, highlights]
        JOB[jobs module<br/>thumbnails, reels]
    end
    API --> PG[(PostgreSQL<br/>Flyway migrations)]
    API --> RD[(Redis<br/>rate limits, cache)]
    UP --> S3[(Object storage<br/>S3 / Cloudflare R2 / MinIO)]
    APP -- presigned PUT / multipart --> S3
    JOB --> Q[[Queue<br/>SQS / RabbitMQ]]
    Q --> WK[Worker<br/>FFmpeg in container]
    WK --> S3
    API --> IDP[Identity provider<br/>Keycloak / Auth0 / Firebase Auth]
    API --> OT[OpenTelemetry → Grafana]
```

**Upload flow:**

1. The app asks `POST /uploads` for a segment, sending its hash and size.
2. The API returns presigned multipart URLs.
3. The app uploads the parts in the background with WorkManager, retrying and resuming.
4. The app calls `POST /uploads/{id}/complete`. The API verifies the hash and records the object.

**Sync model:** last-writer-wins on metadata (protected, favorite, labels), using per-field timestamps. Video objects are immutable.

**Privacy:** opt-in per mode or per clip, client-side encryption as an option (Phase 5+), and a region choice for EU users.

## 10. Security architecture

| Area | Control |
|---|---|
| Data at rest (device) | App-specific storage. Optional encryption of protected clips with Jetpack Security / Tink (AES-GCM, key in Android Keystore) in Phase 2. |
| Library access | Biometric / PIN app lock (`BiometricPrompt`) in Phase 2 |
| Evidence integrity | SHA-256 per segment, chained (`prevSha256`); verification screen + exported manifest |
| Exports | Location and audio stripped unless the user includes them; originals never shared directly |
| Network (Phase 5) | TLS 1.2+, certificate pinning optional, short-lived JWT access tokens + refresh tokens, presigned URLs that expire in 15 min |
| Backend | Spring Security, per-user row ownership checks, rate limiting, OWASP dependency check, secrets in a vault / cloud secret manager |
| Supply chain | Gradle dependency verification metadata, Renovate/Dependabot, app signing with Play App Signing |
| Permissions | Requested just in time with a rationale screen; app works (with reduced features) if location or audio is denied |

## 11. Observability

| Signal | On device | Backend (Phase 5) |
|---|---|---|
| Logs | Timber → rolling file (7 days), exportable zip from Settings | Structured JSON logs → Loki / CloudWatch |
| Session timeline | `recording_log` table (start, rollovers, thermal, errors) | — |
| Crashes / ANRs | Opt-in Crashlytics or Sentry; Android vitals in the Play Console | Sentry |
| Metrics | Debug HUD: FPS, bitrate, dropped frames, temperature, free space | Micrometer → Prometheus → Grafana |
| Tracing | Perfetto system traces during profiling | OpenTelemetry |
| Performance | Macrobenchmark + Baseline Profiles in CI | Load tests with k6 / Gatling |

## 12. CI/CD pipeline

```mermaid
flowchart LR
    PR[Pull request] --> L[Lint<br/>detekt · ktlint · Android Lint]
    L --> UT[Unit tests<br/>JUnit5 · Kotest · Robolectric]
    UT --> B[Build debug + release APK/AAB]
    B --> IT[Instrumented tests<br/>Gradle Managed Devices]
    IT --> M{Merge to main}
    M --> FTL[Firebase Test Lab<br/>real-device matrix]
    M --> NB[Nightly: long-run tests<br/>on lab phones]
    FTL --> REL[Sign AAB<br/>Play App Signing]
    REL --> INT[Play internal testing]
    INT --> CB[Closed beta]
    CB --> PROD[Production<br/>staged rollout 5% → 100%]
```

| Stage | Tooling | Gate |
|---|---|---|
| Static analysis | detekt, ktlint (Spotless), Android Lint | No new warnings at error level |
| Unit tests | JUnit5, Kotest property tests, MockK, Turbine | Retention invariants 100% green; coverage at least 80% on `:core:retention` |
| UI / instrumented | Compose UI tests, Gradle Managed Devices (API 29, 34, 35) | Green |
| Real devices | Firebase Test Lab (Pixel, Samsung, Xiaomi) | Green on the smoke suite |
| Long-run (nightly) | `adb` harness + ffprobe on a self-hosted runner with phones attached | 8 h pass rate on the 3 reference phones |
| Release | Gradle Play Publisher or Fastlane `supply` | Manual approval for production |

## 13. Environments

| Environment | App build | Backend | Who |
|---|---|---|---|
| Local | `debug` (debug HUD, StrictMode, LeakCanary) | Docker Compose (Postgres, MinIO, Keycloak) | Developer |
| Internal | `internal` flavor, R8 on | Staging (Phase 5) | Team + testers |
| Beta | `release`, Play closed testing | Staging | Beta users |
| Production | `release`, staged rollout | Production | Everyone |

## 14. Architecture decision records (ADRs)

Each ADR gets a file in `docs/adr/` (`NNNN-title.md`: context, decision, consequences). Here is the first set:

| ADR | Decision | Status |
|---|---|---|
| 0001 | Native Android (Kotlin + Compose) first; no React Native / Flutter for the recorder | Accepted |
| 0002 | CameraX for capture; Camera2 interop only where needed | Accepted |
| 0003 | Segment writer: Option A (CameraX stop/start) now, Option B (MediaCodec + fMP4) behind a flag | Proposed (Week 1 spike) |
| 0004 | Files + sidecars are the source of truth; Room is a rebuildable index | Accepted |
| 0005 | Recordings in app-specific storage; exports through MediaStore | Accepted |
| 0006 | Protection derived from event links + manual flag (no stored PROTECTED state) | Accepted |
| 0007 | Media3 (Transformer, ExoPlayer, Muxer) instead of bundling FFmpeg on the device | Accepted |
| 0008 | Backend = Spring Boot modular monolith + PostgreSQL + S3-compatible storage; Phase 5 only | Proposed |
| 0009 | iOS via native Swift/AVFoundation; share domain logic with Kotlin Multiplatform if useful | Deferred |

## 15. How the architecture grows by phase

| Phase | Added to the architecture |
|---|---|
| POC / Phase 1 | Engine service, Room index, retention engine, Dashcam/Sports/Camera profiles, library, CI with unit and instrumented tests |
| Phase 2 | Hash chain, app lock, encryption of protected clips, parking-mode pipeline (time-lapse), incident PDF generator, kiosk/device-owner mode, Quick Share |
| Phase 3 | Camera2 manual controls layer, GPU analysis pipeline (histogram, peaking), Wear OS module (`:wear`) with the Data Layer API, profiles + schedules |
| Phase 4 | `:feature:studio` with a composition model (tracks, clips, effects) on Media3 Transformer, project files (JSON), undo/redo |
| Phase 5 | On-device ML (`:core:ml`: LiteRT, ML Kit, whisper.cpp), cloud backend + sync module (`:core:sync`), multi-phone sync over Wi-Fi Direct, RTMP streaming, iOS app |
