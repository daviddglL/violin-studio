# Capability Spec: Tuner Configuration

## Purpose

Define the configurable tuner experience: user-adjustable max-cents range, dynamic tuning wheel, reference pitch A configuration, per-user tuning presets, and the configuration bottom sheet UI. These capabilities extend SPEC-3 (Real Tuner Implementation) from `openspec/specs/app/spec.md`.

**Source**: Change `tuner-custom-configurations` — implemented on `feature/tuner-custom-configurations` (10 commits, 15 files, +1408/-68 lines).

---

## SPEC-TN-CFG-1: Configurable Max-Cents Range

### REQ-TN-CFG-001 — Remove ±50 Cent Clamp

The ViewModel MUST NOT clamp pitch cent offset values to any fixed range. The `coerceIn(-50f, 50f)` guard previously present on `tunerPitchOffsetCents` is removed, allowing the full dynamic range of the YIN pitch detector to pass through unmodified.

**Scenarios**:

- GIVEN the YIN detector returns a pitch offset of +87 cents WHEN `TunerViewModel` processes the pitch result THEN `tunerPitchOffsetCents.value` is 87 (not clamped to 50).
- GIVEN the YIN detector returns -120 cents WHEN the state is read THEN `tunerPitchOffsetCents.value` is -120.

**Acceptance**: Code inspection confirms `coerceIn(-50f, 50f)` is absent. `TunerViewModelTest` validates unbounded cent values.

---

### REQ-TN-CFG-002 — Configurable maxCents StateFlow

The `TunerViewModel` MUST expose a `maxCents: StateFlow<Int>` that defaults to 50 and can be updated to values in the range 25–200 via `updateMaxCents(value: Int)`. Values outside this range are clamped to [25, 200].

**Scenarios**:

- GIVEN the app launches for the first time (no saved config) WHEN `maxCents` is collected THEN the value is 50.
- GIVEN `updateMaxCents(100)` is called WHEN `maxCents` is collected THEN the value is 100.
- GIVEN `updateMaxCents(10)` is called WHEN `maxCents` is collected THEN the value is clamped to 25.
- GIVEN `updateMaxCents(250)` is called WHEN `maxCents` is collected THEN the value is clamped to 200.

**Acceptance**: `TunerViewModelTest > maxCents defaults to 50`, `updateMaxCents changes`, `updateMaxCents clamps 25–200` all pass.

---

### REQ-TN-CFG-003 — YIN Confidence Uses maxCents

The `YinPitchDetector.frequencyToNoteAndCents()` function MUST accept a `maxCents: Int = 50` parameter and use it to compute confidence: `confidence = 1.0f - (abs(cents) / maxCents)`, clamped to [0.0, 1.0]. This replaces the previous hardcoded `abs(cents) / 50f` formula.

**Scenarios**:

- GIVEN `maxCents = 100` and a detected offset of exactly +100 cents WHEN confidence is computed THEN the result is 0.0 (needle at edge).
- GIVEN `maxCents = 100` and a detected offset of +50 cents WHEN confidence is computed THEN the result is 0.5.
- GIVEN `maxCents = 50` and any offset WHEN confidence is computed THEN the result matches the old `/50f` formula (backward compatible).

**Acceptance**: `YinPitchDetectorTest` validates confidence at ±maxCents, half-range, and [0,1] clamping.

---

### REQ-TN-CFG-004 — PitchResult Carries maxCents

`PitchResult` MUST include a `maxCents: Int = 50` field alongside `cents: Float` and `frequency: Float`. The KDoc for `cents` is updated to remove any mention of `-50 to +50` bounds.

**Scenarios**:

- GIVEN a `PitchResult` is emitted by the YIN detector WHEN inspected THEN `maxCents` reflects the configured range used for the detection pass.
- GIVEN the default constructor WHEN a `PitchResult` is created without explicit `maxCents` THEN it defaults to 50.

**Acceptance**: All `YinPitchDetectorTest` scenarios validate `result.maxCents`. KDoc updated.

---

## SPEC-TN-CFG-2: Dynamic TuningWheel

### REQ-TN-CFG-005 — Dynamic Tick Generation

The `TuningWheel` composable MUST accept a `maxCents: Int = 50` parameter and generate tick marks dynamically. The number of ticks per side equals `maxCents / 25`, giving `(maxCents / 25) × 2` total ticks across the 120° arc (60° each side). Center tick = green, edges = red, intermediates = gray.

**Scenarios**:

- GIVEN `maxCents = 50` WHEN `TuningWheel` renders THEN 4 total ticks are drawn (2 per side: ±25, ±50).
- GIVEN `maxCents = 200` WHEN `TuningWheel` renders THEN 16 total ticks are drawn (8 per side: ±25 through ±200).
- GIVEN `maxCents = 75` WHEN `TuningWheel` renders THEN 6 total ticks are drawn (3 per side: ±25, ±50, ±75).

**Acceptance**: Code inspection `TuningWheel.kt` L68–98 generates `totalSteps = (maxCents/25) × 2` ticks. Visual inspection confirms proper tick spacing.

---

### REQ-TN-CFG-006 — Needle Pinning and Overflow Indicator

When the absolute detected cent offset exceeds `maxCents`, the needle MUST pin at the arc edge (left edge for flat, right edge for sharp) with amber color. An overflow text label MUST display `≥+{maxCents}` (sharp) or `≤-{maxCents}` (flat) instead of the clamped value.

**Scenarios**:

- GIVEN `maxCents = 50` and detected offset is +87 cents WHEN rendered THEN the needle sits at the right arc edge (+50 equivalent position) AND the label shows "≥+50".
- GIVEN `maxCents = 100` and detected offset is -130 cents WHEN rendered THEN the needle sits at the left arc edge (-100 equivalent position) AND the label shows "≤-100".
- GIVEN offset is within ±maxCents WHEN rendered THEN the needle sits at the proportional position without overflow indicators.

**Acceptance**: Code inspection `TuningWheel.kt` L101–106 clamps needle to `±effectiveMax`. Overflow label rendering at L163.

---

## SPEC-TN-CFG-3: Per-User Tuning Presets

### REQ-TN-CFG-007 — Persist User Presets via SharedPreferences

`TuningPreferencesManager` MUST store an array of `TuningConfiguration` objects per user (keyed by `tuner_configs_{username}`) in the existing `app_settings` SharedPreferences. Each configuration includes: `label` (String, preset name), `referencePitch` (Int, 200–900 Hz), and `maxCents` (Int, 25–200). Serialization uses Moshi JSON (consistent with existing project patterns). The manager exposes a `StateFlow<List<TuningConfiguration>>` for reactive observation.

**Scenarios**:

- GIVEN a user saves a new preset "Baroque 415" with referencePitch=415, maxCents=50 WHEN the app is restarted THEN `configs: StateFlow` emits a list containing the preset.
- GIVEN a user deletes a preset WHEN the flow is collected THEN the preset is removed from the emitted list.
- GIVEN a user overwrites an existing preset with the same label WHEN saveConfig is called THEN the old entry is replaced.
- GIVEN no user is logged in WHEN saveConfig is called THEN the operation is silently ignored (no crash).

**Acceptance**: `TuningPreferencesManagerTest` (13 tests) covers save, load, delete, overwrite, cross-instance persistence, and no-user safety. All pass.

---

### REQ-TN-CFG-008 — Preset CRUD in TunerViewModel

`TunerViewModel` MUST expose `saveCurrentAsPreset(label: String)`, `loadPreset(config: TuningConfiguration)`, and `deletePreset(config: TuningConfiguration)` methods that delegate to `TuningPreferencesManager`. `saveCurrentAsPreset` captures the current `referencePitch` and `maxCents` as a named preset.

**Scenarios**:

- GIVEN the current config is referencePitch=442, maxCents=100 WHEN `saveCurrentAsPreset("Orchestra 442")` is called THEN a new `TuningConfiguration` with those values and the given label is persisted.
- GIVEN a preset "Baroque 415" exists WHEN `loadPreset(baroque415config)` is called THEN `referencePitch` updates to 415 AND `maxCents` updates to the preset's value.
- GIVEN a non-existent preset label is passed to `loadPreset` WHEN the operation completes THEN the config state is unchanged.
- GIVEN duplicate labels are attempted WHEN two presets with name "Custom" are saved THEN only the most recent is retained (overwrite).

**Acceptance**: `TunerViewModelTest` (11 preset tests) covers save, load, delete, overwrite, and non-existent label safety. All pass.

---

## SPEC-TN-CFG-4: Configuration Bottom Sheet

### REQ-TN-CFG-009 — Config Button and ModalBottomSheet

The `TunerScreen` MUST display a config button (e.g., gear icon) that opens a `ModalBottomSheet` containing: a reference pitch slider (350–500 Hz, step 1), a max-cents range selector (options: 25, 50, 75, 100, 150, 200), and a preset list with save/load/delete actions.

**Scenarios**:

- GIVEN the tuner screen is displayed WHEN the user taps the config button THEN a `ModalBottomSheet` opens with reference pitch slider, max-cents selector, and preset list.
- GIVEN the config sheet is open WHEN the user adjusts the reference pitch slider THEN `TunerViewModel.updateReferencePitch(...)` is called with the new value.
- GIVEN the config sheet is open WHEN the user selects a maxCents option THEN `TunerViewModel.updateMaxCents(...)` is called.

**Acceptance**: `TunerScreen.kt` L89: `showConfigSheet` state, L283–305: `ModalBottomSheet` composable, L312–532: `ConfigSheetContent` with slider, selector, and preset list.

---

### REQ-TN-CFG-010 — Localized Config Sheet Strings

All user-visible strings in the configuration bottom sheet MUST use the localization system (`StringsXxx.kt` keys flowing into the `Localization.get()` function). Both English and Spanish translations are required.

**Scenarios**:

- GIVEN the app language is English WHEN the config sheet renders THEN all labels, hints, and button texts are in English.
- GIVEN the app language is Spanish WHEN the config sheet renders THEN all labels, hints, and button texts are in Spanish.

**Acceptance**: `StringsSettings.kt` contains all config sheet string keys. Both `en` and `es` maps in `Localization.kt` include translations.

---

## SPEC-TN-CFG-5: Hilt Wiring

### REQ-TN-CFG-011 — TuningPreferencesManager as Hilt Singleton

`TuningPreferencesManager` MUST be provided as a `@Singleton` via Hilt in `AudioModule.provideTuningPreferencesManager(@ApplicationContext context: Context)`. It is injected into `TunerViewModel` via constructor injection.

**Scenarios**:

- GIVEN the Hilt component graph is built WHEN `TunerViewModel` is requested THEN it receives a `TuningPreferencesManager` instance.
- GIVEN multiple components request `TuningPreferencesManager` WHEN Hilt resolves the dependency THEN the same singleton instance is provided.

**Acceptance**: `AudioModule.kt` L24–29 contains `@Provides @Singleton fun provideTuningPreferencesManager()`. `TunerViewModel` constructor includes `tuningPreferencesManager: TuningPreferencesManager`.

---

## SPEC-TN-CFG-6: Backward Compatibility

### REQ-TN-CFG-012 — Default Configuration for Existing Users

When no `tuner_configs_{username}` key exists in SharedPreferences (first launch for existing users), the system MUST apply default values: `maxCents = 50` and `referencePitch = 440`. Tuner behavior is identical to the old app under the default configuration.

**Scenarios**:

- GIVEN an existing user who has never opened the tuner config WHEN the tuner screen renders THEN maxCents=50 AND the tuning wheel behaves identically to the pre-configuration version.
- GIVEN a new user launches the app for the first time WHEN `TunerViewModel` initializes THEN the default `TuningConfiguration` with label="", referencePitch=440, maxCents=50 is applied.

**Acceptance**: `TuningPreferencesManager.loadConfigs()` returns an empty list for never-configured users. `TunerViewModel` defaults to maxCents=50. `YinPitchDetector` with default maxCents=50 produces identical confidence values to the old formula.

---

## Requirement Summary

| Spec | Requirements | Count |
|------|-------------|-------|
| SPEC-TN-CFG-1 | REQ-TN-CFG-001 .. REQ-TN-CFG-004 | 4 |
| SPEC-TN-CFG-2 | REQ-TN-CFG-005 .. REQ-TN-CFG-006 | 2 |
| SPEC-TN-CFG-3 | REQ-TN-CFG-007 .. REQ-TN-CFG-008 | 2 |
| SPEC-TN-CFG-4 | REQ-TN-CFG-009 .. REQ-TN-CFG-010 | 2 |
| SPEC-TN-CFG-5 | REQ-TN-CFG-011 | 1 |
| SPEC-TN-CFG-6 | REQ-TN-CFG-012 | 1 |
| **Total** | | **12** |
