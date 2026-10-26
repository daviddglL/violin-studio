# Runbook: operación del backend

Tareas de mantenimiento y de respuesta a incidentes una vez la app esté en producción. Las secciones 2 a 4 necesitan el
plan Blaze: hazlas al crear el proyecto de producción ([lanzamiento-produccion.md](lanzamiento-produccion.md), paso 2).
En los comandos, `PROJECT` es el id del proyecto y `BILLING` el id de la cuenta de facturación
(`gcloud billing accounts list`).

## 1. Forzar una actualización de la app

La app compara su `versionCode` con `minVersionCode` de `identityConfig`. Si es menor, solo muestra "Actualiza la app"
(abre la ficha de Google Play) y "Cerrar sesión": no deja entrar a ninguna otra pantalla. Úsalo cuando una versión antigua tenga un
fallo grave o hable un protocolo que el backend ya no acepta.

- El `versionCode` es el número del run de CI que la compiló (`GITHUB_RUN_NUMBER`). App Distribution lo muestra en las
  notas de cada versión (`CI #<número>`) y Play Console en la lista de versiones.
- **Sin desplegar**: en la consola de Firestore, en el documento `appConfig/features`, pon el campo numérico
  `minVersionCode` con el `versionCode` mínimo. Tarda hasta un minuto en llegar (caché de Functions). Para quitarlo,
  borra el campo.
- **Por despliegue**: `MIN_VERSION_CODE=<n>` en `backend/functions/.env.<project-id>` y despliega Functions. El
  documento solo puede subir ese mínimo, nunca bajarlo; para bajarlo hay que redesplegar.
- Un valor que no sea un entero mayor o igual que 0 se ignora: un error de escritura nunca bloquea a todos.
- Comprueba antes de subirlo que la versión nueva ya está disponible en la pista que usan tus usuarios. Si no, nadie
  podrá entrar hasta que lo bajes.
- Límites: el aviso aparece tras iniciar sesión (sin sesión la app no consulta `identityConfig`), y una app sin conexión
  usa la última configuración guardada hasta que vuelve a tener red.

## 2. Copias de seguridad de Firestore

Firestore no guarda copias por defecto: un borrado accidental (una regla mal desplegada, un script) no se puede
deshacer.

```bash
# Recuperación a un instante de los últimos 7 días.
gcloud firestore databases update --database='(default)' --enable-pitr --project "$PROJECT"
# Copia diaria conservada 7 días.
gcloud firestore backups schedules create --database='(default)' --recurrence=daily --retention=7d --project "$PROJECT"
gcloud firestore backups list --project "$PROJECT"
```

Para restaurar, crea una base de datos nueva a partir de la copia y copia desde ella solo lo que haga falta. No
sobrescribas `(default)` entera, o perderías lo escrito después de la copia:

```bash
gcloud firestore databases restore --source-backup=<ruta-de-la-copia> --destination-database=restaurada \
  --project "$PROJECT"
```

Privacidad: las copias conservan durante su retención los datos de las cuentas ya borradas. Mantén la retención corta
(7 días) y declárala en la política de privacidad antes de activarlas. Si restauras, vuelve a borrar de lo restaurado las
cuentas eliminadas después de la fecha de la copia.

Storage: las grabaciones ya tienen su ciclo de vida (`backend/infra/storage-lifecycle.json`). No hagas copia de ellas,
porque se borran a propósito.

## 3. Presupuesto y alertas de gasto

Un presupuesto avisa pero no corta el gasto. Créalo con avisos al 50, 90 y 100 % (por correo a los administradores de
facturación):

```bash
gcloud billing budgets create --billing-account="$BILLING" --display-name="violin-studio $PROJECT" \
  --filter-projects="projects/$PROJECT" --budget-amount=10EUR \
  --threshold-rule=percent=0.5 --threshold-rule=percent=0.9 --threshold-rule=percent=1.0
```

Si el gasto se dispara (un bucle de triggers, abuso de un callable), apaga lo que lo causa con los interruptores de
`appConfig/features` (README, "Interruptores en caliente") antes de investigar.

## 4. Alertas de errores

Functions (2.ª generación) escribe sus logs como servicios de Cloud Run. Crea una métrica con los errores:

```bash
gcloud logging metrics create functions-errores --project "$PROJECT" \
  --description="Errores de Cloud Functions" \
  --log-filter='resource.type="cloud_run_revision" AND severity>=ERROR'
```

Después, en Cloud Monitoring > Alertas, crea una política sobre `logging/user/functions-errores`: más de 5 en 10
minutos, con notificación por correo. Los logs no llevan datos personales (solo códigos), así que el correo de alerta
tampoco.

En Firebase Console > Crashlytics > Alertas, activa las alertas de fallos nuevos, de regresiones y de velocidad.

## 5. Comprobaciones tras cada despliegue

1. La pantalla de comprobación del servidor de la app (o el callable `health`) responde con la versión desplegada.
2. Logs de Functions sin errores nuevos durante 10 minutos.
3. Si cambiaste reglas, los tests de reglas pasaron en CI con el mismo commit.

## 6. Ante un incidente

1. Contén: apaga la función afectada en `appConfig/features` o fuerza una actualización (sección 1).
2. Si hay pérdida o corrupción de datos, restaura desde la copia (sección 2) en una base aparte.
3. Si hay datos personales expuestos, la notificación a la AEPD es obligatoria en 72 horas (art. 33 RGPD). Apunta qué,
   cuándo y a quién afecta desde el primer momento.
