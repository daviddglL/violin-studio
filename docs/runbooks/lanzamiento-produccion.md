# Runbook: preparar producción

Pasos fuera del repositorio que hay que hacer antes de publicar fuera del grupo de testers. Se hacen en este orden:
cada uno depende del anterior.

## 1. Cuenta de servicio de CI con permisos mínimos

El job `release` solo sube el APK a App Distribution. No debe usar `firebase-adminsdk-fbsvc@…`, que tiene acceso de
administrador a todo el proyecto (Auth, Firestore y Storage con datos de menores): si se filtrara el secreto
`FIREBASE_SERVICE_ACCOUNT_JSON`, se filtraría todo.

Hecho en `violin-app-dev-f0b55` el 2026-10-21: CI usa `ci-app-distribution@violin-app-dev-f0b55`, que solo tiene
`roles/firebaseappdistro.admin`. Repítelo en el proyecto de producción (paso 2.5).

```bash
PROJECT=<id-del-proyecto>
gcloud iam service-accounts create ci-app-distribution \
  --project "$PROJECT" --display-name "CI: subida a App Distribution"
gcloud projects add-iam-policy-binding "$PROJECT" \
  --member "serviceAccount:ci-app-distribution@$PROJECT.iam.gserviceaccount.com" \
  --role roles/firebaseappdistro.admin
gcloud iam service-accounts keys create ci-app-distribution.json \
  --iam-account "ci-app-distribution@$PROJECT.iam.gserviceaccount.com"
```

1. Copia el contenido de `ci-app-distribution.json` en el secreto `FIREBASE_SERVICE_ACCOUNT_JSON` del repositorio y
   borra el fichero local.
2. Lanza un push a `main` y comprueba que el job `release` publica.
3. Revoca la clave que usaba CI en `firebase-adminsdk-fbsvc@…` (Consola de Google Cloud > IAM > Cuentas de servicio >
   Claves). No borres la cuenta: la usan las Functions.

Mejor todavía, sin claves: Workload Identity Federation con
[`google-github-actions/auth`](https://github.com/google-github-actions/auth), limitado a este repositorio y a `main`.

## 2. Proyecto de producción propio

Hoy `backend/.firebaserc` apunta `dev` y `prod` al mismo proyecto (`violin-app-dev-f0b55`), así que los testers y el
desarrollo comparten datos.

1. Crea el proyecto de producción en el plan Blaze, en la región `europe-west1`, y habilita Auth (email y Google),
   Firestore, Storage, Hosting y App Check.
2. Registra la app Android `com.violinstudio` y descarga su `google-services.json` al secreto
   `GOOGLE_SERVICES_PROD_BASE64`. Actualiza la variable `FIREBASE_APP_ID_PROD`.
3. Cambia `prod` en `backend/.firebaserc` por el id nuevo.
4. Crea `backend/functions/.env.<id-prod>` (no se versiona) con al menos:
   - `GUARDIAN_LINK_BASE_URL=https://<id-prod>.web.app`. También fija la URL de la política que ve la app
     (`policyUrl()`); sin ella se mostraría la de desarrollo.
   - `ENFORCE_APP_CHECK=false` hasta completar el paso 4.
   - Los interruptores de la sección "Interruptores en caliente" del README (`RECORDINGS_ENABLED`, `VIDEO_ENABLED`,
     `MINOR_RECORDING_ENABLED`, `CHAT_ENABLED`, `NOTIFICATIONS_ENABLED`), apagados hasta que la política publicada
     cubra cada función.
5. Repite el paso 1 (cuenta de servicio de CI) en el proyecto nuevo.

## 3. Correo del tutor

`backend/extensions/firestore-send-email.env` trae `DEFAULT_FROM=Violin Studio <no-reply@violin-studio.invalid>`, un dominio
que no existe, así que ningún correo al tutor llegaría.

1. Elige un dominio propio y configura SPF, DKIM y DMARC con tu proveedor SMTP.
2. Crea `backend/extensions/firestore-send-email.env.<id-prod>` con `DEFAULT_FROM=Violin Studio <no-reply@<tu-dominio>>`.
   Firebase lo usa en lugar del fichero común para ese proyecto.
3. Guarda `SMTP_CONNECTION_URI` y `SMTP_PASSWORD` como parámetros secretos de la extensión (Secret Manager), nunca en
   el repositorio.
4. Prueba el flujo de un menor de extremo a extremo con un correo real.

## 4. App Check (issue #36)

Play Integrity solo valida apps instaladas desde Google Play, así que App Distribution no sirve para esto.

1. Sube el AAB a la pista de pruebas internas de Google Play. CI lo hace en cada push a `main` con la lane
   `deploy_play` de fastlane en cuanto existe el secreto `PLAY_SERVICE_ACCOUNT_JSON`:
   1. En Play Console crea la app `com.violinstudio` y sube **a mano** el primer AAB (el artefacto `release-aab` de
      un run de CI): la API de Play no puede crear la app ni hacer la primera subida. Acepta Play App Signing.
   2. En Google Cloud, en el proyecto que quieras, crea una cuenta de servicio sin roles de proyecto (por ejemplo
      `ci-play-upload`), habilita la API "Google Play Android Developer" y crea una clave JSON.
   3. En Play Console > Usuarios y permisos, invita el correo de esa cuenta solo para esta app con el permiso
      "Publicar en pistas de prueba" (sin producción).
   4. Copia el JSON en el secreto `PLAY_SERVICE_ACCOUNT_JSON` del repositorio y borra el fichero local.
   5. Mientras la app no tenga ninguna versión publicada, Play solo acepta borradores (`draft`): publica cada versión
      desde Play Console. Cuando la pista interna ya esté publicada, crea la variable de repositorio
      `PLAY_RELEASE_STATUS=completed` para que CI la publique directamente.
   6. Añade a los testers en Pruebas internas e instala la app desde el enlace de Play.
2. En la consola de Firebase, registra Play Integrity con la huella SHA-256 de la clave de firma de Play.
3. Comprueba en App Check > Métricas que las peticiones llegan como verificadas durante unos días.
4. Activa la aplicación, en este orden: Functions (`ENFORCE_APP_CHECK=true` y despliegue), después Firestore y por
   último Storage desde la consola.
5. Cierra el issue #36 con las métricas como prueba.

## 5. Antes de abrir la app al público

- Revisión legal de la política v2 (puerta 0 del README).
- Data safety en Play Console según `docs/data-safety.md`, incluidos las grabaciones, el chat y los avisos cuando
  se activen.
- Cuestionario de clasificación de contenido y público objetivo. Si se admite a menores de 13, revisa la política de
  Familias de Google Play.
