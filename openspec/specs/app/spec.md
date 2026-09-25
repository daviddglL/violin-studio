# Delta Spec: Production-Ready Refactor

## Purpose

Transform Violin Master from an AI Studio-generated prototype (`com.example`) into a production-ready Android app (`com.violinmaster.app`) suitable for Google Play release. This spec covers 10 capability areas: namespace migration, ViewModel decomposition, real tuner, Room migrations, Hilt DI, security hardening, test coverage, localization, build tooling, and Gemini API integration.

---

## SPEC-1: Namespace Migration

### REQ-NS-001 — Package Declarations

All Kotlin source files under `app/src/main/java/` and `app/src/test/` MUST use `package com.violinmaster.app` with sub-packages matching the current structure (e.g., `com.violinmaster.app.viewmodel`, `com.violinmaster.app.audio`, `com.violinmaster.app.data`, `com.violinmaster.app.ui.screens`, `com.violinmaster.app.ui.theme`, `com.violinmaster.app.security`).

**Scenarios**:

- GIVEN any `.kt` file under `app/src/main/java/com/example/` WHEN the namespace migration is complete THEN the physical directory path matches `com/violinmaster/app/` AND the `package` declaration reads `com.violinmaster.app` with the correct sub-package.

- GIVEN a source file with package `com.example.viewmodel` WHEN migrated THEN the new package is `com.violinmaster.app.viewmodel` AND all imports referencing `com.example.*` are updated to `com.violinmaster.app.*`.

**Acceptance**: `grep -r "package com.example" app/src/main/java/` returns zero results. `grep -r "import com.example" app/src/` returns zero results.

---

### REQ-NS-002 — AndroidManifest Package Reference

`AndroidManifest.xml` MUST reference the new package via the `package` attribute (if present) and its `android:name` attribute for `.MainActivity` MUST resolve to `com.violinmaster.app.MainActivity`.

**Scenarios**:

- GIVEN the current manifest has `android:name=".MainActivity"` WHEN the package is migrated THEN the activity resolves to `com.violinmaster.app.MainActivity` (explicit or relative-to-namespace).

- GIVEN the namespace is changed in `build.gradle.kts` WHEN the manifest is loaded by the build system THEN no "package not found" errors occur.

**Acceptance**: `./gradlew assembleDebug` succeeds with no manifest-related errors.

---

### REQ-NS-003 — build.gradle.kts Updates

`app/build.gradle.kts` MUST set `namespace = "com.violinmaster.app"` and `applicationId = "com.violinmaster.app"`.

**Scenarios**:

- GIVEN `namespace = "com.example"` currently WHEN migrated THEN the value is `"com.violinmaster.app"`.

- GIVEN `applicationId = "com.example"` currently WHEN migrated THEN the value is `"com.violinmaster.app"`.

**Acceptance**: Both values set to `"com.violinmaster.app"`. Build succeeds.

---

### REQ-NS-004 — All Imports Updated

Every `import` statement across the codebase that references `com.example.*` MUST be replaced with `com.violinmaster.app.*`.

**Scenarios**:

- GIVEN `ViolinViewModel.kt` imports `com.example.audio.ViolinAudioEngine` WHEN migrated THEN the import reads `com.violinmaster.app.audio.ViolinAudioEngine`.

- GIVEN a screen composable imports `com.example.viewmodel.ViolinViewModel` WHEN ViewModels are split into separate files THEN each screen imports its specific ViewModel from `com.violinmaster.app.viewmodel.*`.

**Acceptance**: `grep -r "import com.example" app/src/` returns zero results.

---

### REQ-NS-005 — Build Success

The project MUST build successfully (`./gradlew assembleDebug`) after namespace migration and all import updates.

**Scenarios**:

- GIVEN all package declarations, imports, manifest, and build.gradle.kts are updated WHEN `./gradlew assembleDebug` runs THEN the build completes with BUILD SUCCESSFUL.

- GIVEN the namespace migration is done incrementally (file by file) WHEN an intermediate build is attempted THEN the build fails fast with clear import errors rather than silent misbehavior.

**Acceptance**: `./gradlew assembleDebug` exits with code 0.

---

## SPEC-2: ViewModel Decomposition

### REQ-VM-001 — No ViewModel Exceeds 300 Lines

No single ViewModel class SHALL exceed 300 lines of source code. The current 804-line `ViolinViewModel.kt` MUST be split into focused ViewModels.

**Scenarios**:

- GIVEN the current `ViolinViewModel.kt` is 804 lines WHEN decomposed THEN the largest resulting ViewModel file has ≤300 lines AND each file handles a single domain concern.

- GIVEN any new feature is added to a ViewModel in the future WHEN the class approaches 300 lines THEN further additions require extraction to a new ViewModel or UseCase.

**Acceptance**: `wc -l` on each ViewModel file shows ≤300 lines.

---

### REQ-VM-002 — TunerViewModel

A `TunerViewModel` MUST handle all tuner state: note selection (G/D/A/E/null), pitch offset in cents (-50 to +50), listening toggle, auto-detect toggle, custom frequency entry, and reference pitch A value. It MUST delegate audio playback to the injected audio engine.

**Scenarios**:

- GIVEN the user taps a string note button (e.g., "A") WHEN `TunerViewModel.selectTunerNote("A")` is called THEN `tunerSelectedNote` state emits "A" AND the audio engine plays the A4 reference tone.

- GIVEN the user toggles listening mode WHEN `TunerViewModel.toggleListeningTuner()` is called THEN `isListeningTuner` flips from false→true AND audio capture begins (via the audio engine).

- GIVEN listening mode is active and auto-detect is enabled WHEN a note is detected by the pitch engine THEN `tunerSelectedNote` updates to the detected note automatically.

**Acceptance**: All tuner UI elements on `TunerScreen.kt` bind to `TunerViewModel` StateFlows. No tuner state in any other ViewModel.

---

### REQ-VM-003 — MetronomeViewModel

A `MetronomeViewModel` MUST handle all metronome state: BPM (40-240), time signature beats (2/3/4/6), accent toggle, play/pause, beat pulse index. It MUST delegate playback to the injected audio engine.

**Scenarios**:

- GIVEN the metronome is stopped WHEN `MetronomeViewModel.toggleMetronome()` is called THEN `isMetronomePlaying` becomes true AND the audio engine starts metronome click playback at the current BPM and time signature.

- GIVEN the metronome is playing at 100 BPM WHEN `MetronomeViewModel.updateMetronomeBpm(120)` is called THEN `metronomeBpm` state emits 120 AND the audio engine BPM updates without stopping playback.

- GIVEN the time signature is 4/4 and beats=4 WHEN `MetronomeViewModel.updateMetronomeBeats(3)` is called THEN `metronomeBeats` emits 3 AND if playing, the metronome restarts with 3-beat pattern.

**Acceptance**: All metronome UI elements on `MetronomeScreen.kt` bind to `MetronomeViewModel` StateFlows. No metronome state in any other ViewModel.

---

### REQ-VM-004 — AuthViewModel

An `AuthViewModel` MUST handle login, register, logout, passcode set/remove/verify, user session persistence via SharedPreferences, and role-based state (teacher/student/freelancer). It MUST delegate user CRUD to PracticeRepository.

**Scenarios**:

- GIVEN a user enters valid username and 4-digit PIN WHEN `AuthViewModel.login(username, pin)` is called THEN `currentUser` emits the UserAccount AND `loginError` is null.

- GIVEN a user enters a username that already exists WHEN `AuthViewModel.register(username, pin, role)` is called THEN `loginError` emits "error_user_exists" AND no user is created.

- GIVEN a user is logged in WHEN `AuthViewModel.logout()` is called THEN `currentUser` becomes null AND SharedPreferences "current_user_id" is removed.

- GIVEN passcode lock is set WHEN `AuthViewModel.authenticatePasscode(correctPin)` is called THEN `isUserAuthenticated` becomes true.

**Acceptance**: `AuthenticationScreen.kt` and passcode dialogs bind exclusively to `AuthViewModel`.

---

### REQ-VM-005 — PracticeViewModel

A `PracticeViewModel` MUST handle practice timer (start/pause/resume/stop-save/cancel), daily tasks (load/complete/score), session persistence, points earning, daily goal tracking, and quiz-gated skill level advancement. The `updateSkillLevel` method SHALL accept a quiz score parameter and only persist advancement when score ≥ 80.

**Scenarios**:

- GIVEN the user starts a practice session with category "Smart Tuner" WHEN `PracticeViewModel.startPracticeTimer("Smart Tuner")` is called THEN `isPracticing` becomes true AND `practiceElapsedSeconds` increments every second.

- GIVEN a practice session has elapsed 30 seconds WHEN `PracticeViewModel.stopAndSavePracticeSession()` is called THEN a PracticeSession is persisted to Room with durationSeconds=30 AND `isPracticing` becomes false.

- GIVEN a daily task is completed on first attempt WHEN `PracticeViewModel.completeDailyTask("task_1", 1)` is called THEN the user earns 100 points AND the task is marked completed in SharedPreferences for today.

- GIVEN user completed TheoryQuizTab with score 85 WHEN quiz summary triggers skill advancement THEN `updateSkillLevel("Intermediate", quizScore=85)` persists the new level.

- GIVEN user completed TheoryQuizTab with score 60 WHEN quiz summary triggers skill advancement THEN the skill level remains unchanged.

**Acceptance**: `HomeScreen.kt` practice timer controls, daily task UI, and quiz advancement bind exclusively to `PracticeViewModel`.

---

### REQ-VM-006 — AssignmentViewModel

An `AssignmentViewModel` MUST handle assignment CRUD (teacher publish, student view, mark complete, delete) and teacher-student linking via invite code. The `publishAssignment` method SHALL accept an optional real video URL from the upload pipeline, replacing the fake URL generation via VideoSecurityService.

**Scenarios**:

- GIVEN a teacher is logged in WHEN `AssignmentViewModel.publishAssignment(title, desc, student, video, duration)` is called THEN an Assignment entity is inserted into Room with the teacher's code.

- GIVEN a student is logged in with a linked teacher code WHEN assignments flow emits items THEN `studentAssignments` state updates reactively with filtered assignments for that student.

- GIVEN an assignment exists WHEN `AssignmentViewModel.markAssignmentComplete(id, true)` is called THEN the assignment's `completed` field is updated AND 200 points are earned.

- GIVEN teacher has recorded and uploaded a video (real Firebase URL) WHEN `publishAssignment` is called with the URL THEN the Assignment stores the real URL, not a generated fake signed URL.

**Acceptance**: `TeacherStudentWorkspace.kt` binds exclusively to `AssignmentViewModel` for assignment state.

---

### REQ-VM-007 — Behavioral Preservation

All existing UI behavior from the monolithic `ViolinViewModel` MUST be preserved exactly across the split ViewModels. No functional regressions.

**Scenarios**:

- GIVEN the original app's tuner needle animation behavior (easing interpolation, 120ms refresh, ±15 cent target range) WHEN the decomposed `TunerViewModel` runs THEN the needle behavior is identical.

- GIVEN the original app's practice timer minimum save threshold of 3 seconds WHEN `PracticeViewModel` handles timer completion THEN sessions under 3 seconds are discarded identically.

- GIVEN the original app's metronome tap tempo calculation (last 5 timestamps, average interval to BPM) WHEN `MetronomeViewModel` handles tap tempo THEN the computed BPM matches the original behavior exactly.

**Acceptance**: Manual side-by-side comparison of prototype vs refactored app shows identical UI responses for all interactions.

---

### REQ-VM-008 — Hilt-Injectable and Testable

All ViewModels MUST be annotated with `@HiltViewModel` and receive dependencies via constructor injection. Direct instantiation (`ViolinAudioEngine()`) and `AndroidViewModel(application)` pattern MUST be replaced with injected services.

**Scenarios**:

- GIVEN `TunerViewModel` is annotated with `@HiltViewModel` WHEN Hilt constructs it THEN `ViolinAudioEngine` and `PracticeRepository` are injected via constructor parameters.

- GIVEN a unit test instantiates a ViewModel WHEN the test provides mock dependencies via constructor THEN the ViewModel functions without Android framework dependencies (except where `Application` is genuinely needed for SharedPreferences, handled via Hilt-provided context).

**Acceptance**: Every ViewModel constructor accepts interfaces/services via parameters. No `companion object` singletons or manual `Application()` constructor calls remain.

---

### REQ-VM-009 — Optional Passcode Wall

The mandatory passcode authentication on MasterclassTab SHALL become optional. Users without a configured passcode SHALL access masterclass content directly. Users with a configured passcode SHALL continue to require PIN authentication. The `!isSecurityLocked` state in AuthViewModel MUST no longer force the passcode setup screen; it SHALL allow direct access to the content hub.

**Scenarios**:

- GIVEN no passcode is set in the system WHEN user navigates to MasterclassTab THEN the masterclass content hub renders immediately without lock screen.

- GIVEN a passcode is configured WHEN user navigates to MasterclassTab THEN the passcode lock screen appears and requires correct PIN.

**Acceptance**: Free-tier users can access masterclass content without PIN setup. Existing passcode users retain their lock behavior.

---

## SPEC-3: Real Tuner Implementation

### REQ-TN-001 — Microphone Audio Capture

The tuner MUST capture real-time audio from the device microphone via `AudioRecord`. A new `PitchDetectionEngine` class (or method on `ViolinAudioEngine`) SHALL manage the audio recording buffer.

**Scenarios**:

- GIVEN the user taps "START ACCURATE LISTEN MODE" WHEN `toggleListeningTuner()` is called THEN `AudioRecord` begins capturing PCM data at 44100 Hz sample rate, 16-bit, mono.

- GIVEN the microphone is capturing audio WHEN the user taps "STOP MICROPHONE LISTEN" THEN `AudioRecord.stop()` and `AudioRecord.release()` are called.

**Acceptance**: Real microphone audio feeds into the pitch detection algorithm. No `Random()` calls in the audio capture path.

---

### REQ-TN-002 — Pitch Detection Accuracy

The pitch detection algorithm MUST identify the fundamental frequency of a played note within ±2 cents of A4=440 Hz when tested with a clean sine wave input.

**Scenarios**:

- GIVEN a 440 Hz pure sine wave is fed to the pitch detector WHEN the algorithm processes a 2048-sample buffer THEN the detected frequency is between 439.5 Hz and 440.5 Hz (±2 cents).

- GIVEN a 196 Hz (G3) sine wave is fed WHEN processed THEN the detected frequency is between 195.77 Hz and 196.23 Hz.

**Acceptance**: Unit test with synthetic sine wave data confirms ±2 cent accuracy for G3, D4, A4, E5 frequencies.

---

### REQ-TN-003 — Note Mapping to Active Instrument Strings

The detected frequency MUST map to the closest string note of the ACTIVE instrument (violin, viola, or cello), not exclusively violin strings. The `YinPitchDetector.frequencyToNoteAndCents()` function SHALL accept an `instrument: Instrument` parameter and iterate `instrument.strings` instead of hardcoded `VIOLIN_NOTE_RATIOS`. Mapping SHALL respect the current reference pitch A value (default 440 Hz, adjustable).
(Previously: Note mapping was hardcoded to violin strings only — G3, D4, A4, E5.)

**Scenarios**:

- GIVEN detected frequency of 438 Hz and active instrument is Violin WHEN auto-detect is enabled THEN `tunerSelectedNote` emits "A" (closest to A4=440 Hz).

- GIVEN detected frequency of 130.0 Hz and active instrument is Viola WHEN auto-detect is enabled THEN `tunerSelectedNote` emits "C" (closest to C3=130.8 Hz).

- GIVEN detected frequency of 218 Hz and active instrument is Cello WHEN auto-detect is enabled THEN `tunerSelectedNote` emits "A" (closest to A3=220.0 Hz).

- GIVEN reference pitch is set to 442 Hz and active instrument is Violin WHEN a frequency of 445 Hz is detected THEN note maps to A4 relative to 442 Hz AND offset is calculated from 442 Hz.

**Acceptance**: For each instrument's open strings, the detected frequency within ±50 cents of the target maps to the correct note name.

---

### REQ-TN-004 — Pitch Offset Display with Configurable Range

The detected pitch offset in cents MUST be displayed on the UI needle gauge with smooth animation. The needle range is configurable via `maxCents` (default ±50 cents, configurable 25–200 in 25-step increments). The ViewModel SHALL NOT clamp pitch offsets — raw detected cent values pass through unmodified. When the offset exceeds `maxCents`, the needle pins at the arc edge and an overflow indicator label (e.g., `≥+200` or `≤-200`) is shown. The `TuningWheel` composable adapts tick density to the configured range: `(maxCents / 25) × 2` total ticks across a 120° arc (60° each side of center).

**Scenarios (updated — tuner-custom-configurations)**:

- GIVEN a detected frequency of 445 Hz with A4=440 Hz reference and maxCents=50 WHEN the offset is calculated THEN `tunerPitchOffsetCents` emits approximately +19.6 cents AND the needle points to the sharp side.

- GIVEN the offset is within ±2.5 cents of the target WHEN displayed THEN the needle turns green (`Color(0xFF81C784)`) and the label shows "PERFECTLY IN TUNE".

- GIVEN maxCents is set to 100 and the detected offset exceeds +100 cents WHEN displayed THEN the needle pins at the right arc edge AND the overflow label shows "≥+100".

- GIVEN the user changes maxCents from 50 to 200 WHEN `TuningWheel` recomposes THEN tick marks adapt to (200/25)×2 = 16 ticks across the arc.

**Acceptance**: The `TuningWheel` Canvas composable in `TunerScreen.kt` renders real YIN pitch data, adapts ticks to the configured maxCents range, and shows overflow indicators at arc edges with amber-colored pinned needle.

---

### REQ-TN-005 — Reference Tone Playback with Active Instrument

The reference tone playback MUST use the active instrument's string frequencies. `ViolinAudioEngine.playStringTone()` SHALL accept an `instrument: Instrument` parameter. Selecting a string note plays the correct frequency through `AudioTrack` using the current reference pitch and the instrument's string definitions.
(Previously: `playStringTone()` only played violin string frequencies; no instrument parameter existed.)

**Scenarios**:

- GIVEN active instrument is Violin and reference pitch is 440 Hz WHEN user taps the A string button THEN `playStringTone("A", 440.0, Instrument.VIOLIN)` plays a 440 Hz sine wave.

- GIVEN active instrument is Viola and reference pitch is 440 Hz WHEN user taps the C string button THEN `playStringTone("C", 440.0, Instrument.VIOLA)` plays a 130.8 Hz sine wave.

- GIVEN active instrument is Cello and reference pitch is set to 442 Hz WHEN user taps the C string button THEN playback frequency is scaled to 442 Hz reference (C2 ≈ 131.4 Hz at A=442).

**Acceptance**: Reference tone buttons produce audible sine waves at the active instrument's string frequencies, respecting the configured reference pitch. Backward compatible — default 440 Hz + Violin when no custom config exists.

---

### REQ-TN-006 — Auto-Detect Mode with Active Instrument

Auto-detect mode MUST map detected frequencies against the active instrument's string set. When pitch is within 50 cents of any string, `tunerSelectedNote` SHALL update automatically. Manual selection overrides auto-detect.
(Previously: Auto-detect only matched against violin strings G3/D4/A4/E5.)

**Scenarios**:

- GIVEN auto-detect is ON and active instrument is Viola WHEN pitch detection identifies 130.0 Hz within 30 cents of C3 THEN `tunerSelectedNote` updates to "C" automatically.

- GIVEN auto-detect is ON and active instrument is Cello WHEN pitch detection identifies 219 Hz within 30 cents of A3 THEN `tunerSelectedNote` updates to "A" automatically.

- GIVEN auto-detect is OFF and user manually selected "D" on Cello WHEN pitch detection identifies A3 (220 Hz) THEN `tunerSelectedNote` remains "D" (manual override).

**Acceptance**: Toggling the "AUTO STRING DETECT" switch enables/disables automatic note cycling against the active instrument's strings.

---

### REQ-TN-007 — Runtime Audio Permission

The app MUST request `RECORD_AUDIO` permission at runtime with a rationale dialog before enabling listening mode. Permission denial SHALL show an error state, not crash.

**Scenarios**:

- GIVEN `RECORD_AUDIO` permission is NOT granted WHEN the user taps "START ACCURATE LISTEN MODE" THEN the system permission dialog appears with rationale text.

- GIVEN the user denies `RECORD_AUDIO` permission WHEN listening mode is attempted THEN a Snackbar or error text displays "Microphone permission required for tuning" AND `isListeningTuner` remains false.

**Acceptance**: No crash when permission is denied. Permission is requested once per session with rationale.

---

### REQ-TN-008 — Graceful Degradation

When the microphone is unavailable (permission denied, hardware missing, or in-use by another app), the tuner MUST display a user-visible error state. Reference tone playback SHALL remain functional.

**Scenarios**:

- GIVEN the device has no microphone (e.g., Android TV) WHEN the tuner screen opens THEN "Microphone unavailable" text is shown AND the reference tone buttons remain usable.

- GIVEN another app holds the microphone exclusively WHEN listening mode starts THEN an error state is emitted AND no crash occurs.

**Acceptance**: `AudioRecord` initialization failure is caught; error state displayed in UI; reference playback unaffected.

### REQ-TN-009 — Low-Frequency Detection for Cello

The YIN pitch detector minimum frequency MUST be 50 Hz (lowered from 80 Hz) to cover cello C2 at 65.4 Hz. Detection accuracy SHALL remain within ±2 cents for all cello open strings.

**Scenarios**:

- GIVEN a 65.4 Hz sine wave input and minFrequency=50 Hz WHEN pitch detection runs THEN detected frequency is between 65.15 Hz and 65.65 Hz (±2 cents).

- GIVEN a 98.0 Hz sine wave input and minFrequency=50 Hz WHEN pitch detection runs THEN detected frequency is within ±2 cents of 98.0 Hz.

**Acceptance**: YIN detector accurately identifies cello C2 (65.4 Hz) and G2 (98.0 Hz) with minFrequency lowered to 50 Hz.

### REQ-TN-010 — Instrument-Aware NoteTargetSelector

The `NoteTargetSelector` composable MUST render string buttons dynamically from the active instrument's `strings` list. Button labels SHALL include octave suffix where needed for disambiguation. Tapping a button SHALL call `TunerViewModel.selectTunerNote()` with the correct frequency.

**Scenarios**:

- GIVEN active instrument is Viola WHEN NoteTargetSelector renders THEN four buttons appear: C3, G3, D4, A4 with frequencies 130.8, 196.0, 293.7, 440.0 Hz.

- GIVEN active instrument is Cello WHEN NoteTargetSelector renders THEN four buttons appear: C2, G2, D3, A3 with frequencies 65.4, 98.0, 146.8, 220.0 Hz.

- GIVEN active instrument is Viola and user taps the C3 button WHEN `selectTunerNote` is called THEN `ViolinAudioEngine.playStringTone()` plays a 130.8 Hz reference tone.

**Acceptance**: NoteTargetSelector dynamically renders correct string labels and frequencies per active instrument.

---

## SPEC-4: Room Migration Safety

### REQ-DB-001 — Remove Destructive Migration

`fallbackToDestructiveMigration()` MUST be removed from the `Room.databaseBuilder()` call chain in `PracticeDatabase.kt`. The database SHALL use proper `Migration` objects instead.

**Scenarios**:

- GIVEN `PracticeDatabase.getDatabase()` currently calls `.fallbackToDestructiveMigration()` WHEN refactored THEN that call is removed AND one or more `.addMigrations(...)` calls are present.

- GIVEN an app upgrade from database version 2 to version 3 WHEN the migration runs THEN existing user data (sessions, lesson progress, user accounts, assignments) survives intact.

**Acceptance**: `grep -r "fallbackToDestructiveMigration" app/src/` returns zero results.

---

### REQ-DB-002 — Version 2 → 3 Migration

A `Migration(2, 3)` object MUST be defined containing the SQL statements needed to migrate from the current schema (version 2, 4 entities) to version 3. If no schema changes are needed in this refactor, the migration SHALL be an empty no-op that preserves data.

**Scenarios**:

- GIVEN an existing database at version 2 with user data WHEN the app upgrades with the Migration(2, 3) THEN all tables and rows are preserved.

- GIVEN the migration object is added to the database builder WHEN Room validates the migration path THEN no "missing migration" error occurs.

**Acceptance**: `Migration(2, 3)` class exists in the data package. Database version in `@Database` annotation is 3.

---

### REQ-DB-003 — Schema Export

Room schema export MUST be enabled by setting `exportSchema = true` in the `@Database` annotation. The exported JSON schema files SHALL be committed to version control under `app/schemas/`.

**Scenarios**:

- GIVEN `@Database(..., exportSchema = false)` currently WHEN refactored THEN `exportSchema = true` AND the KSP processor generates schema JSON files.

- GIVEN schema export is enabled WHEN `./gradlew assembleDebug` runs THEN `app/schemas/com.example.data.PracticeDatabase/3.json` (or equivalent path with new namespace) is generated.

**Acceptance**: `app/schemas/` directory exists with versioned JSON schema files. Directory is committed to git (not gitignored).

---

### REQ-DB-004 — Data Preservation

Existing data (practice sessions, lesson progress, user accounts, assignments) MUST survive the migration from version 2 to version 3 without loss.

**Scenarios**:

- GIVEN the database has 10 practice sessions at version 2 WHEN the app upgrades to version 3 THEN all 10 sessions are queryable after migration.

- GIVEN a user account with hashed password and salt exists at version 2 WHEN migration runs THEN the account is retrievable with identical username, role, hashedPassword, salt, and points.

**Acceptance**: Migration test using Robolectric's `MigrationTestHelper` confirms row counts match pre/post migration for all 4 tables.

---

### REQ-DB-005 — Migration Tested

The migration MUST be tested using Robolectric's `MigrationTestHelper` to verify schema integrity and data preservation across the version boundary.

**Scenarios**:

- GIVEN a migration test using `MigrationTestHelper` WHEN version 2 data is inserted THEN the test asserts that after migrating to version 3, all inserted rows are queryable.

- GIVEN the migration test suite WHEN run via `./gradlew :app:test` THEN the migration test passes.

**Acceptance**: At least one test class in `app/src/test/` uses `MigrationTestHelper` to validate the 2→3 migration.

---

## SPEC-5: Hilt Dependency Injection

### REQ-DI-001 — HiltViewModel on All ViewModels

All ViewModel classes MUST be annotated with `@HiltViewModel` and use `@Inject constructor()` for dependency injection. The `AndroidViewModel(application)` base class SHALL be replaced with injected dependencies.

**Scenarios**:

- GIVEN `TunerViewModel` is defined WHEN inspected THEN it has `@HiltViewModel` annotation AND constructor uses `@Inject`.

- GIVEN Hilt processes the project WHEN compiling THEN all ViewModels are available in the Hilt component graph.

**Acceptance**: Every ViewModel file contains `@HiltViewModel`. No ViewModel directly calls `Application()` in its constructor.

---

### REQ-DI-002 — PracticeDatabase as Singleton via Hilt

`PracticeDatabase` MUST be provided as a `@Singleton` via a Hilt `@Module` annotated with `@InstallIn(SingletonComponent::class)`. The manual `companion object` singleton pattern MUST be removed.

**Scenarios**:

- GIVEN a `DatabaseModule` Hilt module exists WHEN the app requests `PracticeDatabase` THEN Hilt provides the same singleton instance.

- GIVEN the manual singleton (companion object with `INSTANCE` + `synchronized` block) is removed WHEN two components request the database THEN Hilt ensures they receive the same instance.

**Acceptance**: `PracticeDatabase.kt` no longer contains `companion object` with manual singleton logic. A `DatabaseModule.kt` exists in the `di/` package.

---

### REQ-DI-003 — PracticeDao Provided via Module

`PracticeDao` MUST be provided via a `@Provides` method in the database module, obtained from `PracticeDatabase.practiceDao()`.

**Scenarios**:

- GIVEN `DatabaseModule` has a `@Provides @Singleton fun providePracticeDao(db: PracticeDatabase): PracticeDao` method WHEN `PracticeRepository` requests a DAO THEN Hilt injects it.

- GIVEN the DAO is provided by Hilt WHEN a ViewModel depends on `PracticeRepository` THEN the repository receives the DAO via constructor injection.

**Acceptance**: No code directly calls `PracticeDatabase.getDatabase(context).practiceDao()`. All DAO access flows through Hilt.

---

### REQ-DI-004 — ViolinAudioEngine Injectable

`ViolinAudioEngine` MUST be provided as an injectable service (not a manually-instantiated class). It SHALL be annotated with `@Singleton` and provided via a Hilt module, or annotated with `@Inject constructor`.

**Scenarios**:

- GIVEN `TunerViewModel` and `MetronomeViewModel` both need the audio engine WHEN constructed THEN they receive the SAME `ViolinAudioEngine` instance via Hilt.

- GIVEN the audio engine is scoped as `@Singleton` WHEN the app lifecycle ends THEN `releaseAll()` is called once (not per-ViewModel).

**Acceptance**: `ViolinAudioEngine()` is never called with `new`/constructor in ViewModel or Screen code. Only injected.

---

### REQ-DI-005 — SharedPreferences via Hilt

`SharedPreferences` MUST be provided by a Hilt module, not directly accessed via `context.getSharedPreferences(...)` in ViewModels. A `PreferencesModule` SHALL provide the `app_settings` and `secure_user_prefs` SharedPreferences instances.

**Scenarios**:

- GIVEN `AuthViewModel` needs to read/write user session data WHEN constructed THEN it receives a `SharedPreferences` instance via constructor injection.

- GIVEN multiple ViewModels access SharedPreferences WHEN Hilt provides them THEN the same `SharedPreferences` instance is shared (singleton scope).

**Acceptance**: No `getSharedPreferences("app_settings", ...)` calls remain in ViewModel files.

---

### REQ-DI-006 — AndroidEntryPoint on MainActivity

`MainActivity` MUST be annotated with `@AndroidEntryPoint` to enable Hilt field injection for the activity.

**Scenarios**:

- GIVEN `MainActivity` is the entry point WHEN Hilt initializes THEN all `@Inject` fields in `MainActivity` are populated.

- GIVEN `@AndroidEntryPoint` is missing WHEN the app starts THEN Hilt injection fails at runtime with a clear error message.

**Acceptance**: `MainActivity.kt` has `@AndroidEntryPoint` annotation before the class declaration.

---

### REQ-DI-007 — Remove Manual Singleton from PracticeDatabase

The `companion object` containing `@Volatile private var INSTANCE` and the `synchronized` `getDatabase()` method MUST be removed from `PracticeDatabase`. Database access SHALL go exclusively through Hilt.

**Scenarios**:

- GIVEN the codebase is searched for `PracticeDatabase.getDatabase(` WHEN refactoring is complete THEN zero call sites remain.

- GIVEN any code needs the database WHEN the dependency is resolved THEN Hilt provides it without manual singleton management.

**Acceptance**: `grep -r "getDatabase" app/src/main/` returns zero results.

---

### REQ-DI-008 — VideoUploadViewModel Hilt Provision

The Hilt dependency injection graph MUST provide VideoUploadViewModel with all service dependencies (VideoRecordingService, FaceBlurProcessor, VideoCompressionService, VideoUploadService). TeacherDashboardTab and StudentAssignmentsTab SHALL receive VideoUploadViewModel as a non-null parameter, replacing the current nullable default (`null`).

**Scenarios**:

- GIVEN the Hilt component graph is initialized WHEN TeacherDashboardTab is composed THEN VideoUploadViewModel is injected and non-null.

- GIVEN teacher is viewing AssignmentCreationForm WHEN the video attachment section renders THEN a recording trigger button is present instead of the checkbox-only attachVideo toggle.

**Acceptance**: Hilt DI graph builds without errors. Recording trigger visible in assignment creation form.

---

## SPEC-6: Security Hardening

### REQ-SEC-001 — Remove Debug Keystore from Repo

`debug.keystore` and `debug.keystore.base64` MUST be removed from the repository and added to `.gitignore`.

**Scenarios**:

- GIVEN `debug.keystore` exists in the repo root WHEN cleaned THEN `git rm --cached debug.keystore` removes tracking AND `.gitignore` contains `debug.keystore` and `debug.keystore.base64`.

- GIVEN a fresh clone of the repo WHEN `./gradlew assembleDebug` runs THEN the default Android debug keystore is used (auto-generated by Android SDK) OR a new debug keystore is generated if needed.

**Acceptance**: `debug.keystore` and `debug.keystore.base64` do not exist in the working tree and are listed in `.gitignore`.

---

### REQ-SEC-002 — Debug Signing Config Uses Environment Variables

The `debugConfig` signing config in `build.gradle.kts` MUST NOT contain hardcoded passwords. It SHALL use environment variables with sensible defaults, or fall back to the Android SDK default debug certificate.

**Scenarios**:

- GIVEN `debugConfig` currently has `storePassword = "android"` and `keyPassword = "android"` WHEN hardened THEN these values are read from `System.getenv("DEBUG_STORE_PASSWORD") ?: "android"` OR the debug signing config is removed entirely (defaulting to SDK debug cert).

- GIVEN the environment variable `DEBUG_STORE_PASSWORD` is NOT set WHEN the build runs THEN a sensible default (empty string or documented fallback) is used AND the build does not fail.

**Acceptance**: No hardcoded password strings in `signingConfigs` blocks. Grep for `"android"` in signing config context returns zero results.

---

### REQ-SEC-003 — Network Security Config

A `network_security_config.xml` file MUST be created at `app/src/main/res/xml/network_security_config.xml` with domain restrictions allowing cleartext traffic only for explicitly whitelisted domains (if any) and restricting certificate pinning decisions.

**Scenarios**:

- GIVEN the app needs to communicate with the Gemini API WHEN the network security config is processed THEN `generativelanguage.googleapis.com` is in the allowed domains for HTTPS traffic.

- GIVEN the security config is in place WHEN Android 9+ (API 28+) devices run the app THEN cleartext traffic is blocked by default (unless explicitly configured for local development).

**Acceptance**: File exists at `app/src/main/res/xml/network_security_config.xml` with valid XML schema.

---

### REQ-SEC-004 — Manifest References Network Security Config

`AndroidManifest.xml` MUST reference the network security config via `android:networkSecurityConfig="@xml/network_security_config"` on the `<application>` element.

**Scenarios**:

- GIVEN the manifest's `<application>` tag WHEN inspected THEN it includes `android:networkSecurityConfig="@xml/network_security_config"`.

- GIVEN the config is referenced WHEN a network request is made to a disallowed domain THEN the platform blocks it with a `SecurityException`.

**Acceptance**: `grep "networkSecurityConfig" app/src/main/AndroidManifest.xml` returns the expected attribute.

---

### REQ-SEC-005 — No Hardcoded Secrets

No hardcoded API keys, passwords, or signing keys SHALL remain in any source file. The existing `CDN_SIGNING_PRIVATE_KEY` in `VideoSecurityService.kt` MUST be moved to environment configuration or BuildConfig.

**Scenarios**:

- GIVEN `VideoSecurityService.kt` currently has `const val CDN_SIGNING_PRIVATE_KEY = "k8s_acoustic_violin_secret_crypto_salt_91x7"` WHEN hardened THEN the value is loaded from a BuildConfig field or environment variable, not a hardcoded string.

- GIVEN a grep for common secret patterns (`password`, `secret`, `key = "`, `api_key`) WHEN run against `app/src/main/` THEN zero hardcoded secrets are found.

**Acceptance**: `grep -rE "(password|secret|api_key|private_key)\s*=\s*\"" app/src/main/` returns zero results (excluding commented-out lines).

---

## SPEC-7: Test Coverage

### REQ-TST-001 — ViewModel Unit Tests

Unit tests MUST exist for all 5 ViewModels (Tuner, Metronome, Auth, Practice, Assignment). Each ViewModel test class SHALL cover: happy path, edge cases, error states, and state emission correctness.

**Scenarios**:

- GIVEN `TunerViewModel` with a mock `ViolinAudioEngine` WHEN `selectTunerNote("A")` is called THEN the test asserts `tunerSelectedNote.value` is "A" AND `audioEngine.playStringTone("A", 440)` was invoked.

- GIVEN `MetronomeViewModel` with a mock audio engine WHEN `toggleMetronome()` is called THEN the test asserts `isMetronomePlaying` transitions to true AND `audioEngine.startMetronome(...)` was called with correct parameters.

- GIVEN `AuthViewModel` with a mock repository WHEN `login("validUser", "1234")` is called and the repository returns a matching user THEN `currentUser.value` is the user object AND `loginError.value` is null.

**Acceptance**: Test files exist for all 5 ViewModels under `app/src/test/`. All tests pass.

---

### REQ-TST-002 — DAO Tests with Robolectric

DAO tests MUST cover all CRUD operations for `PracticeDao` (insert, query, update, delete for: PracticeSession, LessonProgress, UserAccount, Assignment) using Robolectric.

**Scenarios**:

- GIVEN an in-memory Room database in a Robolectric test WHEN `insertSession(session)` is called THEN `getAllSessions()` flow emits a list containing that session.

- GIVEN a session exists in the database WHEN `deleteSessionById(id)` is called THEN `getAllSessions()` emits an empty list.

- GIVEN `getUserByUsername("test")` is called and no such user exists WHEN the result is collected THEN it returns null.

**Acceptance**: A `PracticeDaoTest.kt` file exists with test methods for all DAO operations. All DAO tests pass on `./gradlew :app:test`.

---

### REQ-TST-003 — Composable UI Tests

Composable tests MUST exist for `HomeScreen`, `TunerScreen`, and `MetronomeScreen` using Compose testing APIs. Tests SHALL verify UI element existence, interaction behavior, and state-driven rendering.

**Scenarios**:

- GIVEN `HomeScreen` is rendered with a mock `PracticeViewModel` WHEN the "START PRACTICE SESSION" button is clicked THEN `viewModel.startPracticeTimer(...)` is invoked.

- GIVEN `TunerScreen` renders with `tunerSelectedNote = "A"` WHEN the test inspects the UI THEN the selected note "A" is displayed with the correct styling.

- GIVEN `MetronomeScreen` renders with `bpm = 120` WHEN the BPM slider is dragged to 140 THEN `viewModel.updateMetronomeBpm(140)` is called.

**Acceptance**: At least 3 compose test files exist. Tests use `createComposeRule()` and `onNodeWithTag(...)` assertions.

---

### REQ-TST-004 — Coverage ≥70% on ViewModels and DAOs

Line coverage on ViewModel classes (5 files) and `PracticeDao` (implemented by Room) SHALL reach ≥70% as measured by JaCoCo or similar coverage tool.

**Scenarios**:

- GIVEN the test suite runs with coverage enabled WHEN the JaCoCo report is generated THEN ViewModels show ≥70% line coverage.

- GIVEN a developer adds a new method to a ViewModel WHEN coverage drops below 70% THEN the CI build warns or fails.

**Acceptance**: Coverage report generated and ≥70% on the specified packages.

---

### REQ-TST-005 — All Tests Pass

All unit tests (`./gradlew :app:test`) MUST pass with zero failures.

**Scenarios**:

- GIVEN the full test suite WHEN `./gradlew :app:test` runs THEN BUILD SUCCESSFUL with all test cases passing.

- GIVEN a PR is submitted WHEN CI runs the test suite THEN failing tests block the merge.

**Acceptance**: `./gradlew :app:test` exits 0 with no failures.

---

## SPEC-8: String Localization

### REQ-L10N-001 — Extract Hardcoded Strings

All hardcoded English display strings in Compose UI files MUST be extracted to `Localization.kt` using string keys. The localization system (`Localization.get(key, language)`) SHALL be the single source of truth for UI text.

**Scenarios**:

- GIVEN `TunerScreen.kt` currently has `text = "SMART TUNER"` WHEN localized THEN it uses `Localization.get("smart_tuner_header", appLanguage)` instead.

- GIVEN `MetronomeScreen.kt` currently has `text = "METRONOME"` and `text = "Rhythmic Pulse Trainer"` WHEN localized THEN both use localization key lookups.

**Acceptance**: `grep -rn 'text = "' app/src/main/java/com/example/ui/screens/*.kt` returns zero results for hardcoded English strings (Compose `stringResource` and `Localization.get()` calls are acceptable).

---

### REQ-L10N-002 — Spanish Translations Complete

Every string key in the English map MUST have a corresponding Spanish translation in the `es` map of `Localization.kt`.

**Scenarios**:

- GIVEN a new string key `"tuner_start_listening"` is added to the English map WHEN the Spanish map is inspected THEN it contains a Spanish translation for the same key.

- GIVEN the app language is set to `AppLanguage.SPANISH` WHEN any screen renders THEN all visible text is in Spanish, not English fallback.

**Acceptance**: `en.keys` set equals `es.keys` set. No English string visible when Spanish is selected.

---

### REQ-L10N-003 — No Hardcoded Strings in Compose

Zero hardcoded display strings SHALL remain in any Compose `@Composable` function. Strings used for `testTag`, `Modifier`, or technical identifiers that are never displayed to users are exempt.

**Scenarios**:

- GIVEN a new Compose screen is added WHEN reviewed THEN all user-visible `Text()` and `contentDescription` strings use localization lookups.

- GIVEN a code review grep for `text = "` in UI files WHEN run THEN only localization calls remain.

**Acceptance**: Automated grep check passes. Exemptions for test tags, log messages, and technical identifiers documented inline.

---

### REQ-L10N-004 — Fallback to English

When a translation key is missing from the active language map, the `Localization.get()` function MUST fall back to the English value. If the key is missing from both maps, the key string itself SHALL be returned.

**Scenarios**:

- GIVEN the Spanish map is missing key `"new_feature_label"` WHEN `Localization.get("new_feature_label", SPANISH)` is called THEN the English value is returned.

- GIVEN neither English nor Spanish maps contain `"unknown_key"` WHEN `Localization.get("unknown_key", ENGLISH)` is called THEN the string `"unknown_key"` is returned (never null).

**Acceptance**: `Localization.get()` never returns null. Fallback chain: active language → English → key string.

---

## SPEC-9: Build Tooling

### REQ-BLD-001 — Gradle Wrapper Committed

The Gradle wrapper files (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`) MUST be committed to the repository.

**Scenarios**:

- GIVEN a fresh clone of the repository WHEN `./gradlew --version` is run THEN the Gradle wrapper executes and shows the configured Gradle version.

- GIVEN the wrapper files are committed WHEN a CI system checks out the repo THEN `./gradlew tasks` executes without manual Gradle installation.

**Acceptance**: `gradlew` file exists and is executable. `gradle/wrapper/` directory exists with `.jar` and `.properties` files.

---

### REQ-BLD-002 — assembleDebug Succeeds from CLI

`./gradlew :app:assembleDebug` MUST succeed from the command line without IDE assistance.

**Scenarios**:

- GIVEN the project is cloned and Gradle wrapper is available WHEN `./gradlew :app:assembleDebug` runs THEN BUILD SUCCESSFUL is printed AND an APK is generated at `app/build/outputs/apk/debug/`.

- GIVEN dependencies are not yet downloaded WHEN the first build runs THEN Gradle downloads all dependencies automatically and the build succeeds.

**Acceptance**: CLI build succeeds with exit code 0.

---

### REQ-BLD-003 — ktlint Configured

`ktlint` MUST be configured via the `org.jlleitschuh.gradle.ktlint` Gradle plugin in `app/build.gradle.kts`. Rules SHALL use `.editorconfig`-style configuration in the project root.

**Scenarios**:

- GIVEN `ktlint` is configured WHEN `./gradlew ktlintCheck` runs THEN all Kotlin source files are checked against the configured rules.

- GIVEN a developer runs `./gradlew ktlintFormat` WHEN auto-fixable violations exist THEN ktlint corrects them automatically.

**Acceptance**: `ktlint` plugin applied in `build.gradle.kts`. `.editorconfig` exists at project root.

---

### REQ-BLD-004 — Zero ktlint Violations

`./gradlew ktlintCheck` MUST pass with zero violations. All existing code SHALL be reformatted to comply with the configured rules.

**Scenarios**:

- GIVEN all existing Kotlin source files WHEN `./gradlew ktlintCheck` runs THEN zero violations are reported.

- GIVEN a developer commits code with ktlint violations WHEN the pre-commit hook or CI check runs THEN the violation is flagged and the commit/PR is blocked.

**Acceptance**: `./gradlew ktlintCheck` exits 0 with "BUILD SUCCESSFUL".

---

### REQ-BLD-005 — isMinifyEnabled for Release

`isMinifyEnabled` MUST be set to `true` in the `release` build type block of `app/build.gradle.kts`. ProGuard/R8 rules SHALL be adequate for the app's dependencies.

**Scenarios**:

- GIVEN the `release` build type currently has `isMinifyEnabled = false` WHEN hardened THEN it is set to `true`.

- GIVEN `isMinifyEnabled = true` WHEN `./gradlew :app:assembleRelease` runs THEN R8 processes the bytecode AND the APK is smaller than the debug APK.

**Acceptance**: Release build type has `isMinifyEnabled = true`. Release build succeeds.

---

### REQ-BLD-006 — Adequate ProGuard Rules

`proguard-rules.pro` MUST contain keep rules for Room entities, Retrofit interfaces, Moshi data classes, and Compose runtime to prevent R8 from stripping required classes.

**Scenarios**:

- GIVEN Room entities are processed by R8 WHEN the release APK runs THEN Room can still generate DAO implementations and access entity fields (no `IllegalStateException` or reflection errors).

- GIVEN Retrofit service interfaces have `@Keep` or ProGuard rules WHEN R8 processes them THEN the interface methods and Moshi-annotated constructors are preserved.

**Acceptance**: `./gradlew :app:assembleRelease` succeeds. Release APK runs without runtime crashes caused by missing classes/methods.

---

## SPEC-10: Gemini API Integration

### REQ-GEM-001 — GeminiApiService Interface

A `GeminiApiService` interface MUST be defined with Retrofit annotations, declaring at least one endpoint for generating lesson feedback or practice suggestions. The interface SHALL use Moshi for JSON serialization.

**Scenarios**:

- GIVEN `GeminiApiService` is defined WHEN Retrofit creates the implementation THEN a valid HTTP call can be made to the Gemini API endpoint.

- GIVEN the service interface has a method annotated with `@POST` WHEN the request body contains the prompt and model parameters THEN the response is deserialized to a Kotlin data class.

**Acceptance**: `GeminiApiService.kt` file exists with at least one `@POST` annotated method. Response data classes exist.

---

### REQ-GEM-002 — API Key via Secrets Plugin

The Gemini API key MUST be loaded from `.env` file via the existing Secrets Gradle Plugin, NOT hardcoded in source. `BuildConfig.GEMINI_API_KEY` (or equivalent) SHALL be the access point.

**Scenarios**:

- GIVEN `.env` contains `GEMINI_API_KEY=your-key-here` WHEN the app builds THEN `BuildConfig` contains the key accessible at runtime.

- GIVEN `.env.example` exists with placeholder key WHEN a new developer sets up the project THEN they copy `.env.example` to `.env` and insert their real key.

**Acceptance**: No Gemini API key string in any `.kt` file. `.env.example` lists `GEMINI_API_KEY` as a required variable.

---

### REQ-GEM-003 — At Least One Callable Endpoint

At least one Gemini API endpoint (e.g., `generateContent` for lesson feedback) MUST be fully callable: request builds correctly, HTTP call executes, response is parsed.

**Scenarios**:

- GIVEN a user completes a practice session WHEN the app requests lesson feedback THEN a POST request is sent to `https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent` with the user's practice context as the prompt.

- GIVEN the Gemini API returns a valid JSON response WHEN parsed THEN the feedback text is displayed in the UI.

**Acceptance**: End-to-end flow from button press → API call → parsed response → UI display works (with valid API key).

---

### REQ-GEM-004 — Coroutine-Based Execution

All Gemini API calls MUST execute on `Dispatchers.IO` via Kotlin coroutines. The UI thread SHALL NOT be blocked.

**Scenarios**:

- GIVEN a Gemini API call is initiated WHEN the network request is in flight THEN the UI remains responsive (user can interact with other elements).

- GIVEN the coroutine context is `Dispatchers.IO` WHEN the response arrives THEN the result is switched to `Dispatchers.Main` for UI state updates.

**Acceptance**: All Retrofit service methods are `suspend` functions. ViewModel calls use `viewModelScope.launch { withContext(Dispatchers.IO) { ... } }` pattern.

---

### REQ-GEM-005 — Error Handling

Network failures, rate limiting (HTTP 429), and authentication failures (HTTP 401/403) MUST be handled gracefully with user-visible error messages. The app SHALL NOT crash on any API error condition.

Gemini authentication uses per-user Google OAuth tokens via [GeminiAuthInterceptor]. A missing or expired token results in HTTP 401; a revoked or unauthorized token results in HTTP 403.

**Scenarios**:

- GIVEN the device is offline WHEN a Gemini API call is attempted THEN a `try/catch` for `IOException` catches the error AND the UI displays "Network unavailable. Check your connection."

- GIVEN the API returns HTTP 429 (rate limit) WHEN the response is processed THEN the UI displays a retry suggestion ("Too many requests. Try again in a moment.") with exponential backoff.

- GIVEN the user's OAuth token is expired or missing (HTTP 401/403) WHEN the response is processed THEN the UI displays "Authentication failed. Sign in with Google to use AI features."

**Acceptance**: All Retrofit calls are wrapped in `try/catch`. Each error type (network, rate limit, auth) produces a distinct, localized error message. The deprecated `.env`-based API key flow is removed; all Gemini calls use per-user OAuth tokens.

---

## Requirement Summary

| SPEC  | Requirements                    | Count |
|-------|--------------------------------|-------|
| SPEC-1  | REQ-NS-001 .. REQ-NS-005      | 5     |
| SPEC-2  | REQ-VM-001 .. REQ-VM-009      | 9     |
| SPEC-3  | REQ-TN-001 .. REQ-TN-010      | 10    |
| SPEC-4  | REQ-DB-001 .. REQ-DB-005      | 5     |
| SPEC-5  | REQ-DI-001 .. REQ-DI-008      | 8     |
| SPEC-6  | REQ-SEC-001 .. REQ-SEC-005    | 5     |
| SPEC-7  | REQ-TST-001 .. REQ-TST-005    | 5     |
| SPEC-8  | REQ-L10N-001 .. REQ-L10N-004  | 4     |
| SPEC-9  | REQ-BLD-001 .. REQ-BLD-006    | 6     |
| SPEC-10 | REQ-GEM-001 .. REQ-GEM-005    | 5     |
| **Total** |                                | **62** |

