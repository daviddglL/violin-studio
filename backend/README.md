# Backend de Violin Studio

Todo lo que se despliega en Firebase. Los comandos de Firebase y Node se ejecutan desde esta carpeta.

```
functions/    Cloud Functions (TypeScript) y scripts de administración (npm run admin:*).
rules-tests/  Tests de las reglas de Firestore y Storage contra los emuladores.
hosting/      Política de privacidad y página del tutor (/tutor reescribe a guardianConsent).
extensions/   Parámetros de la extensión Trigger Email (firestore-send-email).
infra/        Configuración fuera de Firebase (ciclo de vida del bucket).
firestore.rules · firestore.indexes.json · storage.rules · firebase.json · .firebaserc
```

```bash
npm ci --prefix functions && npm --prefix functions run build
npm --prefix functions test
firebase emulators:start --only functions,auth,firestore,storage --project violin-app-dev-f0b55
firebase deploy --only functions --project violin-app-dev-f0b55
```

Los tests de paridad de la app (`domain`, `data`) leen ficheros de aquí: si cambias reglas, límites o textos del
servidor, la CI de Android también se ejecuta. Requisitos, despliegue e interruptores: [README principal](../README.md).
