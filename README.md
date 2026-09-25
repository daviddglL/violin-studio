# Violin Studio

App Android para practicar violín. Une Proyecto_musica (`daviddglL/Prueba-app-music` @ `6390769`)
y Violin-master (`daviddglL/Violin` @ `9163c12`).

- Diseño de la fase 1: `docs/superpowers/specs/2026-09-25-violin-studio-fase1-design.md`
- Plan de la fase 1: `docs/superpowers/plans/2026-09-25-violin-studio-fase1.md`

## Requisitos

JDK 21, Android SDK 36, Node 20, Firebase CLI (`npm i -g firebase-tools`).

Los `google-services.json` no están en git: descárgalos de la consola de Firebase a
`app/src/dev/` y `app/src/prod/`. Dev y prod usan actualmente el mismo proyecto,
`violin-app-dev-f0b55`, hasta que exista un proyecto de producción.

## Comandos

```bash
./gradlew check coverage                     # ktlint, lint, tests y cobertura
./gradlew :app:recordRoborazziDevDebug       # regrabar capturas
./gradlew :app:verifyRoborazziDevDebug       # comparar capturas
npm --prefix functions test                  # tests unitarios de Functions
firebase emulators:start --only functions --project violin-app-dev-f0b55
./gradlew :app:connectedDevDebugAndroidTest  # E2E (con el emulador anterior arrancado)
bash scripts/secrets-guard.sh                # comprueba que no hay secretos en git
```

Móvil físico contra los emuladores del PC: `./gradlew installDevDebug -Pviolin.emulatorHost=<IP del PC>`
y arrancar los emuladores con `--host 0.0.0.0` (o `"host": "0.0.0.0"` en `firebase.json`).

## Arquitectura

Multimódulo (`app`, `core/*`, y `feature/*` desde la fase 3) con MVI: cada pantalla tiene
`Contract` (State/Intent/Effect), `Reducer` puro, `ViewModel` que extiende `MviViewModel` y
`Screen` sin estado. Backend en Firebase; la lógica que debe aplicarse en servidor va en
`functions/`.

## App Check en prod

Play Integrity solo valida apps instaladas desde Google Play. Mientras la app se reparta por
App Distribution, crea `functions/.env.violin-app-795ee` con `ENFORCE_APP_CHECK=false` antes de
desplegar Functions (ese fichero no se versiona).
