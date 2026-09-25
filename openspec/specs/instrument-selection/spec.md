# Instrument Selection Specification

## Purpose

Users select violin/viola/cello; selection persists and flows to tuner, audio engine, and UI subsystems.

## Requirements

### Requirement: Instrument Selector in Settings

The Settings screen MUST display an instrument selector card with three buttons (Violin, Viola, Cello) rendered in a row. The active instrument SHALL be visually highlighted. Labels MUST use localization keys.

#### Scenario: Select viola in Settings

- GIVEN Settings is open with Violin selected
- WHEN user taps Viola button
- THEN Viola is highlighted AND selection is persisted to SharedPreferences

#### Scenario: Localized instrument labels

- GIVEN app language is Spanish
- WHEN the instrument selector renders
- THEN buttons display Violín, Viola, Violonchelo

### Requirement: Persistent Instrument Selection

The selected instrument MUST persist across app restarts via `UserPreferencesManager` (SharedPreferences key `selected_instrument`, serialized as enum name). The system SHALL default to Violin when no preference exists. The value SHALL be exposed as a `StateFlow<Instrument>`.

#### Scenario: Selection survives app restart

- GIVEN user selected Cello in a previous session
- WHEN app relaunches
- THEN active instrument is Cello AND tuner/audio/UI reflect Cello strings (C2, G2, D3, A3)

#### Scenario: First launch defaults to Violin

- GIVEN no `selected_instrument` key exists in SharedPreferences
- WHEN instrument preference is read
- THEN default is Violin AND all existing violin-only behavior is preserved

#### Scenario: Switch instruments mid-session

- GIVEN tuner is listening on Violin (G/D/A/E strings)
- WHEN user switches to Viola in Settings and returns to tuner
- THEN note mapping updates to Viola strings (C3, G3, D4, A4) AND reference tone buttons display C, G, D, A

### Requirement: Instrument Data Model

The system MUST define an `Instrument` enum in `domain/model/Instrument.kt` with values VIOLIN, VIOLA, CELLO. Each value SHALL carry a label key and a list of `InstrumentString(name: String, frequency: Double)` objects representing the instrument's open strings.

#### Scenario: Violin strings

- GIVEN `Instrument.VIOLIN.strings`
- THEN the list is [G3(196.0), D4(293.7), A4(440.0), E5(659.3)]

#### Scenario: Viola strings

- GIVEN `Instrument.VIOLA.strings`
- THEN the list is [C3(130.8), G3(196.0), D4(293.7), A4(440.0)]

#### Scenario: Cello strings

- GIVEN `Instrument.CELLO.strings`
- THEN the list is [C2(65.4), G2(98.0), D3(146.8), A3(220.0)]

#### Scenario: Enum serialization roundtrip

- GIVEN `Instrument.valueOf("CELLO")`
- THEN returns `Instrument.CELLO` AND `Instrument.CELLO.name` equals `"CELLO"`

### Requirement: String Label Disambiguation

When instruments share note names at different octaves (G on violin/viola vs G on cello), the UI layer MUST append an octave suffix. `InstrumentString.name` SHALL be the plain note letter; disambiguation is applied at display time.

#### Scenario: Cello G is lower octave than violin G

- GIVEN active instrument is Cello
- WHEN string buttons render
- THEN G button label is "G2" (not "G3" as in violin/viola)

#### Scenario: Violin and viola G share same octave

- GIVEN active instrument is Violin THEN G label displays as "G3"
- GIVEN active instrument is Viola THEN G label displays as "G3"
