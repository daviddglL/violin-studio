# Violin Studio — Fase 1 (Base): Diseño

- **Fecha:** 2026-09-25
- **Estado:** Borrador pendiente de revisión
- **Programa:** Fusión de Proyecto_musica (`daviddglL/Prueba-app-music` @ `6390769`) y Violin-master (`daviddglL/Violin` @ `9163c12`) en una sola app.

## 1. Contexto y objetivo del programa

La app fusionada es una **app completa de práctica de violín**: el núcleo es la práctica diaria (afinador, metrónomo, currículo, estadísticas, gamificación) y el flujo profesor–alumno (grabación, revisión, chat) es una función más.

Decisiones del programa (aprobadas):

| Decisión | Valor |
|---|---|
| Backend | Firebase (Auth, Firestore, Storage, FCM, App Check) + Cloud Functions para la lógica que debe aplicarse en servidor (consentimiento, menores) |
| Código base | Enfoque A: esqueleto multimódulo de Proyecto_musica, versiones y features de Violin-master |
| Patrón de presentación | **MVI** en todas las pantallas |
| Repositorio | Nuevo, `daviddglL/violin-studio`, privado |

Fases del programa (cada una con su propio spec y plan):

1. **Base** ← este documento
2. Identidad y privacidad: Auth (email + Google, sin PIN de 4 dígitos), perfil/roles, consentimiento y menores en Functions, borrado GDPR en cascada
3. Práctica: afinador (YIN), metrónomo, sesiones/estadísticas, currículo/lecciones
4. Profesor–alumno: captura con face blur + subida reanudable a Storage (WorkManager), tareas, revisión, chat
5. Extras: gamificación, feedback con Gemini, Remote Config

## 2. Identidad del proyecto

| Elemento | Valor |
|---|---|
| Nombre de la app | Violin Studio (provisional) |
| Repo | `daviddglL/violin-studio` (privado) |
| Carpeta local | `Escritorio\violin-studio` |
| `applicationId` / namespace raíz | `com.violinstudio` (flavor dev: sufijo `.dev`) |
| Firebase prod | proyecto existente `violin-app-795ee`, nueva app Android `com.violinstudio` |
| Firebase dev | proyecto existente `violin-app-dev`, nueva app Android `com.violinstudio.dev` |
| Historial git | Nuevo. El commit inicial cita los SHAs de origen de ambos repos |
| Specs | `openspec/` (se importan `app`, `instrument-selection`, `tuner-configuration` de Violin-master) + `docs/superpowers/{specs,plans}` |

Los repos originales no se modifican.

## 3. Estructura de módulos

```
violin-studio/
├── build-logic/          plugins de convención
├── app/                  shell: MainActivity, NavHost, init Firebase/App Check
├── core/
│   ├── core-mvi/         contrato MVI + MviViewModel
│   ├── core-ui/          tema Material 3 unificado + componentes comunes
│   ├── core-model/       modelos de dominio puros (Kotlin/JVM, sin Android)
│   ├── core-data/        repositorios + fuentes de datos Firebase
│   ├── core-database/    Room (versión 1, sin entidades de negocio en fase 1)
│   ├── core-firebase/    DI de Firebase, selección de emuladores por flavor
│   └── core-testing/     reglas de coroutines, fakes, helper testMvi, utilidades Robolectric/Roborazzi
├── functions/            Cloud Functions (TypeScript)
├── openspec/
└── docs/superpowers/
```

Los módulos `feature/*` se crean en la fase que los introduce. Reglas de dependencia:

- `feature-*` → `core-mvi`, `core-ui`, `core-model`, `core-data` (nunca otra `feature-*`).
- `core-data` → `core-model`, `core-firebase`, `core-database`.
- `core-model` y `core-mvi` no dependen de Compose.
- `app` → todo lo anterior; es el único que conoce la navegación entre features.

### 3.1 Plugins de convención (`build-logic`)

| Plugin | Aplica |
|---|---|
| `violin.android.application` | AGP app, SDKs, flavors `dev`/`prod`, signing, Jacoco |
| `violin.android.library` | AGP library, SDKs, JUnit5, Jacoco, ktlint |
| `violin.android.compose` | Compose compiler plugin + BOM + Roborazzi |
| `violin.android.hilt` | Hilt + KSP |
| `violin.jvm.library` | Kotlin/JVM (para `core-model`) + JUnit5 + ktlint |
| `violin.android.feature` | library + compose + hilt + dependencias `core-*` estándar |

### 3.2 Versiones

| Componente | Versión |
|---|---|
| Kotlin | 2.2.10 |
| AGP | 9.2.1 |
| KSP | la compatible con Kotlin 2.2.10 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| JDK de compilación | 21 (bytecode Java 17) |
| Hilt | 2.59 |
| Room | 2.7.x |
| Compose BOM | la estable más reciente compatible con Kotlin 2.2.10 |
| Firebase BOM | 34.x |
| Navigation Compose | 2.8.x (rutas tipadas con kotlinx.serialization) |

Todas en `gradle/libs.versions.toml`. Las filas sin número exacto (KSP, Compose BOM) se fijan en la primera tarea del plan, comprobando que compilan con Kotlin 2.2.10 y AGP 9.2.1.

## 4. Arquitectura MVI

### 4.1 Contrato (`core-mvi`)

```kotlin
interface UiState
interface UiIntent
interface UiEffect

abstract class MviViewModel<S : UiState, I : UiIntent, E : UiEffect>(initial: S) : ViewModel() {
    val state: StateFlow<S>
    val effects: Flow<E>          // respaldado por Channel(Channel.BUFFERED), consumo único
    fun onIntent(intent: I)       // procesa intents en orden de llegada
    protected fun setState(reduce: S.() -> S)
    protected fun sendEffect(effect: E)
}
```

Garantías que el contrato debe cumplir y probar:

- **Orden:** los intents se procesan en orden de llegada (un `Channel` de intents consumido por una única coroutine en `viewModelScope`).
- **Atomicidad:** `setState` usa `MutableStateFlow.update`.
- **Efectos de consumo único:** un efecto emitido se entrega exactamente una vez, aunque no haya colector en ese momento (se bufferiza hasta que la UI vuelve a colectar tras una rotación).

### 4.2 Convenciones por feature

```
feature-x/
├── XContract.kt    XState (data class inmutable), XIntent, XEffect (sealed interfaces)
├── XReducer.kt     función pura (XState, XMutation) -> XState
├── XViewModel.kt   extiende MviViewModel; intent -> caso de uso -> mutación -> reducer
└── XScreen.kt      XRoute (conecta ViewModel) + XScreen(state, onIntent) stateless
```

- `XReducer` no importa Android ni coroutines.
- `XScreen` es stateless: previews y tests Roborazzi sin ViewModel.
- La UI colecta con `collectAsStateWithLifecycle()` y los efectos en `LaunchedEffect` con `repeatOnLifecycle(STARTED)`.

### 4.3 Portado desde los originales

Ambos originales son MVVM. Al portar una feature (fases 2–5): se conservan casos de uso, repositorios y lógica pura (YIN, subida, face blur); se reescriben ViewModels y pantallas al contrato MVI.

## 5. Firebase

- `google-services.json` **fuera de git** (`app/src/dev/` y `app/src/prod/`, en `.gitignore`). En CI se decodifican desde los secretos `GOOGLE_SERVICES_DEV_BASE64` y `GOOGLE_SERVICES_PROD_BASE64`.
- `firebase.json` con emuladores Auth (9099), Firestore (8080), Storage (9199), Functions (5001) y UI.
- Flavor `dev`: `BuildConfig.USE_EMULATORS = true` en debug; `core-firebase` apunta a `10.0.2.2` desde el emulador Android.
- `firestore.rules` y `storage.rules`: deny-all en fase 1.
- App Check: Play Integrity en `prod`, debug provider en `dev`.
- Crashlytics, Analytics y Performance inicializados desde el arranque. Crashlytics desactivado en `dev`.
- Remote Config y FCM: fuera de la fase 1.

### 5.1 Cloud Functions (`functions/`)

- TypeScript, Node 20, `firebase-functions` v2.
- Función `health` (callable, `enforceAppCheck: true`) → `{ status: "ok", version }`.
- Tests: Jest + `firebase-functions-test` contra el emulador.
- ESLint + `tsc --noEmit` en CI.
- **Prerequisito manual (usuario):** activar el plan Blaze en `violin-app-795ee` y `violin-app-dev`.

## 6. Pantalla de muestra (fase 1)

Una única pantalla `Home` en `app/` (se reemplazará en la fase 3) que valida la cadena completa:

- `HomeState(status: HealthStatus)` con `HealthStatus = Idle | Loading | Ok(version) | Error(message)`.
- `HomeIntent.CheckHealth`.
- `HomeEffect.ShowError(message)` (snackbar).
- El ViewModel llama a `HealthRepository` (en `core-data`), que invoca la callable `health`.

## 7. CI/CD

Un único workflow `.github/workflows/ci.yml`:

| Job | Disparador | Pasos |
|---|---|---|
| `secrets-guard` | PR y push | Falla si git contiene `google-services.json`, `*.jks`, `*.keystore`, `.env` (excepto `.env.example`) |
| `android` | PR y push | ktlint, lint, `assembleDevDebug`, tests unitarios, JaCoCo agregado con mínimo del 80 % en `core-model`, `core-data`, `core-mvi` y reducers |
| `functions` | PR y push | `npm ci`, lint, build, tests con el emulador |
| `rules` | PR y push | Tests de `firestore.rules` y `storage.rules` con `@firebase/rules-unit-testing` en el emulador |
| `e2e` | PR (`continue-on-error: true` al principio) | Emulador Android (API 34, x86_64) + emuladores Firebase; test instrumentado de la pantalla Home |
| `release` | push a `main` | `bundleProdRelease` firmado → fastlane `deploy_firebase` → App Distribution (grupo `testers`) |

Secretos de GitHub: `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `GOOGLE_SERVICES_DEV_BASE64`, `GOOGLE_SERVICES_PROD_BASE64`, `FIREBASE_SERVICE_ACCOUNT_JSON`.

**Keystore:** nuevo, de subida, exclusivo de `com.violinstudio`. Se guarda fuera del repo. Su SHA-256 se registra en Firebase (App Check / Play Integrity).

**Git:** `main` protegida; ramas `feat/*`; merge por PR con CI en verde.

## 8. Tests

| Capa | Herramientas |
|---|---|
| Unitarios JVM (reducers, modelos, repositorios, ViewModels) | JUnit5, MockK, Turbine, kotlinx-coroutines-test |
| UI / capturas | Robolectric + Roborazzi (JUnit4 vía vintage engine donde lo exija Robolectric) |
| Instrumentados / E2E | AndroidX Test + Compose UI test contra emuladores Firebase |
| Functions | Jest + firebase-functions-test |
| Reglas | @firebase/rules-unit-testing |

`core-testing` provee `MainDispatcherExtension` (JUnit5) y el DSL:

```kotlin
viewModel.testMvi {
    intent(HomeIntent.CheckHealth)
    assertState { it.status is HealthStatus.Loading }
    assertState { it.status == HealthStatus.Ok("1.0.0") }
    assertNoEffects()
}
```

Se sigue TDD estricto: cada tarea del plan empieza con un test que falla.

## 9. Criterios de terminado

1. `./gradlew check` pasa en local y en CI (ktlint, lint, tests, cobertura).
2. `core-mvi` tiene tests de orden de intents, atomicidad de estado y entrega única de efectos con rotación simulada.
3. Home: test del reducer, test del ViewModel con `testMvi` y captura Roborazzi de los cuatro estados.
4. `functions`: test de `health` en verde contra el emulador.
5. `rules`: test que confirma que el deny-all rechaza lecturas y escrituras en Firestore y Storage.
6. E2E: la app arranca en el emulador Android contra los emuladores Firebase y muestra `Ok(version)`.
7. Push a `main` produce un AAB firmado publicado en App Distribution.
8. `secrets-guard` en verde.

## 10. Fuera de alcance de la fase 1

Login y cualquier feature de negocio, entidades Room reales, FCM, Remote Config, Gemini, Play Store.

## 11. Riesgos

| Riesgo | Mitigación |
|---|---|
| AGP 9 + Kotlin 2.2 con plugins de convención | Violin-master ya compila con esas versiones; se copian sus ajustes |
| Plan Blaze no activado | Bloquea el deploy de Functions, pero no el desarrollo (se usa el emulador). Prerequisito manual del usuario |
| Robolectric/Roborazzi requieren JUnit4 | Se usa `junit-vintage-engine` solo en los módulos de UI |
| Coste del job E2E en CI | Empieza sin bloquear; pasa a bloquear cuando sea estable |
