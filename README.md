# Violin Studio

App Android para aprender y practicar violín, pensada tanto para quien estudia por su cuenta como para
alumnos que trabajan con un profesor.

## Qué ofrece

- **Práctica diaria**: afinador, metrónomo, sesiones de estudio con estadísticas y un currículo de
  lecciones por niveles.
- **Profesor y alumno**: el alumno graba sus ejercicios, el profesor los revisa, deja comentarios y
  asigna tareas, y ambos hablan por chat.
- **Motivación**: logros, rachas y feedback sobre la práctica.
- **Privacidad desde el diseño**: cuentas con email o Google, consentimiento versionado,
  autorización del tutor para menores de 14 años y borrado completo de la cuenta y sus datos en
  cualquier momento.

## Estado

El desarrollo va por fases:

| Fase | Contenido | Estado |
|---|---|---|
| 1. Base | Arquitectura, Firebase, CI/CD, pantalla de comprobación del servidor | ✅ |
| 2. Identidad y privacidad | Registro, perfil, consentimiento, flujo del tutor, borrado de cuenta | En curso |
| 3. Práctica | Afinador, metrónomo, sesiones, estadísticas, currículo | Pendiente |
| 4. Profesor–alumno | Grabación, subida, tareas, revisión, chat | Pendiente |
| 5. Extras | Gamificación, feedback inteligente, configuración remota | Pendiente |

## Tecnología

- **Android**: Kotlin, Jetpack Compose con Material 3, Hilt, Navigation Compose y coroutines/Flow.
- **Backend**: Firebase (Auth, Firestore, Storage, App Check, Crashlytics, Performance) y Cloud
  Functions en TypeScript para la lógica que debe aplicarse en servidor.
- **Calidad**: JUnit 5, Turbine, MockK, Robolectric, capturas con Roborazzi, JaCoCo (umbral del 80 %),
  ktlint y tests de reglas de seguridad contra los emuladores de Firebase.
- **Entrega**: GitHub Actions y fastlane, que publica en Firebase App Distribution.

## Arquitectura

Clean Architecture en cuatro módulos:

```
app/     Application, App Check y configuración por flavor (dev/prod). Ensambla el resto.
ui/      → domain · MainActivity, navegación y feature/<nombre>/{view,viewmodel};
                      en commons/ el tema, los componentes compartidos y la base MVI.
domain/  (Kotlin puro) · feature/<nombre>/{model,repository,usecase,failure}.
data/    → domain · feature/<nombre>/{datasource,dto,repository,utils};
                      en commons/ la DI y la configuración de Firebase.
functions/  Cloud Functions (TypeScript).
```

- Los repositorios son interfaces en `domain`, implementadas en `data`. Cada caso de uso es una clase.
- Cada pantalla sigue MVI: `Contract` (State/Intent/Effect), `Reducer` puro, `ViewModel` que extiende
  `MviViewModel` y usa casos de uso, y `Screen` sin estado.
- Las reglas de Firestore y Storage deniegan todo por defecto. Cada funcionalidad abre solo lo que
  necesita, siempre con sus tests.

## Requisitos

JDK 21, Android SDK 36, Node 22 y Firebase CLI (`npm i -g firebase-tools`).

Los `google-services.json` no están en git: descárgalos de la consola de Firebase a `app/src/dev/` y
`app/src/prod/`. Dev y prod usan de momento el mismo proyecto, `violin-app-dev-f0b55`.

## Comandos

```bash
./gradlew check coverage                           # ktlint, lint, tests y cobertura
./gradlew :ui:recordRoborazziDebug                 # regrabar capturas
./gradlew :ui:verifyRoborazziDebug                 # comparar capturas
npm --prefix functions test                        # tests unitarios de Functions

# Tests de integración de Functions contra los emuladores
firebase emulators:exec --only functions,auth,firestore,storage \
  --project demo-violin-studio "npm --prefix functions run test:integration"

# Tests de las reglas de seguridad
firebase emulators:exec --only firestore,storage --project demo-violin-studio \
  "npm --prefix rules-tests test"

# E2E: arranca los emuladores y lanza los tests instrumentados
firebase emulators:start --only functions,auth,firestore,storage --project violin-app-dev-f0b55
./gradlew :app:connectedDevDebugAndroidTest

bash scripts/secrets-guard.sh                      # comprueba que no hay secretos en git
```

Para probar en un móvil físico contra los emuladores del PC:
`./gradlew installDevDebug -Pviolin.emulatorHost=<IP del PC>`. Los emuladores ya escuchan en
`0.0.0.0`, así que úsalo solo en una red de confianza.

## Despliegue

### Primer despliegue de Functions

1. Confirma que el proyecto (`violin-app-dev-f0b55`) está en el plan Blaze, que Functions requiere.
2. Crea `functions/.env.violin-app-dev-f0b55` con `ENFORCE_APP_CHECK=false`. No se versiona.
3. `npm --prefix functions run build`
4. `firebase deploy --only functions --project violin-app-dev-f0b55`
5. Verifica el despliegue con "Comprobar servidor" en la app.

### App Check en prod

Play Integrity solo valida apps instaladas desde Google Play. Mientras la app se reparta por App
Distribution, mantén `ENFORCE_APP_CHECK=false` en `functions/.env.violin-app-dev-f0b55`. Firebase
carga `.env.<project-id>` según el proyecto activo; cambia el nombre cuando exista un proyecto de
producción propio.

### Releases

Cada push a `main` firma el APK y el AAB y publica en App Distribution. El job necesita estos
secretos en el repositorio:

| Secreto / variable | Contenido |
|---|---|
| `GOOGLE_SERVICES_DEV_BASE64`, `GOOGLE_SERVICES_PROD_BASE64` | `google-services.json` en base64 |
| `KEYSTORE_BASE64` | Keystore de subida en base64 |
| `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | Credenciales del keystore |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Cuenta de servicio para App Distribution |
| `FIREBASE_APP_ID_PROD` (variable) | App ID de Firebase de prod |
