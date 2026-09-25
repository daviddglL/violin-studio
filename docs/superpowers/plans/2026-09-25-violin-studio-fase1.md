# Violin Studio — Fase 1 (Base) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Crear el repo `violin-studio` con el esqueleto multimódulo, el contrato MVI, Firebase (Functions + reglas) y CI/CD completos, validados de extremo a extremo por una pantalla Home que llama a la Function `health`.

**Architecture:** Gradle multimódulo con plugins de convención en `build-logic`. Cada pantalla sigue MVI (`Contract` + `Reducer` puro + `MviViewModel` + `Screen` stateless). El cliente habla con Firebase a través de repositorios en `core-data`; la lógica de servidor vive en Cloud Functions (TypeScript). Todo se prueba contra fakes (JVM) o contra los emuladores de Firebase (integración/E2E).

**Tech Stack:** Kotlin 2.2.10, AGP 9.2.1 (Kotlin integrado), Gradle 9.4.1, Compose (BOM 2024.09.00) + Material 3, Hilt 2.59 + KSP 2.3.5, Navigation Compose 2.8.9 con rutas tipadas, Firebase BOM 34.12.0, JUnit5 + MockK + Turbine, Robolectric 4.16.1 + Roborazzi 1.59.0, JaCoCo 0.8.12, ktlint (plugin 12.2.0), Cloud Functions v2 (Node 20, TypeScript, Jest), `@firebase/rules-unit-testing`, GitHub Actions, fastlane.

**Spec:** `docs/superpowers/specs/2026-09-25-violin-studio-fase1-design.md`

## Global Constraints

- Raíz del repo: `C:\Users\ragna\OneDrive\Escritorio\violin-studio` (todas las rutas del plan son relativas a ella).
- `applicationId` / namespace raíz: `com.violinstudio`; flavor `dev` añade `.dev`.
- Paquetes de módulos: `com.violinstudio.core.<nombre>` (p. ej. `com.violinstudio.core.mvi`).
- compileSdk 36, targetSdk 36, minSdk 26, JDK y bytecode 21.
- Versiones exactas: las de `gradle/libs.versions.toml` de la Task 1. No añadir dependencias fuera del catálogo.
- Firebase: prod `violin-app-795ee`, dev `violin-app-dev`; emuladores con proyecto `demo-violin-studio` salvo el E2E (usa `violin-app-dev`).
- Región de Functions: `europe-west1`.
- Puertos de emulador: Auth 9099, Firestore 8080, Storage 9199, Functions 5001.
- Nunca se versionan `google-services.json`, `*.jks`, `*.keystore`, `.env` (salvo `.env.example`).
- Cobertura de líneas ≥ 80 % en `core-model`, `core-data`, `core-mvi` y en las clases `*Reducer*` de `app`.
- TDD estricto: cada paso de código empieza con un test que falla.
- Textos visibles al usuario en español, en `strings.xml`.
- Mensajes de commit: Conventional Commits en inglés, terminados en `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Doble pulsación en "Comprobar servidor" mientras carga** → solo se lanza una petición: el botón está deshabilitado en `Loading` (test en Task 8).
2. **Error sin mensaje** (excepción con `message == null`) → la UI muestra "Error desconocido", nunca "null" ni un hueco vacío (tests en Tasks 7 y 8).
3. **Respuesta inesperada de `health`** (no es un objeto, falta `version`, `status` distinto de `"ok"`) → estado `Error` con mensaje, nunca un crash (tests en Task 5).
4. **Rotación con un error pendiente** → el snackbar se muestra una vez, ni se pierde ni se duplica: los efectos se bufferizan sin colector y se entregan una sola vez (test en Task 2).
5. **Pantalla cerrada con la petición en vuelo** → la cancelación no se convierte en estado `Error`: `CancellationException` se relanza (test en Task 5).

---

## Prerrequisitos manuales (los hace el usuario)

El ejecutor **se detiene** y pide al usuario cada uno de estos pasos cuando llegue a la task indicada. No los simula ni los inventa.

| # | Antes de | Acción |
|---|---|---|
| P1 | Task 7 | En la consola de Firebase: en `violin-app-dev` añadir app Android `com.violinstudio.dev`; en `violin-app-795ee` añadir app Android `com.violinstudio`. Descargar cada `google-services.json` a `app/src/dev/google-services.json` y `app/src/prod/google-services.json`. |
| P2 | Task 8 (emulador Android) y Task 9 (Firebase CLI) | Tener un emulador Android (API 34) y Firebase CLI (`npm i -g firebase-tools`, necesita JDK 21) instalados en local. |
| P3 | Task 12 | Generar el keystore: `keytool -genkeypair -v -keystore keystore/upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`. Registrar su SHA-256 (`keytool -list -v -keystore keystore/upload.jks -alias upload`) en la app `com.violinstudio` de Firebase. Guardar las contraseñas en un gestor de contraseñas. |
| P4 | Task 13 | Confirmar la creación del repo remoto y dar valores para los secretos de GitHub. Activar el plan Blaze en ambos proyectos si se van a desplegar Functions. |

---

### Task 1: Esqueleto Gradle, plugins de convención y `core-model`

**Files:**
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` (copiados de `..\violin-master`)
- Create: `.gitignore`, `.editorconfig`, `gradle.properties`, `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`
- Create: `build-logic/settings.gradle.kts`, `build-logic/convention/build.gradle.kts`
- Create: `build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt`
- Create: `build-logic/convention/src/main/kotlin/AndroidLibraryConventionPlugin.kt`
- Create: `build-logic/convention/src/main/kotlin/AndroidComposeConventionPlugin.kt`
- Create: `build-logic/convention/src/main/kotlin/AndroidHiltConventionPlugin.kt`
- Create: `build-logic/convention/src/main/kotlin/JvmLibraryConventionPlugin.kt`
- Create: `build-logic/convention/src/main/kotlin/com/violinstudio/buildlogic/{Catalog.kt,KotlinConfig.kt,Testing.kt,Coverage.kt}`
- Create: `core/core-model/build.gradle.kts`, `core/core-model/src/main/kotlin/com/violinstudio/core/model/HealthInfo.kt`
- Test: `core/core-model/src/test/kotlin/com/violinstudio/core/model/HealthInfoTest.kt`

**Interfaces:**
- Produces: plugins `violin.android.application`, `violin.android.library`, `violin.android.compose`, `violin.android.hilt`, `violin.jvm.library`; función `fun Project.configureCoverage(classPaths: List<String>, variant: String = "debug", minimum: String = "0.80")` en el paquete `com.violinstudio.buildlogic`, que registra la tarea `coverageVerification` y la engancha a `check`; `data class HealthInfo(val status: String, val version: String) { val isOk: Boolean }`.

- [ ] **Step 1: Copiar el wrapper de Gradle y crear los ficheros raíz**

```bash
cd /c/Users/ragna/OneDrive/Escritorio/violin-studio
cp ../violin-master/gradlew ../violin-master/gradlew.bat .
mkdir -p gradle/wrapper && cp ../violin-master/gradle/wrapper/gradle-wrapper.jar ../violin-master/gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
```

`.gitignore`:

```gitignore
.gradle/
build/
.kotlin/
local.properties
.idea/
*.iml
.DS_Store
captures/

# Secretos: nunca en git
google-services.json
*.jks
*.keystore
keystore/
.env
.env.*
!.env.example

# Node / Firebase
node_modules/
functions/lib/
*.log

# fastlane
fastlane/report.xml
```

`.editorconfig`:

```ini
root = true

[*.{kt,kts}]
indent_size = 4
max_line_length = 120
ktlint_code_style = android_studio
ktlint_function_naming_ignore_when_annotated_with = Composable
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.workers.max=4
kotlin.code.style=official
kotlin.compiler.execution.strategy=in-process
android.nonTransitiveRClass=true
```

- [ ] **Step 2: Crear el catálogo de versiones**

`gradle/libs.versions.toml`:

```toml
[versions]
agp = "9.2.1"
kotlin = "2.2.10"
ksp = "2.3.5"
coreKtx = "1.18.0"
lifecycle = "2.8.7"
activityCompose = "1.10.1"
composeBom = "2024.09.00"
navigationCompose = "2.8.9"
kotlinxSerialization = "1.9.0"
coroutines = "1.10.2"
hilt = "2.59"
hiltNavigationCompose = "1.2.0"
firebaseBom = "34.12.0"
googleServices = "4.4.2"
firebaseCrashlyticsPlugin = "3.0.3"
junit5 = "5.12.2"
junitPlatform = "1.12.2"
junit4 = "4.13.2"
mockk = "1.14.5"
turbine = "1.2.1"
robolectric = "4.16.1"
roborazzi = "1.59.0"
androidxTestExtJunit = "1.3.0"
androidxTestRunner = "1.6.2"
espresso = "3.7.0"
jacoco = "0.8.12"
ktlintGradle = "12.2.0"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-ktx = { module = "androidx.lifecycle:lifecycle-viewmodel-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigationCompose" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-graphics = { module = "androidx.compose.ui:ui-graphics" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
compose-material3 = { module = "androidx.compose.material3:material3" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-play-services = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-play-services", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version.ref = "hiltNavigationCompose" }
firebase-bom = { module = "com.google.firebase:firebase-bom", version.ref = "firebaseBom" }
firebase-functions = { module = "com.google.firebase:firebase-functions" }
firebase-analytics = { module = "com.google.firebase:firebase-analytics" }
firebase-crashlytics = { module = "com.google.firebase:firebase-crashlytics" }
firebase-perf = { module = "com.google.firebase:firebase-perf" }
firebase-appcheck-playintegrity = { module = "com.google.firebase:firebase-appcheck-playintegrity" }
firebase-appcheck-debug = { module = "com.google.firebase:firebase-appcheck-debug" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit5" }
junit-jupiter-api = { module = "org.junit.jupiter:junit-jupiter-api", version.ref = "junit5" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher", version.ref = "junitPlatform" }
junit-vintage-engine = { module = "org.junit.vintage:junit-vintage-engine", version.ref = "junit5" }
junit4 = { module = "junit:junit", version.ref = "junit4" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
roborazzi = { module = "io.github.takahirom.roborazzi:roborazzi", version.ref = "roborazzi" }
roborazzi-compose = { module = "io.github.takahirom.roborazzi:roborazzi-compose", version.ref = "roborazzi" }
roborazzi-junit-rule = { module = "io.github.takahirom.roborazzi:roborazzi-junit-rule", version.ref = "roborazzi" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestExtJunit" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }
androidx-espresso-core = { module = "androidx.test.espresso:espresso-core", version.ref = "espresso" }
# Solo para build-logic
android-gradlePlugin = { module = "com.android.tools.build:gradle", version.ref = "agp" }
kotlin-gradlePlugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
compose-gradlePlugin = { module = "org.jetbrains.kotlin:compose-compiler-gradle-plugin", version.ref = "kotlin" }
ksp-gradlePlugin = { module = "com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin", version.ref = "ksp" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
google-services = { id = "com.google.gms.google-services", version.ref = "googleServices" }
firebase-crashlytics = { id = "com.google.firebase.crashlytics", version.ref = "firebaseCrashlyticsPlugin" }
roborazzi = { id = "io.github.takahirom.roborazzi", version.ref = "roborazzi" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlintGradle" }
violin-android-application = { id = "violin.android.application" }
violin-android-library = { id = "violin.android.library" }
violin-android-compose = { id = "violin.android.compose" }
violin-android-hilt = { id = "violin.android.hilt" }
violin-jvm-library = { id = "violin.jvm.library" }
```

- [ ] **Step 3: Crear `settings.gradle.kts` y `build.gradle.kts` raíz**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "violin-studio"

include(":core:core-model")
```

`build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.ktlint) apply false
}

// Tareas de cobertura de todos los módulos con umbral. Cada task del plan que
// añade un módulo con umbral añade aquí su ruta.
tasks.register("coverage") {
    group = "verification"
    description = "Verifica el umbral de cobertura en todos los módulos que lo tienen."
    dependsOn(":core:core-model:coverageVerification")
}
```

- [ ] **Step 4: Crear `build-logic`**

`build-logic/settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "build-logic"
include(":convention")
```

`build-logic/convention/build.gradle.kts`:

```kotlin
plugins {
    `kotlin-dsl`
}

group = "com.violinstudio.buildlogic"

kotlin { jvmToolchain(21) }

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "violin.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "violin.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "violin.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "violin.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = "violin.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
```

`build-logic/convention/src/main/kotlin/com/violinstudio/buildlogic/Catalog.kt`:

```kotlin
package com.violinstudio.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalArgumentException("Falta '$alias' en libs.versions.toml") }

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow { IllegalArgumentException("Falta la versión '$alias'") }.requiredVersion
```

`build-logic/convention/src/main/kotlin/com/violinstudio/buildlogic/KotlinConfig.kt`:

```kotlin
package com.violinstudio.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension

internal const val COMPILE_SDK = 36
internal const val MIN_SDK = 26
internal const val JDK = 21
internal val JAVA_VERSION = JavaVersion.VERSION_21

internal fun Project.configureKotlinToolchain() {
    extensions.configure<KotlinProjectExtension> { jvmToolchain(JDK) }
}
```

`build-logic/convention/src/main/kotlin/com/violinstudio/buildlogic/Testing.kt`:

```kotlin
package com.violinstudio.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/** JUnit5 en todos los tests JVM. Gradle 9 exige declarar junit-platform-launcher. */
internal fun Project.configureJUnit5() {
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
    dependencies {
        "testImplementation"(libs.lib("junit-jupiter"))
        "testRuntimeOnly"(libs.lib("junit-platform-launcher"))
        "testImplementation"(libs.lib("kotlinx-coroutines-test"))
        "testImplementation"(libs.lib("turbine"))
        "testImplementation"(libs.lib("mockk"))
    }
}
```

`build-logic/convention/src/main/kotlin/com/violinstudio/buildlogic/Coverage.kt`:

```kotlin
package com.violinstudio.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.api.tasks.testing.Test
import java.math.BigDecimal

private val COVERAGE_EXCLUDES = listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    "**/di/**",
    "**/remote/firebase/**",
    "**/*_Factory*.class",
    "**/*_HiltModules*.class",
    "**/*_MembersInjector*.class",
    "**/Hilt_*.class",
    "**/Dagger*.class",
    "**/*ComposableSingletons*.class",
)

/**
 * Registra `coverageVerification` (LINE >= [minimum]) sobre las clases que casan con [classPaths]
 * (patrones de ruta de .class, p. ej. "com/violinstudio/core/mvi/**") y la engancha a `check`.
 *
 * Falla si no encuentra ninguna clase: JaCoCo aprueba en vacío cuando no hay clases, y eso
 * ocultaría un error de ruta (AGP 9 compila Kotlin en `intermediates/built_in_kotlinc`).
 */
fun Project.configureCoverage(
    classPaths: List<String>,
    variant: String = "debug",
    minimum: String = "0.80",
) {
    pluginManager.apply("jacoco")
    extensions.configure<JacocoPluginExtension> { toolVersion = libs.version("jacoco") }
    tasks.withType<Test>().configureEach {
        extensions.configure<JacocoTaskExtension> {
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
    }

    val isJvm = pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")
    val variantCap = variant.replaceFirstChar { it.uppercase() }
    val testTask = if (isJvm) "test" else "test${variantCap}UnitTest"
    val classRoots = if (isJvm) {
        listOf("classes/kotlin/main")
    } else {
        listOf("tmp/kotlin-classes/$variant", "intermediates/built_in_kotlinc/$variant")
    }
    val execPattern = if (isJvm) "jacoco/test.exec" else "outputs/unit_test_code_coverage/${variant}UnitTest/*.exec"
    val buildDir = layout.buildDirectory

    val verify = tasks.register<JacocoCoverageVerification>("coverageVerification") {
        group = "verification"
        description = "Cobertura de líneas >= $minimum en $classPaths"
        dependsOn(testTask)
        classDirectories.setFrom(
            fileTree(buildDir) {
                include(classRoots.flatMap { root -> classPaths.map { "$root/**/$it" } })
                exclude(COVERAGE_EXCLUDES)
            },
        )
        executionData.setFrom(fileTree(buildDir) { include(execPattern) })
        sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    this.minimum = BigDecimal(minimum)
                }
            }
        }
        doFirst {
            check(!classDirectories.asFileTree.isEmpty) {
                "coverageVerification: no hay clases en $classRoots que casen con $classPaths"
            }
        }
    }
    tasks.named("check") { dependsOn(verify) }
}
```

`build-logic/convention/src/main/kotlin/JvmLibraryConventionPlugin.kt`:

```kotlin
import com.violinstudio.buildlogic.configureJUnit5
import com.violinstudio.buildlogic.configureKotlinToolchain
import org.gradle.api.Plugin
import org.gradle.api.Project

class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")
            configureKotlinToolchain()
            configureJUnit5()
        }
    }
}
```

`build-logic/convention/src/main/kotlin/AndroidLibraryConventionPlugin.kt`:

```kotlin
import com.android.build.api.dsl.LibraryExtension
import com.violinstudio.buildlogic.COMPILE_SDK
import com.violinstudio.buildlogic.JAVA_VERSION
import com.violinstudio.buildlogic.MIN_SDK
import com.violinstudio.buildlogic.configureJUnit5
import com.violinstudio.buildlogic.configureKotlinToolchain
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")
            extensions.configure<LibraryExtension> {
                compileSdk = COMPILE_SDK
                defaultConfig {
                    minSdk = MIN_SDK
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = JAVA_VERSION
                    targetCompatibility = JAVA_VERSION
                }
                buildTypes.getByName("debug") { enableUnitTestCoverage = true }
                testOptions { unitTests.isIncludeAndroidResources = true }
            }
            configureKotlinToolchain()
            configureJUnit5()
        }
    }
}
```

`build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt`:

```kotlin
import com.android.build.api.dsl.ApplicationExtension
import com.violinstudio.buildlogic.COMPILE_SDK
import com.violinstudio.buildlogic.JAVA_VERSION
import com.violinstudio.buildlogic.MIN_SDK
import com.violinstudio.buildlogic.configureJUnit5
import com.violinstudio.buildlogic.configureKotlinToolchain
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")
            extensions.configure<ApplicationExtension> {
                compileSdk = COMPILE_SDK
                defaultConfig {
                    minSdk = MIN_SDK
                    targetSdk = COMPILE_SDK
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = JAVA_VERSION
                    targetCompatibility = JAVA_VERSION
                }
                buildFeatures { buildConfig = true }
                buildTypes.getByName("debug") { enableUnitTestCoverage = true }
                testOptions { unitTests.isIncludeAndroidResources = true }
                flavorDimensions += "environment"
                productFlavors {
                    create("dev") {
                        dimension = "environment"
                        applicationIdSuffix = ".dev"
                        versionNameSuffix = "-dev"
                    }
                    create("prod") { dimension = "environment" }
                }
            }
            configureKotlinToolchain()
            configureJUnit5()
        }
    }
}
```

`build-logic/convention/src/main/kotlin/AndroidComposeConventionPlugin.kt`:

```kotlin
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.violinstudio.buildlogic.lib
import com.violinstudio.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.findByType

/** Aplicar después de violin.android.application o violin.android.library. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply("io.github.takahirom.roborazzi")
            extensions.findByType<ApplicationExtension>()?.buildFeatures?.compose = true
            extensions.findByType<LibraryExtension>()?.buildFeatures?.compose = true
            dependencies {
                val bom = platform(libs.lib("compose-bom"))
                "implementation"(bom)
                "testImplementation"(bom)
                "androidTestImplementation"(bom)
                "implementation"(libs.lib("compose-ui"))
                "implementation"(libs.lib("compose-ui-graphics"))
                "implementation"(libs.lib("compose-ui-tooling-preview"))
                "implementation"(libs.lib("compose-material3"))
                "debugImplementation"(libs.lib("compose-ui-tooling"))
                "debugImplementation"(libs.lib("compose-ui-test-manifest"))
                // Tests de UI con Robolectric/Roborazzi: JUnit4 vía vintage engine.
                "testImplementation"(libs.lib("compose-ui-test-junit4"))
                "testImplementation"(libs.lib("robolectric"))
                "testImplementation"(libs.lib("roborazzi"))
                "testImplementation"(libs.lib("roborazzi-compose"))
                "testImplementation"(libs.lib("roborazzi-junit-rule"))
                "testImplementation"(libs.lib("junit4"))
                "testImplementation"(libs.lib("androidx-test-ext-junit"))
                "testRuntimeOnly"(libs.lib("junit-vintage-engine"))
            }
        }
    }
}
```

`build-logic/convention/src/main/kotlin/AndroidHiltConventionPlugin.kt`:

```kotlin
import com.violinstudio.buildlogic.lib
import com.violinstudio.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")
            dependencies {
                "implementation"(libs.lib("hilt-android"))
                "ksp"(libs.lib("hilt-compiler"))
            }
        }
    }
}
```

- [ ] **Step 5: Escribir el test que falla de `core-model`**

`core/core-model/build.gradle.kts`:

```kotlin
import com.violinstudio.buildlogic.configureCoverage

plugins {
    alias(libs.plugins.violin.jvm.library)
}

configureCoverage(classPaths = listOf("com/violinstudio/core/model/**"))
```

`core/core-model/src/test/kotlin/com/violinstudio/core/model/HealthInfoTest.kt`:

```kotlin
package com.violinstudio.core.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HealthInfoTest {
    @Test
    fun `isOk es true solo cuando status es ok`() {
        assertTrue(HealthInfo(status = "ok", version = "1.0.0").isOk)
        assertFalse(HealthInfo(status = "degraded", version = "1.0.0").isOk)
        assertFalse(HealthInfo(status = "OK", version = "1.0.0").isOk)
    }
}
```

- [ ] **Step 6: Ejecutar y ver que falla**

Run: `./gradlew :core:core-model:test`
Expected: FAIL de compilación, `Unresolved reference 'HealthInfo'`. (Si falla antes por configuración de Gradle, corregir la configuración hasta llegar a este error.)

- [ ] **Step 7: Implementar**

`core/core-model/src/main/kotlin/com/violinstudio/core/model/HealthInfo.kt`:

```kotlin
package com.violinstudio.core.model

/** Respuesta de la Function `health`. */
data class HealthInfo(val status: String, val version: String) {
    val isOk: Boolean get() = status == STATUS_OK

    companion object {
        const val STATUS_OK = "ok"
    }
}
```

- [ ] **Step 8: Ejecutar `check` y ver que pasa**

Run: `./gradlew :core:core-model:check`
Expected: BUILD SUCCESSFUL (tests, ktlint y `coverageVerification` en verde).

- [ ] **Step 9: Comprobar que el umbral de cobertura muerde**

Crear temporalmente `core/core-model/src/main/kotlin/com/violinstudio/core/model/Untested.kt`:

```kotlin
package com.violinstudio.core.model

object Untested {
    fun a(x: Int): Int {
        val y = x + 1
        val z = y * 2
        val w = z - 3
        val v = w / 4
        return v + y + z + w
    }
}
```

Run: `./gradlew :core:core-model:coverageVerification`
Expected: FAIL con `Rule violated for bundle core-model: lines covered ratio is 0.xx, but expected minimum is 0.80`.

Borrar `Untested.kt` y volver a ejecutar: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "build: add gradle skeleton, convention plugins and core-model

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `core-mvi` — contrato MVI

**Files:**
- Create: `core/core-mvi/build.gradle.kts`
- Create: `core/core-mvi/src/main/kotlin/com/violinstudio/core/mvi/MviContract.kt`
- Create: `core/core-mvi/src/main/kotlin/com/violinstudio/core/mvi/MviViewModel.kt`
- Test: `core/core-mvi/src/test/kotlin/com/violinstudio/core/mvi/MviViewModelTest.kt`
- Modify: `settings.gradle.kts` (añadir `include(":core:core-mvi")`), `build.gradle.kts` (añadir `":core:core-mvi:coverageVerification"` al `dependsOn` de `coverage`)

**Interfaces:**
- Consumes: plugin `violin.android.library`, `configureCoverage`.
- Produces:
  - `interface UiState`, `interface UiIntent`, `interface UiEffect`
  - `abstract class MviViewModel<S : UiState, I : UiIntent, E : UiEffect>(initial: S) : ViewModel()` con `val state: StateFlow<S>`, `val effects: Flow<E>`, `fun onIntent(intent: I)`, `protected abstract suspend fun handleIntent(intent: I)`, `protected fun setState(reduce: S.() -> S)`, `protected fun sendEffect(effect: E)`.

- [ ] **Step 1: Crear el módulo**

`core/core-mvi/build.gradle.kts`:

```kotlin
import com.violinstudio.buildlogic.configureCoverage

plugins {
    alias(libs.plugins.violin.android.library)
}

android { namespace = "com.violinstudio.core.mvi" }

configureCoverage(classPaths = listOf("com/violinstudio/core/mvi/**"))

dependencies {
    api(libs.androidx.lifecycle.viewmodel.ktx)
    api(libs.kotlinx.coroutines.core)
}
```

Añadir `include(":core:core-mvi")` a `settings.gradle.kts` y `":core:core-mvi:coverageVerification"` al `dependsOn` de la tarea `coverage` del `build.gradle.kts` raíz.

- [ ] **Step 2: Escribir los tests que fallan**

`core/core-mvi/src/test/kotlin/com/violinstudio/core/mvi/MviViewModelTest.kt`:

```kotlin
package com.violinstudio.core.mvi

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MviViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    data class TestState(val log: String = "", val count: Int = 0) : UiState

    sealed interface TestIntent : UiIntent {
        data class Append(val value: String, val delayMs: Long = 0) : TestIntent
        data object Increment : TestIntent
        data class Emit(val value: String) : TestIntent
    }

    data class TestEffect(val value: String) : UiEffect

    class TestViewModel : MviViewModel<TestState, TestIntent, TestEffect>(TestState()) {
        override suspend fun handleIntent(intent: TestIntent) = when (intent) {
            is TestIntent.Append -> {
                delay(intent.delayMs)
                setState { copy(log = log + intent.value) }
            }
            TestIntent.Increment -> setState { copy(count = count + 1) }
            is TestIntent.Emit -> sendEffect(TestEffect(intent.value))
        }
    }

    @Test
    fun `expone el estado inicial`() = runTest(dispatcher) {
        assertEquals(TestState(), TestViewModel().state.value)
    }

    @Test
    fun `procesa los intents en orden de llegada aunque el primero tarde`() = runTest(dispatcher) {
        val vm = TestViewModel()
        vm.onIntent(TestIntent.Append("a", delayMs = 1_000))
        vm.onIntent(TestIntent.Append("b"))
        advanceUntilIdle()
        assertEquals("ab", vm.state.value.log)
    }

    @Test
    fun `mil intents enviados desde varios hilos no pierden actualizaciones`() = runTest(dispatcher) {
        val vm = TestViewModel()
        withContext(Dispatchers.Default) {
            coroutineScope { repeat(1_000) { launch { vm.onIntent(TestIntent.Increment) } } }
        }
        advanceUntilIdle()
        assertEquals(1_000, vm.state.value.count)
    }

    @Test
    fun `un efecto emitido sin colector se entrega una sola vez al volver a colectar`() = runTest(dispatcher) {
        val vm = TestViewModel()
        vm.onIntent(TestIntent.Emit("error"))
        advanceUntilIdle() // no hay colector: simula la pantalla rotando

        vm.effects.test {
            assertEquals(TestEffect("error"), awaitItem())
            expectNoEvents()
        }
        vm.effects.test { expectNoEvents() } // un segundo colector no lo recibe otra vez
    }
}
```

- [ ] **Step 3: Ejecutar y ver que falla**

Run: `./gradlew :core:core-mvi:testDebugUnitTest`
Expected: FAIL de compilación, `Unresolved reference 'MviViewModel'`.

- [ ] **Step 4: Implementar**

`core/core-mvi/src/main/kotlin/com/violinstudio/core/mvi/MviContract.kt`:

```kotlin
package com.violinstudio.core.mvi

/** Estado inmutable de una pantalla. Implementar con data classes. */
interface UiState

/** Acción del usuario o del sistema que la pantalla envía al ViewModel. */
interface UiIntent

/** Evento de consumo único (navegar, snackbar). */
interface UiEffect
```

`core/core-mvi/src/main/kotlin/com/violinstudio/core/mvi/MviViewModel.kt`:

```kotlin
package com.violinstudio.core.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Base MVI. Garantías:
 * - Los intents se procesan de uno en uno y en orden de llegada. Un trabajo largo que no deba
 *   bloquear intents posteriores tiene que lanzar su propia coroutine desde [handleIntent].
 * - [setState] es atómico.
 * - Cada efecto se entrega exactamente una vez; si no hay colector se guarda hasta que lo haya.
 */
abstract class MviViewModel<S : UiState, I : UiIntent, E : UiEffect>(initial: S) : ViewModel() {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effects = Channel<E>(Channel.BUFFERED)
    val effects: Flow<E> = _effects.receiveAsFlow()

    private val intents = Channel<I>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            for (intent in intents) handleIntent(intent)
        }
    }

    fun onIntent(intent: I) {
        intents.trySend(intent)
    }

    protected abstract suspend fun handleIntent(intent: I)

    protected fun setState(reduce: S.() -> S) {
        _state.update(reduce)
    }

    protected fun sendEffect(effect: E) {
        _effects.trySend(effect)
    }
}
```

- [ ] **Step 5: Ejecutar y ver que pasa**

Run: `./gradlew :core:core-mvi:check`
Expected: BUILD SUCCESSFUL, 4 tests en verde y `coverageVerification` en verde.

- [ ] **Step 6: Comprobar que la cobertura encuentra las clases en un módulo Android**

Es la ruta de riesgo (AGP 9 con Kotlin integrado). Crear temporalmente `core/core-mvi/src/main/kotlin/com/violinstudio/core/mvi/Untested.kt` con el mismo contenido que en Task 1 Step 9, cambiando el paquete a `com.violinstudio.core.mvi`.

Run: `./gradlew :core:core-mvi:coverageVerification`
Expected: FAIL con `lines covered ratio is 0.xx, but expected minimum is 0.80`. Si en cambio falla con `no hay clases en ...`, buscar dónde deja AGP las clases (`find core/core-mvi/build -name "MviViewModel.class"`) y añadir esa raíz a `classRoots` en `Coverage.kt`.

Borrar `Untested.kt`; `./gradlew :core:core-mvi:coverageVerification` → BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(core-mvi): add MVI contract and MviViewModel base

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `core-testing` — `MainDispatcherExtension` y DSL `testMvi`

**Files:**
- Create: `core/core-testing/build.gradle.kts`
- Create: `core/core-testing/src/main/kotlin/com/violinstudio/core/testing/MainDispatcherExtension.kt`
- Create: `core/core-testing/src/main/kotlin/com/violinstudio/core/testing/TestMvi.kt`
- Test: `core/core-testing/src/test/kotlin/com/violinstudio/core/testing/TestMviTest.kt`
- Modify: `settings.gradle.kts` (añadir `include(":core:core-testing")`)

**Interfaces:**
- Consumes: `MviViewModel`, `UiState`, `UiIntent`, `UiEffect` (Task 2).
- Produces:
  - `class MainDispatcherExtension(val dispatcher: TestDispatcher = StandardTestDispatcher()) : BeforeEachCallback, AfterEachCallback`
  - `suspend fun <S : UiState, I : UiIntent, E : UiEffect> MviViewModel<S, I, E>.testMvi(block: suspend MviScenario<S, I, E>.() -> Unit)` — consume el estado inicial antes de ejecutar `block`; al final falla si quedan efectos sin comprobar.
  - `class MviScenario<S, I, E>` con `fun intent(intent: I)`, `suspend fun assertState(predicate: (S) -> Boolean)`, `suspend fun assertEffect(expected: E)`, `fun assertNoEffects()`.

- [ ] **Step 1: Crear el módulo**

`core/core-testing/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.violin.android.library)
}

android { namespace = "com.violinstudio.core.testing" }

dependencies {
    api(project(":core:core-mvi"))
    api(libs.kotlinx.coroutines.test)
    api(libs.junit.jupiter.api)
    api(libs.turbine)
}
```

Añadir `include(":core:core-testing")` a `settings.gradle.kts`.

- [ ] **Step 2: Escribir los tests que fallan**

`core/core-testing/src/test/kotlin/com/violinstudio/core/testing/TestMviTest.kt`:

```kotlin
package com.violinstudio.core.testing

import com.violinstudio.core.mvi.MviViewModel
import com.violinstudio.core.mvi.UiEffect
import com.violinstudio.core.mvi.UiIntent
import com.violinstudio.core.mvi.UiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
class TestMviTest {
    data class CounterState(val count: Int = 0) : UiState

    sealed interface CounterIntent : UiIntent {
        data object SlowIncrement : CounterIntent
        data object Notify : CounterIntent
    }

    data class CounterEffect(val message: String) : UiEffect

    class CounterViewModel : MviViewModel<CounterState, CounterIntent, CounterEffect>(CounterState()) {
        override suspend fun handleIntent(intent: CounterIntent) = when (intent) {
            CounterIntent.SlowIncrement -> {
                delay(100)
                setState { copy(count = count + 1) }
            }
            CounterIntent.Notify -> sendEffect(CounterEffect("hola"))
        }
    }

    @Test
    fun `assertState recibe los estados posteriores al inicial`() = runTest {
        CounterViewModel().testMvi {
            intent(CounterIntent.SlowIncrement)
            assertState { it.count == 1 }
            assertNoEffects()
        }
    }

    @Test
    fun `assertEffect recibe el efecto emitido`() = runTest {
        CounterViewModel().testMvi {
            intent(CounterIntent.Notify)
            assertEffect(CounterEffect("hola"))
        }
    }

    @Test
    fun `assertState falla con AssertionError si el predicado no se cumple`() = runTest {
        val error = runCatching {
            CounterViewModel().testMvi {
                intent(CounterIntent.SlowIncrement)
                assertState { it.count == 99 }
            }
        }.exceptionOrNull()
        assertTrue(error is AssertionError, "se esperaba AssertionError y llegó $error")
    }

    @Test
    fun `testMvi falla si quedan efectos sin comprobar`() = runTest {
        val error = runCatching {
            CounterViewModel().testMvi {
                intent(CounterIntent.Notify)
                intent(CounterIntent.SlowIncrement)
                assertState { it.count == 1 }
            }
        }.exceptionOrNull()
        assertTrue(error is AssertionError, "se esperaba AssertionError y llegó $error")
    }
}
```

- [ ] **Step 3: Ejecutar y ver que falla**

Run: `./gradlew :core:core-testing:testDebugUnitTest`
Expected: FAIL de compilación, `Unresolved reference 'MainDispatcherExtension'` y `'testMvi'`.

- [ ] **Step 4: Implementar**

`core/core-testing/src/main/kotlin/com/violinstudio/core/testing/MainDispatcherExtension.kt`:

```kotlin
package com.violinstudio.core.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/** Sustituye Dispatchers.Main por un TestDispatcher; runTest reutiliza su scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherExtension(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : BeforeEachCallback, AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) = Dispatchers.setMain(dispatcher)

    override fun afterEach(context: ExtensionContext) = Dispatchers.resetMain()
}
```

`core/core-testing/src/main/kotlin/com/violinstudio/core/testing/TestMvi.kt`:

```kotlin
package com.violinstudio.core.testing

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.turbineScope
import com.violinstudio.core.mvi.MviViewModel
import com.violinstudio.core.mvi.UiEffect
import com.violinstudio.core.mvi.UiIntent
import com.violinstudio.core.mvi.UiState

class MviScenario<S : UiState, I : UiIntent, E : UiEffect> internal constructor(
    private val viewModel: MviViewModel<S, I, E>,
    private val states: ReceiveTurbine<S>,
    private val effects: ReceiveTurbine<E>,
) {
    fun intent(intent: I) = viewModel.onIntent(intent)

    /** Espera el siguiente estado (StateFlow descarta estados intermedios iguales o no observados). */
    suspend fun assertState(predicate: (S) -> Boolean) {
        val state = states.awaitItem()
        if (!predicate(state)) throw AssertionError("Estado inesperado: $state")
    }

    suspend fun assertEffect(expected: E) {
        val effect = effects.awaitItem()
        if (effect != expected) throw AssertionError("Efecto esperado $expected, llegó $effect")
    }

    fun assertNoEffects() = effects.expectNoEvents()
}

/**
 * Colecta estado y efectos del ViewModel mientras se ejecuta [block].
 * El estado inicial se consume antes de [block]. Al terminar falla si quedan efectos sin comprobar.
 */
suspend fun <S : UiState, I : UiIntent, E : UiEffect> MviViewModel<S, I, E>.testMvi(
    block: suspend MviScenario<S, I, E>.() -> Unit,
) = turbineScope {
    val stateTurbine = this@testMvi.state.testIn(this, name = "state")
    val effectTurbine = this@testMvi.effects.testIn(this, name = "effects")
    stateTurbine.awaitItem()
    MviScenario(this@testMvi, stateTurbine, effectTurbine).block()
    stateTurbine.cancelAndIgnoreRemainingEvents()
    effectTurbine.ensureAllEventsConsumed()
    effectTurbine.cancel()
}
```

- [ ] **Step 5: Ejecutar y ver que pasa**

Run: `./gradlew :core:core-testing:check`
Expected: BUILD SUCCESSFUL, 4 tests en verde.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "test(core-testing): add MainDispatcherExtension and testMvi DSL

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `core-firebase` — configuración de emuladores y DI de Firebase

**Files:**
- Create: `core/core-firebase/build.gradle.kts`
- Create: `core/core-firebase/src/main/kotlin/com/violinstudio/core/firebase/EmulatorConfig.kt`
- Create: `core/core-firebase/src/main/kotlin/com/violinstudio/core/firebase/di/FirebaseModule.kt`
- Test: `core/core-firebase/src/test/kotlin/com/violinstudio/core/firebase/EmulatorConfigTest.kt`
- Modify: `settings.gradle.kts` (añadir `include(":core:core-firebase")`)

**Interfaces:**
- Produces:
  - `data class EmulatorEndpoint(val host: String, val port: Int)`
  - `data class EmulatorConfig(val enabled: Boolean, val host: String)` con `fun functions(): EmulatorEndpoint?` y `companion object { const val FUNCTIONS_PORT = 5001 }`; lanza `IllegalArgumentException` si `enabled && host.isBlank()`.
  - `const val FUNCTIONS_REGION = "europe-west1"` (en `FirebaseModule.kt`, paquete `com.violinstudio.core.firebase.di`).
  - Binding Hilt `FirebaseFunctions` (singleton). **Requiere** que la app aporte un binding de `EmulatorConfig` (Task 7).

- [ ] **Step 1: Crear el módulo**

`core/core-firebase/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.violin.android.library)
    alias(libs.plugins.violin.android.hilt)
}

android { namespace = "com.violinstudio.core.firebase" }

dependencies {
    api(platform(libs.firebase.bom))
    api(libs.firebase.functions)
}
```

Añadir `include(":core:core-firebase")` a `settings.gradle.kts`.

- [ ] **Step 2: Escribir el test que falla**

`core/core-firebase/src/test/kotlin/com/violinstudio/core/firebase/EmulatorConfigTest.kt`:

```kotlin
package com.violinstudio.core.firebase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EmulatorConfigTest {
    @Test
    fun `activado devuelve el endpoint de Functions en el puerto 5001`() {
        assertEquals(
            EmulatorEndpoint("10.0.2.2", 5001),
            EmulatorConfig(enabled = true, host = "10.0.2.2").functions(),
        )
    }

    @Test
    fun `desactivado no devuelve endpoint aunque haya host`() {
        assertNull(EmulatorConfig(enabled = false, host = "10.0.2.2").functions())
        assertNull(EmulatorConfig(enabled = false, host = "").functions())
    }

    @Test
    fun `activado con host vacío es un error de configuración`() {
        assertThrows<IllegalArgumentException> { EmulatorConfig(enabled = true, host = " ") }
    }
}
```

- [ ] **Step 3: Ejecutar y ver que falla**

Run: `./gradlew :core:core-firebase:testDebugUnitTest`
Expected: FAIL de compilación, `Unresolved reference 'EmulatorConfig'`.

- [ ] **Step 4: Implementar**

`core/core-firebase/src/main/kotlin/com/violinstudio/core/firebase/EmulatorConfig.kt`:

```kotlin
package com.violinstudio.core.firebase

data class EmulatorEndpoint(val host: String, val port: Int)

/**
 * Si [enabled], los SDKs de Firebase apuntan a los emuladores locales en [host]
 * (10.0.2.2 desde el emulador Android; la IP del PC desde un móvil físico).
 */
data class EmulatorConfig(val enabled: Boolean, val host: String) {
    init {
        require(!enabled || host.isNotBlank()) { "EmulatorConfig: host vacío con los emuladores activados" }
    }

    fun functions(): EmulatorEndpoint? = if (enabled) EmulatorEndpoint(host, FUNCTIONS_PORT) else null

    companion object {
        const val FUNCTIONS_PORT = 5001
    }
}
```

`core/core-firebase/src/main/kotlin/com/violinstudio/core/firebase/di/FirebaseModule.kt`:

```kotlin
package com.violinstudio.core.firebase.di

import com.google.firebase.Firebase
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.functions
import com.violinstudio.core.firebase.EmulatorConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

const val FUNCTIONS_REGION = "europe-west1"

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {
    @Provides
    @Singleton
    fun provideFunctions(config: EmulatorConfig): FirebaseFunctions =
        Firebase.functions(FUNCTIONS_REGION).apply {
            config.functions()?.let { useEmulator(it.host, it.port) }
        }
}
```

- [ ] **Step 5: Ejecutar y ver que pasa**

Run: `./gradlew :core:core-firebase:check`
Expected: BUILD SUCCESSFUL, 3 tests en verde.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(core-firebase): add emulator config and Functions DI

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `core-data` — `HealthRepository`

**Files:**
- Create: `core/core-data/build.gradle.kts`
- Create: `core/core-data/src/main/kotlin/com/violinstudio/core/data/health/HealthRemoteSource.kt`
- Create: `core/core-data/src/main/kotlin/com/violinstudio/core/data/health/HealthRepository.kt`
- Create: `core/core-data/src/main/kotlin/com/violinstudio/core/data/remote/HealthResponseParser.kt`
- Create: `core/core-data/src/main/kotlin/com/violinstudio/core/data/remote/firebase/FirebaseHealthRemoteSource.kt`
- Create: `core/core-data/src/main/kotlin/com/violinstudio/core/data/di/DataModule.kt`
- Test: `core/core-data/src/test/kotlin/com/violinstudio/core/data/remote/HealthResponseParserTest.kt`
- Test: `core/core-data/src/test/kotlin/com/violinstudio/core/data/health/HealthRepositoryTest.kt`
- Modify: `settings.gradle.kts` (añadir `include(":core:core-data")`), `build.gradle.kts` (añadir `":core:core-data:coverageVerification"` a `coverage`)

**Interfaces:**
- Consumes: `HealthInfo` (Task 1), `FirebaseFunctions` binding (Task 4).
- Produces:
  - `fun interface HealthRemoteSource { suspend fun fetchHealth(): HealthInfo }`
  - `class HealthRepository @Inject constructor(remote: HealthRemoteSource)` con `suspend fun check(): Result<HealthInfo>`; relanza `CancellationException`.
  - `class ServerUnavailableException(val status: String) : IllegalStateException`
  - `object HealthResponseParser { fun parse(data: Any?): HealthInfo }` y `class MalformedResponseException(message: String) : IllegalStateException(message)`
  - Binding Hilt `HealthRemoteSource` → `FirebaseHealthRemoteSource`.

- [ ] **Step 1: Crear el módulo**

`core/core-data/build.gradle.kts`:

```kotlin
import com.violinstudio.buildlogic.configureCoverage

plugins {
    alias(libs.plugins.violin.android.library)
    alias(libs.plugins.violin.android.hilt)
}

android { namespace = "com.violinstudio.core.data" }

configureCoverage(classPaths = listOf("com/violinstudio/core/data/**"))

dependencies {
    api(project(":core:core-model"))
    implementation(project(":core:core-firebase"))
    implementation(libs.kotlinx.coroutines.play.services)
}
```

Añadir el `include` y la entrada de `coverage`.

- [ ] **Step 2: Escribir los tests que fallan**

`core/core-data/src/test/kotlin/com/violinstudio/core/data/remote/HealthResponseParserTest.kt`:

```kotlin
package com.violinstudio.core.data.remote

import com.violinstudio.core.model.HealthInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HealthResponseParserTest {
    @Test
    fun `parsea un objeto con status y version`() {
        assertEquals(
            HealthInfo("ok", "0.1.0"),
            HealthResponseParser.parse(mapOf("status" to "ok", "version" to "0.1.0")),
        )
    }

    @Test
    fun `ignora campos extra`() {
        assertEquals(
            HealthInfo("ok", "0.1.0"),
            HealthResponseParser.parse(mapOf("status" to "ok", "version" to "0.1.0", "region" to "eu")),
        )
    }

    @Test
    fun `falla con MalformedResponseException si no es un objeto`() {
        assertThrows<MalformedResponseException> { HealthResponseParser.parse(null) }
        assertThrows<MalformedResponseException> { HealthResponseParser.parse("ok") }
    }

    @Test
    fun `falla si falta un campo o no es texto`() {
        assertThrows<MalformedResponseException> { HealthResponseParser.parse(mapOf("status" to "ok")) }
        assertThrows<MalformedResponseException> { HealthResponseParser.parse(mapOf("version" to "0.1.0")) }
        assertThrows<MalformedResponseException> {
            HealthResponseParser.parse(mapOf("status" to "ok", "version" to 1))
        }
    }
}
```

`core/core-data/src/test/kotlin/com/violinstudio/core/data/health/HealthRepositoryTest.kt`:

```kotlin
package com.violinstudio.core.data.health

import com.violinstudio.core.model.HealthInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class HealthRepositoryTest {
    @Test
    fun `devuelve success cuando el servidor responde ok`() = runTest {
        val repo = HealthRepository { HealthInfo("ok", "0.1.0") }
        assertEquals(Result.success(HealthInfo("ok", "0.1.0")), repo.check())
    }

    @Test
    fun `devuelve ServerUnavailableException cuando status no es ok`() = runTest {
        val error = HealthRepository { HealthInfo("degraded", "0.1.0") }.check().exceptionOrNull()
        assertTrue(error is ServerUnavailableException)
        assertEquals("degraded", (error as ServerUnavailableException).status)
    }

    @Test
    fun `convierte los errores de red en failure`() = runTest {
        val error = HealthRepository { throw IOException("sin red") }.check().exceptionOrNull()
        assertTrue(error is IOException)
    }

    @Test
    fun `relanza la cancelación en vez de convertirla en failure`() {
        val repo = HealthRepository { throw CancellationException("pantalla cerrada") }
        assertThrows<CancellationException> { runBlocking { repo.check() } }
    }
}
```

- [ ] **Step 3: Ejecutar y ver que falla**

Run: `./gradlew :core:core-data:testDebugUnitTest`
Expected: FAIL de compilación, `Unresolved reference 'HealthRepository'` y `'HealthResponseParser'`.

- [ ] **Step 4: Implementar**

`core/core-data/src/main/kotlin/com/violinstudio/core/data/health/HealthRemoteSource.kt`:

```kotlin
package com.violinstudio.core.data.health

import com.violinstudio.core.model.HealthInfo

fun interface HealthRemoteSource {
    suspend fun fetchHealth(): HealthInfo
}
```

`core/core-data/src/main/kotlin/com/violinstudio/core/data/health/HealthRepository.kt`:

```kotlin
package com.violinstudio.core.data.health

import com.violinstudio.core.model.HealthInfo
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class ServerUnavailableException(val status: String) :
    IllegalStateException("El servidor respondió con estado '$status'")

class HealthRepository @Inject constructor(private val remote: HealthRemoteSource) {
    suspend fun check(): Result<HealthInfo> {
        val info = try {
            remote.fetchHealth()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return if (info.isOk) Result.success(info) else Result.failure(ServerUnavailableException(info.status))
    }
}
```

`core/core-data/src/main/kotlin/com/violinstudio/core/data/remote/HealthResponseParser.kt`:

```kotlin
package com.violinstudio.core.data.remote

import com.violinstudio.core.model.HealthInfo

class MalformedResponseException(message: String) : IllegalStateException(message)

object HealthResponseParser {
    fun parse(data: Any?): HealthInfo {
        val map = data as? Map<*, *> ?: throw MalformedResponseException("health: se esperaba un objeto y llegó $data")
        return HealthInfo(status = map.string("status"), version = map.string("version"))
    }

    private fun Map<*, *>.string(key: String): String =
        this[key] as? String ?: throw MalformedResponseException("health: falta '$key' o no es texto")
}
```

`core/core-data/src/main/kotlin/com/violinstudio/core/data/remote/firebase/FirebaseHealthRemoteSource.kt`:

```kotlin
package com.violinstudio.core.data.remote.firebase

import com.google.firebase.functions.FirebaseFunctions
import com.violinstudio.core.data.health.HealthRemoteSource
import com.violinstudio.core.data.remote.HealthResponseParser
import com.violinstudio.core.model.HealthInfo
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/** Adaptador fino sobre el SDK; se prueba en el E2E (Task 11). */
class FirebaseHealthRemoteSource @Inject constructor(
    private val functions: FirebaseFunctions,
) : HealthRemoteSource {
    override suspend fun fetchHealth(): HealthInfo =
        HealthResponseParser.parse(functions.getHttpsCallable("health").call().await().getData())
}
```

`core/core-data/src/main/kotlin/com/violinstudio/core/data/di/DataModule.kt`:

```kotlin
package com.violinstudio.core.data.di

import com.violinstudio.core.data.health.HealthRemoteSource
import com.violinstudio.core.data.remote.firebase.FirebaseHealthRemoteSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun bindHealthRemoteSource(impl: FirebaseHealthRemoteSource): HealthRemoteSource
}
```

- [ ] **Step 5: Ejecutar y ver que pasa**

Run: `./gradlew :core:core-data:check`
Expected: BUILD SUCCESSFUL, 8 tests en verde, cobertura ≥ 80 %.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(core-data): add HealthRepository with response parser

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: `core-ui` — tema y componentes comunes

**Files:**
- Create: `core/core-ui/build.gradle.kts`
- Create: `core/core-ui/src/main/kotlin/com/violinstudio/core/ui/theme/{Color.kt,Theme.kt}`
- Create: `core/core-ui/src/main/kotlin/com/violinstudio/core/ui/components/{LoadingIndicator.kt,ErrorView.kt}`
- Create: `core/core-ui/src/main/kotlin/com/violinstudio/core/ui/ObserveAsEvents.kt`
- Test: `core/core-ui/src/test/kotlin/com/violinstudio/core/ui/components/ErrorViewTest.kt`
- Modify: `settings.gradle.kts` (añadir `include(":core:core-ui")`)

**Interfaces:**
- Produces:
  - `@Composable fun ViolinStudioTheme(content: @Composable () -> Unit)` (paleta oscura de Violin-master)
  - `@Composable fun LoadingIndicator(modifier: Modifier = Modifier)` — tag `"loading"`
  - `@Composable fun ErrorView(message: String, modifier: Modifier = Modifier)` — tag `"error"`
  - `@Composable fun <E> ObserveAsEvents(events: Flow<E>, onEvent: suspend (E) -> Unit)` — colecta en `STARTED`

- [ ] **Step 1: Crear el módulo**

`core/core-ui/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.violin.android.library)
    alias(libs.plugins.violin.android.compose)
}

android { namespace = "com.violinstudio.core.ui" }

dependencies {
    api(libs.androidx.lifecycle.runtime.compose)
}
```

Añadir `include(":core:core-ui")` a `settings.gradle.kts`.

- [ ] **Step 2: Escribir el test que falla**

`core/core-ui/src/test/kotlin/com/violinstudio/core/ui/components/ErrorViewTest.kt`:

```kotlin
package com.violinstudio.core.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.core.ui.theme.ViolinStudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ErrorViewTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun muestraElMensaje() {
        compose.setContent { ViolinStudioTheme { ErrorView(message = "Sin conexión") } }
        compose.onNodeWithTag("error").assertIsDisplayed()
        compose.onNodeWithText("Sin conexión").assertIsDisplayed()
    }
}
```

- [ ] **Step 3: Ejecutar y ver que falla**

Run: `./gradlew :core:core-ui:testDebugUnitTest`
Expected: FAIL de compilación, `Unresolved reference 'ErrorView'`.

- [ ] **Step 4: Implementar**

`core/core-ui/src/main/kotlin/com/violinstudio/core/ui/theme/Color.kt`:

```kotlin
package com.violinstudio.core.ui.theme

import androidx.compose.ui.graphics.Color

// Paleta "Sophisticated Dark" de Violin-master.
internal val DarkBg = Color(0xFF1C1B1F)
internal val DarkSurface = Color(0xFF2B2930)
internal val DarkSurfaceVariant = Color(0xFF49454F)
internal val PrimaryPurple = Color(0xFFD0BCFF)
internal val OnPrimaryPurple = Color(0xFF381E72)
internal val PrimaryContainer = Color(0xFFEADDFF)
internal val OnPrimaryContainer = Color(0xFF21005D)
internal val SecondaryLav = Color(0xFFCCC2DC)
internal val TextLight = Color(0xFFE6E1E5)
internal val TextMuted = Color(0xFFCAC4D0)
internal val ErrorRed = Color(0xFFF2B8B5)
```

`core/core-ui/src/main/kotlin/com/violinstudio/core/ui/theme/Theme.kt`:

```kotlin
package com.violinstudio.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ViolinDarkColors = darkColorScheme(
    primary = PrimaryPurple,
    onPrimary = OnPrimaryPurple,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = SecondaryLav,
    background = DarkBg,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = TextLight,
    onSurface = TextLight,
    onSurfaceVariant = TextMuted,
    outline = DarkSurfaceVariant,
    error = ErrorRed,
)

/** Tema único oscuro (decisión heredada de Violin-master); sin color dinámico. */
@Composable
fun ViolinStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ViolinDarkColors, content = content)
}
```

`core/core-ui/src/main/kotlin/com/violinstudio/core/ui/components/LoadingIndicator.kt`:

```kotlin
package com.violinstudio.core.ui.components

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier = modifier.testTag("loading"))
}
```

`core/core-ui/src/main/kotlin/com/violinstudio/core/ui/components/ErrorView.kt`:

```kotlin
package com.violinstudio.core.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign

@Composable
fun ErrorView(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = modifier.testTag("error"),
    )
}
```

`core/core-ui/src/main/kotlin/com/violinstudio/core/ui/ObserveAsEvents.kt`:

```kotlin
package com.violinstudio.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow

/** Colecta efectos MVI solo con la pantalla visible; los emitidos mientras tanto esperan en el buffer. */
@Composable
fun <E> ObserveAsEvents(events: Flow<E>, onEvent: suspend (E) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(events, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            events.collect { onEvent(it) }
        }
    }
}
```

- [ ] **Step 5: Ejecutar y ver que pasa**

Run: `./gradlew :core:core-ui:check`
Expected: BUILD SUCCESSFUL, 1 test en verde.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(core-ui): add theme, loading/error components and ObserveAsEvents

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Módulo `app` y lógica MVI de Home

**Antes de empezar:** prerrequisito **P1** (los dos `google-services.json`). Sin ellos el plugin `google-services` no compila. Parar y pedirlos al usuario.

**Files:**
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`
- Create: `app/src/main/kotlin/com/violinstudio/home/HomeContract.kt`
- Create: `app/src/main/kotlin/com/violinstudio/home/HomeReducer.kt`
- Create: `app/src/main/kotlin/com/violinstudio/home/HomeViewModel.kt`
- Test: `app/src/test/kotlin/com/violinstudio/home/HomeReducerTest.kt`
- Test: `app/src/test/kotlin/com/violinstudio/home/HomeViewModelTest.kt`
- Create: `app/src/main/AndroidManifest.xml` (mínimo, sin actividad todavía)
- Modify: `settings.gradle.kts` (añadir `include(":app")`), `build.gradle.kts` (añadir `":app:coverageVerification"` a `coverage`)

**Interfaces:**
- Consumes: `MviViewModel` (Task 2), `testMvi`, `MainDispatcherExtension` (Task 3), `HealthRepository`, `HealthRemoteSource` (Task 5).
- Produces:
  - `sealed interface HealthStatus { Idle; Loading; data class Ok(val version: String); data class Error(val message: String?) }`
  - `data class HomeState(val status: HealthStatus = HealthStatus.Idle) : UiState`
  - `sealed interface HomeIntent : UiIntent { data object CheckHealth }`
  - `sealed interface HomeEffect : UiEffect { data class ShowError(val message: String?) }`
  - `sealed interface HomeMutation { Loading; data class Loaded(val version: String); data class Failed(val message: String?) }`
  - `object HomeReducer { fun reduce(state: HomeState, mutation: HomeMutation): HomeState }`
  - `@HiltViewModel class HomeViewModel @Inject constructor(health: HealthRepository)`
  - BuildConfig: `USE_EMULATORS: Boolean`, `EMULATOR_HOST: String`, `CRASHLYTICS_ENABLED: Boolean`.

- [ ] **Step 1: Crear el módulo `app`**

`app/build.gradle.kts`:

```kotlin
import com.violinstudio.buildlogic.configureCoverage

plugins {
    alias(libs.plugins.violin.android.application)
    alias(libs.plugins.violin.android.compose)
    alias(libs.plugins.violin.android.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

// IP del PC con los emuladores de Firebase. Para móvil físico: -Pviolin.emulatorHost=192.168.x.x
val emulatorHost = providers.gradleProperty("violin.emulatorHost").getOrElse("10.0.2.2")
val keystoreFile = file(
    providers.environmentVariable("KEYSTORE_PATH").getOrElse(rootProject.file("keystore/upload.jks").path),
)

android {
    namespace = "com.violinstudio"

    defaultConfig {
        applicationId = "com.violinstudio"
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").map { it.toInt() }.getOrElse(1)
        versionName = "0.1.0"
    }

    productFlavors {
        getByName("dev") {
            buildConfigField("boolean", "USE_EMULATORS", "true")
            buildConfigField("String", "EMULATOR_HOST", "\"$emulatorHost\"")
            buildConfigField("boolean", "CRASHLYTICS_ENABLED", "false")
        }
        getByName("prod") {
            buildConfigField("boolean", "USE_EMULATORS", "false")
            buildConfigField("String", "EMULATOR_HOST", "\"\"")
            buildConfigField("boolean", "CRASHLYTICS_ENABLED", "true")
        }
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = providers.environmentVariable("STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("KEY_ALIAS").getOrElse("upload")
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
}

configureCoverage(classPaths = listOf("com/violinstudio/**/*Reducer*.class"), variant = "devDebug")

dependencies {
    implementation(project(":core:core-mvi"))
    implementation(project(":core:core-ui"))
    implementation(project(":core:core-model"))
    implementation(project(":core:core-data"))
    implementation(project(":core:core-firebase"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.perf)
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)

    testImplementation(project(":core:core-testing"))

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
```

`app/proguard-rules.pro`:

```proguard
# Reglas propias de la app. Hilt, Firebase y kotlinx.serialization traen las suyas.
```

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application android:label="@string/app_name" />
</manifest>
```

`app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">Violin Studio</string>
</resources>
```

Añadir `include(":app")` y la entrada de `coverage`.

- [ ] **Step 2: Escribir los tests que fallan**

`app/src/test/kotlin/com/violinstudio/home/HomeReducerTest.kt`:

```kotlin
package com.violinstudio.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HomeReducerTest {
    @Test
    fun `Loading pasa a estado Loading desde cualquier estado`() {
        assertEquals(HomeState(HealthStatus.Loading), HomeReducer.reduce(HomeState(), HomeMutation.Loading))
        assertEquals(
            HomeState(HealthStatus.Loading),
            HomeReducer.reduce(HomeState(HealthStatus.Error("x")), HomeMutation.Loading),
        )
    }

    @Test
    fun `Loaded guarda la versión`() {
        assertEquals(
            HomeState(HealthStatus.Ok("0.1.0")),
            HomeReducer.reduce(HomeState(HealthStatus.Loading), HomeMutation.Loaded("0.1.0")),
        )
    }

    @Test
    fun `Failed guarda el mensaje aunque sea null`() {
        assertEquals(
            HomeState(HealthStatus.Error("sin red")),
            HomeReducer.reduce(HomeState(HealthStatus.Loading), HomeMutation.Failed("sin red")),
        )
        assertEquals(
            HomeState(HealthStatus.Error(null)),
            HomeReducer.reduce(HomeState(HealthStatus.Loading), HomeMutation.Failed(null)),
        )
    }
}
```

`app/src/test/kotlin/com/violinstudio/home/HomeViewModelTest.kt`:

```kotlin
package com.violinstudio.home

import com.violinstudio.core.data.health.HealthRepository
import com.violinstudio.core.model.HealthInfo
import com.violinstudio.core.testing.MainDispatcherExtension
import com.violinstudio.core.testing.testMvi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.IOException

@ExtendWith(MainDispatcherExtension::class)
class HomeViewModelTest {
    // La latencia hace observable el estado Loading (StateFlow descarta estados intermedios).
    private fun viewModel(fetch: suspend () -> HealthInfo) = HomeViewModel(
        HealthRepository {
            delay(100)
            fetch()
        },
    )

    @Test
    fun `CheckHealth pasa por Loading y termina en Ok`() = runTest {
        viewModel { HealthInfo("ok", "0.1.0") }.testMvi {
            intent(HomeIntent.CheckHealth)
            assertState { it.status == HealthStatus.Loading }
            assertState { it.status == HealthStatus.Ok("0.1.0") }
            assertNoEffects()
        }
    }

    @Test
    fun `un error termina en Error y emite ShowError con el mensaje`() = runTest {
        viewModel { throw IOException("sin red") }.testMvi {
            intent(HomeIntent.CheckHealth)
            assertState { it.status == HealthStatus.Loading }
            assertState { it.status == HealthStatus.Error("sin red") }
            assertEffect(HomeEffect.ShowError("sin red"))
        }
    }

    @Test
    fun `un error sin mensaje llega como null para que la UI ponga el texto por defecto`() = runTest {
        viewModel { throw IllegalStateException() }.testMvi {
            intent(HomeIntent.CheckHealth)
            assertState { it.status == HealthStatus.Loading }
            assertState { it.status == HealthStatus.Error(null) }
            assertEffect(HomeEffect.ShowError(null))
        }
    }
}
```

- [ ] **Step 3: Ejecutar y ver que falla**

Run: `./gradlew :app:testDevDebugUnitTest`
Expected: FAIL de compilación, `Unresolved reference 'HomeReducer'`, `'HomeViewModel'`.

- [ ] **Step 4: Implementar**

`app/src/main/kotlin/com/violinstudio/home/HomeContract.kt`:

```kotlin
package com.violinstudio.home

import com.violinstudio.core.mvi.UiEffect
import com.violinstudio.core.mvi.UiIntent
import com.violinstudio.core.mvi.UiState

sealed interface HealthStatus {
    data object Idle : HealthStatus
    data object Loading : HealthStatus
    data class Ok(val version: String) : HealthStatus

    /** [message] null → la UI muestra "Error desconocido". */
    data class Error(val message: String?) : HealthStatus
}

data class HomeState(val status: HealthStatus = HealthStatus.Idle) : UiState

sealed interface HomeIntent : UiIntent {
    data object CheckHealth : HomeIntent
}

sealed interface HomeEffect : UiEffect {
    data class ShowError(val message: String?) : HomeEffect
}

sealed interface HomeMutation {
    data object Loading : HomeMutation
    data class Loaded(val version: String) : HomeMutation
    data class Failed(val message: String?) : HomeMutation
}
```

`app/src/main/kotlin/com/violinstudio/home/HomeReducer.kt`:

```kotlin
package com.violinstudio.home

object HomeReducer {
    fun reduce(state: HomeState, mutation: HomeMutation): HomeState = when (mutation) {
        HomeMutation.Loading -> state.copy(status = HealthStatus.Loading)
        is HomeMutation.Loaded -> state.copy(status = HealthStatus.Ok(mutation.version))
        is HomeMutation.Failed -> state.copy(status = HealthStatus.Error(mutation.message))
    }
}
```

`app/src/main/kotlin/com/violinstudio/home/HomeViewModel.kt`:

```kotlin
package com.violinstudio.home

import com.violinstudio.core.data.health.HealthRepository
import com.violinstudio.core.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val health: HealthRepository,
) : MviViewModel<HomeState, HomeIntent, HomeEffect>(HomeState()) {

    override suspend fun handleIntent(intent: HomeIntent) = when (intent) {
        HomeIntent.CheckHealth -> checkHealth()
    }

    private suspend fun checkHealth() {
        reduce(HomeMutation.Loading)
        health.check().fold(
            onSuccess = { reduce(HomeMutation.Loaded(it.version)) },
            onFailure = { error ->
                reduce(HomeMutation.Failed(error.message))
                sendEffect(HomeEffect.ShowError(error.message))
            },
        )
    }

    private fun reduce(mutation: HomeMutation) = setState { HomeReducer.reduce(this, mutation) }
}
```

- [ ] **Step 5: Ejecutar y ver que pasa**

Run: `./gradlew :app:testDevDebugUnitTest :app:coverageVerification`
Expected: BUILD SUCCESSFUL, 6 tests en verde, cobertura de `HomeReducer` ≥ 80 %.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(app): add app module with Home MVI contract, reducer and ViewModel

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Pantalla Home, shell de la app y capturas

**Files:**
- Create: `app/src/main/kotlin/com/violinstudio/home/HomeScreen.kt`
- Create: `app/src/main/kotlin/com/violinstudio/ViolinStudioApp.kt`, `app/src/main/kotlin/com/violinstudio/MainActivity.kt`
- Create: `app/src/main/kotlin/com/violinstudio/navigation/AppNavHost.kt`
- Create: `app/src/main/kotlin/com/violinstudio/di/AppConfigModule.kt`
- Create: `app/src/debug/kotlin/com/violinstudio/AppCheckInstaller.kt`, `app/src/release/kotlin/com/violinstudio/AppCheckInstaller.kt`
- Create: `app/src/dev/AndroidManifest.xml`, `app/src/dev/res/xml/network_security_config.xml`
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`
- Test: `app/src/test/kotlin/com/violinstudio/home/HomeScreenTest.kt`
- Test: `app/src/test/kotlin/com/violinstudio/home/HomeScreenScreenshotTest.kt`
- Create: `app/src/test/screenshots/*.png` (generadas en Step 6)

**Interfaces:**
- Consumes: `HomeState`, `HomeIntent`, `HomeEffect`, `HomeViewModel` (Task 7); `ViolinStudioTheme`, `LoadingIndicator`, `ErrorView`, `ObserveAsEvents` (Task 6); `EmulatorConfig` (Task 4).
- Produces:
  - `@Composable fun HomeScreen(state: HomeState, onIntent: (HomeIntent) -> Unit, snackbarHostState: SnackbarHostState = remember { SnackbarHostState() })` — stateless; botón con texto "Comprobar servidor"; tag `"health_ok"` en el texto de estado `Ok`.
  - `@Composable fun HomeRoute(viewModel: HomeViewModel = hiltViewModel())`
  - `MainActivity` (launcher) y `ViolinStudioApp` (`@HiltAndroidApp`).

- [ ] **Step 1: Añadir los textos**

`app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">Violin Studio</string>
    <string name="home_status_idle">Pulsa el botón para comprobar el servidor</string>
    <string name="home_status_ok">Servidor OK · v%1$s</string>
    <string name="home_check_health">Comprobar servidor</string>
    <string name="error_unknown">Error desconocido</string>
</resources>
```

- [ ] **Step 2: Escribir los tests de comportamiento que fallan**

`app/src/test/kotlin/com/violinstudio/home/HomeScreenTest.kt`:

```kotlin
package com.violinstudio.home

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.violinstudio.core.ui.theme.ViolinStudioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class) // evita arrancar Hilt/Firebase en tests de UI
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: HomeState, onIntent: (HomeIntent) -> Unit = {}) {
        compose.setContent { ViolinStudioTheme { HomeScreen(state = state, onIntent = onIntent) } }
    }

    @Test
    fun pulsarElBotonEnviaCheckHealth() {
        val sent = mutableListOf<HomeIntent>()
        show(HomeState()) { sent += it }
        compose.onNodeWithText("Comprobar servidor").assertIsEnabled().performClick()
        assertEquals(listOf(HomeIntent.CheckHealth), sent)
    }

    @Test
    fun elBotonEstaDeshabilitadoMientrasCarga() {
        compose.mainClock.autoAdvance = false // el indicador infinito nunca deja la UI inactiva
        show(HomeState(HealthStatus.Loading))
        compose.onNodeWithText("Comprobar servidor").assertIsNotEnabled()
    }

    @Test
    fun okMuestraLaVersion() {
        show(HomeState(HealthStatus.Ok("0.1.0")))
        compose.onNodeWithTag("health_ok").assertIsDisplayed()
        compose.onNodeWithText("Servidor OK · v0.1.0").assertIsDisplayed()
    }

    @Test
    fun errorSinMensajeMuestraErrorDesconocido() {
        show(HomeState(HealthStatus.Error(null)))
        compose.onNodeWithText("Error desconocido").assertIsDisplayed()
    }
}
```

- [ ] **Step 3: Ejecutar y ver que falla**

Run: `./gradlew :app:testDevDebugUnitTest --tests "com.violinstudio.home.HomeScreenTest"`
Expected: FAIL de compilación, `Unresolved reference 'HomeScreen'`.

- [ ] **Step 4: Implementar pantalla, shell y configuración**

`app/src/main/kotlin/com/violinstudio/home/HomeScreen.kt`:

```kotlin
package com.violinstudio.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.violinstudio.R
import com.violinstudio.core.ui.ObserveAsEvents
import com.violinstudio.core.ui.components.ErrorView
import com.violinstudio.core.ui.components.LoadingIndicator

@Composable
fun HomeRoute(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    ObserveAsEvents(viewModel.effects) { effect ->
        when (effect) {
            is HomeEffect.ShowError ->
                snackbarHostState.showSnackbar(effect.message ?: context.getString(R.string.error_unknown))
        }
    }
    HomeScreen(state = state, onIntent = viewModel::onIntent, snackbarHostState = snackbarHostState)
}

@Composable
fun HomeScreen(
    state: HomeState,
    onIntent: (HomeIntent) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            when (val status = state.status) {
                HealthStatus.Idle -> Text(stringResource(R.string.home_status_idle))
                HealthStatus.Loading -> LoadingIndicator()
                is HealthStatus.Ok -> Text(
                    text = stringResource(R.string.home_status_ok, status.version),
                    modifier = Modifier.testTag("health_ok"),
                )
                is HealthStatus.Error -> ErrorView(status.message ?: stringResource(R.string.error_unknown))
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onIntent(HomeIntent.CheckHealth) },
                enabled = state.status !is HealthStatus.Loading,
            ) {
                Text(stringResource(R.string.home_check_health))
            }
        }
    }
}
```

`app/src/main/kotlin/com/violinstudio/navigation/AppNavHost.kt`:

```kotlin
package com.violinstudio.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.violinstudio.home.HomeRoute
import kotlinx.serialization.Serializable

@Serializable
data object HomeDestination

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = HomeDestination) {
        composable<HomeDestination> { HomeRoute() }
    }
}
```

`app/src/main/kotlin/com/violinstudio/MainActivity.kt`:

```kotlin
package com.violinstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.violinstudio.core.ui.theme.ViolinStudioTheme
import com.violinstudio.navigation.AppNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ViolinStudioTheme { AppNavHost() } }
    }
}
```

`app/src/main/kotlin/com/violinstudio/ViolinStudioApp.kt`:

```kotlin
package com.violinstudio

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ViolinStudioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installAppCheck()
        Firebase.crashlytics.isCrashlyticsCollectionEnabled = BuildConfig.CRASHLYTICS_ENABLED
    }
}
```

`app/src/debug/kotlin/com/violinstudio/AppCheckInstaller.kt`:

```kotlin
package com.violinstudio

import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/** Builds debug: el token de depuración aparece en logcat (tag DebugAppCheckProvider). */
internal fun installAppCheck() {
    Firebase.appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
}
```

`app/src/release/kotlin/com/violinstudio/AppCheckInstaller.kt`:

```kotlin
package com.violinstudio

import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

internal fun installAppCheck() {
    Firebase.appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
}
```

`app/src/main/kotlin/com/violinstudio/di/AppConfigModule.kt`:

```kotlin
package com.violinstudio.di

import com.violinstudio.BuildConfig
import com.violinstudio.core.firebase.EmulatorConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppConfigModule {
    @Provides
    fun provideEmulatorConfig(): EmulatorConfig =
        EmulatorConfig(enabled = BuildConfig.USE_EMULATORS, host = BuildConfig.EMULATOR_HOST)
}
```

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name=".ViolinStudioApp"
        android:allowBackup="false"
        android:label="@string/app_name"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/dev/AndroidManifest.xml` (los emuladores de Firebase hablan HTTP en claro):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:networkSecurityConfig="@xml/network_security_config" />
</manifest>
```

`app/src/dev/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Solo flavor dev: permite HTTP hacia los emuladores (10.0.2.2 o la IP del PC). -->
<network-security-config>
    <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```

- [ ] **Step 5: Ejecutar y ver que pasa**

Run: `./gradlew :app:testDevDebugUnitTest --tests "com.violinstudio.home.HomeScreenTest"`
Expected: PASS, 4 tests.

- [ ] **Step 6: Añadir capturas Roborazzi y grabar las de referencia**

`app/src/test/kotlin/com/violinstudio/home/HomeScreenScreenshotTest.kt`:

```kotlin
package com.violinstudio.home

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.violinstudio.core.ui.theme.ViolinStudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class HomeScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(state: HomeState, name: String) {
        compose.setContent { ViolinStudioTheme { HomeScreen(state = state, onIntent = {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun idle() = capture(HomeState(HealthStatus.Idle), "home_idle")

    @Test fun loading() {
        compose.mainClock.autoAdvance = false
        capture(HomeState(HealthStatus.Loading), "home_loading")
    }

    @Test fun ok() = capture(HomeState(HealthStatus.Ok("0.1.0")), "home_ok")

    @Test fun error() = capture(HomeState(HealthStatus.Error("Sin conexión")), "home_error")
}
```

Run: `./gradlew :app:recordRoborazziDevDebug`
Expected: BUILD SUCCESSFUL y 4 PNG en `app/src/test/screenshots/`. Abrirlas y comprobar a ojo que cada una muestra su estado.

Run: `./gradlew :app:verifyRoborazziDevDebug`
Expected: BUILD SUCCESSFUL.

Nota: las capturas se comparan en local. En CI solo se renderizan (el test falla si la composición lanza una excepción), porque la fuente de Linux y Windows no coincide píxel a píxel.

- [ ] **Step 7: Compilar y arrancar la app**

Run: `./gradlew :app:check :app:assembleDevDebug`
Expected: BUILD SUCCESSFUL.

Instalar en un emulador (`adb install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk`), abrir "Violin Studio" y comprobar que muestra "Pulsa el botón para comprobar el servidor". Pulsar el botón **sin** emuladores de Firebase: debe acabar en un mensaje de error y un snackbar, sin crash.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(app): add Home screen, app shell, App Check and screenshots

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Cloud Functions — `health`

**Files:**
- Create: `functions/package.json`, `functions/tsconfig.json`, `functions/tsconfig.build.json`, `functions/jest.config.js`, `functions/eslint.config.mjs`
- Create: `functions/src/index.ts`, `functions/src/appcheck.ts`, `functions/src/version.ts`
- Test: `functions/test/unit/appcheck.test.ts`, `functions/test/unit/health.test.ts`
- Test: `functions/test/integration/health.int.test.ts`
- Create: `firebase.json`, `.firebaserc` (solo la parte de Functions y emuladores; las reglas llegan en Task 10)

**Interfaces:**
- Produces: callable `health` en `europe-west1` → `{ status: "ok", version: string }`; `shouldEnforceAppCheck(env: NodeJS.ProcessEnv): boolean`; `VERSION: string`.

- [ ] **Step 1: Crear el paquete**

`functions/package.json`:

```json
{
  "name": "violin-studio-functions",
  "version": "0.1.0",
  "private": true,
  "main": "lib/index.js",
  "engines": { "node": "20" },
  "scripts": {
    "build": "tsc -p tsconfig.build.json",
    "lint": "eslint src test",
    "test": "jest test/unit",
    "test:integration": "jest test/integration"
  },
  "dependencies": {
    "firebase-admin": "^13.0.0",
    "firebase-functions": "^6.3.0"
  },
  "devDependencies": {
    "@types/jest": "^29.5.14",
    "eslint": "^9.17.0",
    "firebase-functions-test": "^3.4.0",
    "jest": "^29.7.0",
    "ts-jest": "^29.2.5",
    "typescript": "^5.7.2",
    "typescript-eslint": "^8.18.0"
  }
}
```

`functions/tsconfig.json` (editor y tests):

```json
{
  "compilerOptions": {
    "target": "es2022",
    "module": "commonjs",
    "strict": true,
    "esModuleInterop": true,
    "resolveJsonModule": true,
    "skipLibCheck": true,
    "noEmit": true
  },
  "include": ["src", "test"]
}
```

`functions/tsconfig.build.json`:

```json
{
  "extends": "./tsconfig.json",
  "compilerOptions": { "noEmit": false, "rootDir": "src", "outDir": "lib", "sourceMap": true },
  "include": ["src"]
}
```

`functions/jest.config.js`:

```js
module.exports = { preset: "ts-jest", testEnvironment: "node" };
```

`functions/eslint.config.mjs`:

```js
import tseslint from "typescript-eslint";

export default tseslint.config(...tseslint.configs.recommended, { ignores: ["lib/**", "*.js"] });
```

Run: `cd functions && npm install` (genera `package-lock.json`, que se versiona).

- [ ] **Step 2: Escribir los tests unitarios que fallan**

`functions/test/unit/appcheck.test.ts`:

```ts
import { shouldEnforceAppCheck } from "../../src/appcheck";

describe("shouldEnforceAppCheck", () => {
  test("exige App Check por defecto", () => {
    expect(shouldEnforceAppCheck({})).toBe(true);
  });
  test("no lo exige en el emulador", () => {
    expect(shouldEnforceAppCheck({ FUNCTIONS_EMULATOR: "true" })).toBe(false);
  });
  test("se puede desactivar con ENFORCE_APP_CHECK=false", () => {
    expect(shouldEnforceAppCheck({ ENFORCE_APP_CHECK: "false" })).toBe(false);
  });
  test("cualquier otro valor lo deja activado", () => {
    expect(shouldEnforceAppCheck({ ENFORCE_APP_CHECK: "no" })).toBe(true);
  });
});
```

`functions/test/unit/health.test.ts`:

```ts
import functionsTest from "firebase-functions-test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { health } from "../../src/index";
import { VERSION } from "../../src/version";

const fft = functionsTest();
afterAll(() => fft.cleanup());

test("health responde ok con la versión", async () => {
  const wrapped = fft.wrap(health);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  await expect(wrapped({ data: {} } as any)).resolves.toEqual({ status: "ok", version: VERSION });
});

test("VERSION coincide con package.json", () => {
  const pkg = JSON.parse(readFileSync(join(__dirname, "../../package.json"), "utf8"));
  expect(VERSION).toBe(pkg.version);
});
```

- [ ] **Step 3: Ejecutar y ver que falla**

Run: `npm --prefix functions test`
Expected: FAIL, `Cannot find module '../../src/appcheck'`.

- [ ] **Step 4: Implementar**

`functions/src/version.ts`:

```ts
/** Debe coincidir con package.json (lo comprueba un test). */
export const VERSION = "0.1.0";
```

`functions/src/appcheck.ts`:

```ts
/**
 * App Check se exige salvo en el emulador (no hay tokens reales) o si ENFORCE_APP_CHECK=false.
 * Play Integrity solo valida apps instaladas desde Google Play: mientras la app se reparta por
 * App Distribution, prod necesita ENFORCE_APP_CHECK=false en functions/.env.violin-app-795ee.
 */
export function shouldEnforceAppCheck(env: NodeJS.ProcessEnv): boolean {
  if (env.FUNCTIONS_EMULATOR === "true") return false;
  return env.ENFORCE_APP_CHECK !== "false";
}
```

`functions/src/index.ts`:

```ts
import { onCall } from "firebase-functions/v2/https";
import { shouldEnforceAppCheck } from "./appcheck";
import { VERSION } from "./version";

export const REGION = "europe-west1";

export const health = onCall(
  { region: REGION, enforceAppCheck: shouldEnforceAppCheck(process.env) },
  () => ({ status: "ok", version: VERSION }),
);
```

- [ ] **Step 5: Ejecutar tests unitarios, lint y build**

Run: `npm --prefix functions test && npm --prefix functions run lint && npm --prefix functions run build`
Expected: 6 tests en verde, lint sin errores, `functions/lib/index.js` generado.

- [ ] **Step 6: Añadir la configuración de Firebase y el test de integración**

`firebase.json`:

```json
{
  "functions": [
    {
      "source": "functions",
      "codebase": "default",
      "ignore": ["node_modules", "test", "*.log"],
      "predeploy": ["npm --prefix \"$RESOURCE_DIR\" run build"]
    }
  ],
  "emulators": {
    "auth": { "port": 9099 },
    "functions": { "port": 5001 },
    "firestore": { "port": 8080 },
    "storage": { "port": 9199 },
    "ui": { "enabled": true },
    "singleProjectMode": true
  }
}
```

`.firebaserc`:

```json
{
  "projects": {
    "default": "violin-app-dev",
    "dev": "violin-app-dev",
    "prod": "violin-app-795ee"
  }
}
```

`functions/test/integration/health.int.test.ts`:

```ts
import { VERSION } from "../../src/version";

const host = process.env.FUNCTIONS_EMULATOR_HOST ?? "127.0.0.1:5001";
const project = process.env.GCLOUD_PROJECT ?? "demo-violin-studio";
const url = `http://${host}/${project}/europe-west1/health`;

test("health responde ok en el emulador", async () => {
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ data: {} }),
  });
  expect(res.status).toBe(200);
  expect(await res.json()).toEqual({ result: { status: "ok", version: VERSION } });
});
```

Run: `firebase emulators:exec --only functions --project demo-violin-studio "npm --prefix functions run test:integration"`
Expected: 1 test en verde y el emulador se apaga solo.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(functions): add health callable with App Check toggle

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Reglas de Firestore y Storage cerradas

**Files:**
- Create: `firestore.rules`, `storage.rules`
- Modify: `firebase.json` (añadir `firestore` y `storage`)
- Create: `rules-tests/package.json`, `rules-tests/jest.config.js`, `rules-tests/tsconfig.json`
- Test: `rules-tests/test/deny-all.test.ts`

**Interfaces:**
- Produces: reglas deny-all; paquete `rules-tests` con `npm test` que se ejecuta dentro de `firebase emulators:exec`.

- [ ] **Step 1: Crear el paquete de tests de reglas**

`rules-tests/package.json`:

```json
{
  "name": "violin-studio-rules-tests",
  "private": true,
  "scripts": { "test": "jest" },
  "devDependencies": {
    "@firebase/rules-unit-testing": "^4.0.1",
    "@types/jest": "^29.5.14",
    "firebase": "^11.1.0",
    "jest": "^29.7.0",
    "ts-jest": "^29.2.5",
    "typescript": "^5.7.2"
  }
}
```

`rules-tests/jest.config.js`:

```js
module.exports = { preset: "ts-jest", testEnvironment: "node", testTimeout: 20000 };
```

`rules-tests/tsconfig.json`:

```json
{
  "compilerOptions": { "target": "es2022", "module": "commonjs", "strict": true, "esModuleInterop": true, "skipLibCheck": true, "noEmit": true },
  "include": ["test"]
}
```

Run: `cd rules-tests && npm install`

- [ ] **Step 2: Escribir el test que falla**

`rules-tests/test/deny-all.test.ts`:

```ts
import { assertFails, initializeTestEnvironment, RulesTestContext, RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc } from "firebase/firestore";
import { getBytes, ref, uploadString } from "firebase/storage";
import { readFileSync } from "node:fs";
import { join } from "node:path";

let env: RulesTestEnvironment;

beforeAll(async () => {
  // Host y puerto salen de FIRESTORE_EMULATOR_HOST / FIREBASE_STORAGE_EMULATOR_HOST (los pone emulators:exec).
  env = await initializeTestEnvironment({
    projectId: "demo-violin-studio",
    firestore: { rules: readFileSync(join(__dirname, "../../firestore.rules"), "utf8") },
    storage: { rules: readFileSync(join(__dirname, "../../storage.rules"), "utf8") },
  });
});

afterAll(() => env.cleanup());

const contexts: [string, () => RulesTestContext][] = [
  ["anónimo", () => env.unauthenticatedContext()],
  ["autenticado", () => env.authenticatedContext("alice")],
];

describe.each(contexts)("usuario %s", (_, ctx) => {
  test("no puede leer Firestore", async () => {
    await assertFails(getDoc(doc(ctx().firestore(), "users/alice")));
  });
  test("no puede escribir Firestore", async () => {
    await assertFails(setDoc(doc(ctx().firestore(), "users/alice"), { name: "Alice" }));
  });
  test("no puede leer Storage", async () => {
    await assertFails(getBytes(ref(ctx().storage(), "users/alice/demo.txt")));
  });
  test("no puede escribir Storage", async () => {
    await assertFails(uploadString(ref(ctx().storage(), "users/alice/demo.txt"), "hola"));
  });
});
```

- [ ] **Step 3: Ejecutar y ver que falla**

Run: `firebase emulators:exec --only firestore,storage --project demo-violin-studio "npm --prefix rules-tests test"`
Expected: FAIL. Sin `firestore.rules` ni la sección en `firebase.json`, los emuladores no arrancan o `readFileSync` lanza `ENOENT`.

- [ ] **Step 4: Implementar las reglas**

`firestore.rules`:

```
rules_version = '2';
// Fase 1: todo cerrado. Cada fase abre solo las colecciones que necesita, con su test.
service cloud.firestore {
  match /databases/{database}/documents {
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

`storage.rules`:

```
rules_version = '2';
// Fase 1: todo cerrado.
service firebase.storage {
  match /b/{bucket}/o {
    match /{allPaths=**} {
      allow read, write: if false;
    }
  }
}
```

Añadir a `firebase.json`, al nivel de `"functions"`:

```json
  "firestore": { "rules": "firestore.rules" },
  "storage": { "rules": "storage.rules" },
```

- [ ] **Step 5: Ejecutar y ver que pasa**

Run: `firebase emulators:exec --only firestore,storage --project demo-violin-studio "npm --prefix rules-tests test"`
Expected: 8 tests en verde.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(firebase): add deny-all Firestore and Storage rules with tests

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Test E2E de Home contra los emuladores

**Antes de empezar:** prerrequisito **P2** (emulador Android y Firebase CLI).

**Files:**
- Test: `app/src/androidTest/kotlin/com/violinstudio/HomeHealthE2ETest.kt`

**Interfaces:**
- Consumes: la app completa (Task 8) y la Function `health` (Task 9) en el emulador de Functions con proyecto `violin-app-dev` (el de `google-services.json` dev, que usa el SDK para construir la URL).

- [ ] **Step 1: Escribir el test**

`app/src/androidTest/kotlin/com/violinstudio/HomeHealthE2ETest.kt`:

```kotlin
package com.violinstudio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Requiere el emulador de Functions en el PC:
 *   firebase emulators:start --only functions --project violin-app-dev
 * Ejecutar con: ./gradlew :app:connectedDevDebugAndroidTest
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HomeHealthE2ETest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun comprobarServidorMuestraOkConLaVersion() {
        compose.onNodeWithText("Comprobar servidor").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("health_ok"), timeoutMillis = 30_000)
        compose.onNodeWithText("Servidor OK · v0.1.0").assertExists()
    }
}
```

- [ ] **Step 2: Ejecutarlo sin emulador de Functions y ver que falla**

Con un emulador Android arrancado y **sin** el de Functions:

Run: `./gradlew :app:connectedDevDebugAndroidTest`
Expected: FAIL por `ComposeTimeoutException`: la app muestra un error en vez de `health_ok`. Así se confirma que el test no pasa por accidente.

- [ ] **Step 3: Ejecutarlo con el emulador de Functions y ver que pasa**

```bash
npm --prefix functions run build
firebase emulators:start --only functions --project violin-app-dev   # en otra terminal
./gradlew :app:connectedDevDebugAndroidTest
```

Expected: BUILD SUCCESSFUL, 1 test en verde.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "test(app): add Home health E2E test against the Functions emulator

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: `secrets-guard`, CI y fastlane

**Antes de empezar:** prerrequisito **P3** (keystore local) para probar la firma.

**Files:**
- Create: `scripts/secrets-guard.sh`
- Test: `scripts/test-secrets-guard.sh`
- Create: `.github/workflows/ci.yml`
- Create: `Gemfile`, `fastlane/Appfile`, `fastlane/Fastfile`

**Interfaces:**
- Produces: `scripts/secrets-guard.sh [dir]` → exit 0 si git no rastrea secretos; exit 1 y lista de ficheros si sí. Workflow `CI` con jobs `secrets-guard`, `android`, `functions`, `rules`, `e2e`, `release`. Lane `android deploy_firebase` (lee `FIREBASE_APP_ID_PROD` y `FIREBASE_SERVICE_ACCOUNT_PATH` del entorno).

- [ ] **Step 1: Escribir el test que falla del guard**

`scripts/test-secrets-guard.sh`:

```bash
#!/usr/bin/env bash
# Prueba secrets-guard.sh en repos temporales.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
guard="$here/secrets-guard.sh"
fail=0

new_repo() {
  local dir
  dir="$(mktemp -d)"
  git -C "$dir" init -q
  git -C "$dir" config user.email t@t
  git -C "$dir" config user.name t
  echo "$dir"
}

expect() { # expect <exit esperado> <descripción> <ficheros...>
  local want="$1" desc="$2"; shift 2
  local repo; repo="$(new_repo)"
  for f in "$@"; do mkdir -p "$repo/$(dirname "$f")"; echo x > "$repo/$f"; git -C "$repo" add -f "$f"; done
  set +e; bash "$guard" "$repo" >/dev/null 2>&1; local got=$?; set -e
  if [ "$got" -ne "$want" ]; then echo "FALLO: $desc (exit $got, esperado $want)"; fail=1; else echo "ok: $desc"; fi
  rm -rf "$repo"
}

expect 0 "repo limpio" README.md .env.example
expect 1 "google-services.json" app/src/dev/google-services.json
expect 1 "keystore .jks" keystore/upload.jks
expect 1 "keystore .keystore" debug.keystore
expect 1 ".env" .env
expect 1 ".env.production" functions/.env.violin-app-795ee
exit $fail
```

Run: `bash scripts/test-secrets-guard.sh`
Expected: FAIL. `secrets-guard.sh` no existe, así que todos los casos que esperan 1 reciben 127, y el primero también falla.

- [ ] **Step 2: Implementar el guard**

`scripts/secrets-guard.sh`:

```bash
#!/usr/bin/env bash
# Falla si git rastrea ficheros con secretos. Uso: secrets-guard.sh [directorio-del-repo]
set -euo pipefail
repo="${1:-.}"
found="$(git -C "$repo" ls-files \
  | grep -E '(^|/)google-services\.json$|\.jks$|\.keystore$|(^|/)\.env($|\.)' \
  | grep -vE '(^|/)\.env\.example$' || true)"
if [ -n "$found" ]; then
  echo "Secretos rastreados por git (quítalos con git rm --cached):"
  echo "$found"
  exit 1
fi
echo "secrets-guard: ok"
```

Run: `bash scripts/test-secrets-guard.sh && bash scripts/secrets-guard.sh`
Expected: 6 líneas `ok:` y `secrets-guard: ok`.

- [ ] **Step 3: fastlane**

`Gemfile`:

```ruby
source "https://rubygems.org"

gem "fastlane"
gem "fastlane-plugin-firebase_app_distribution"
```

`fastlane/Appfile`:

```ruby
package_name("com.violinstudio")
```

`fastlane/Fastfile`:

```ruby
default_platform(:android)

platform :android do
  desc "Publica el APK prodRelease en Firebase App Distribution (grupo testers)"
  lane :deploy_firebase do
    # APK y no AAB: App Distribution solo acepta AAB con el proyecto vinculado a Google Play.
    firebase_app_distribution(
      app: ENV.fetch("FIREBASE_APP_ID_PROD"),
      android_artifact_type: "APK",
      android_artifact_path: "app/build/outputs/apk/prod/release/app-prod-release.apk",
      service_credentials_file: ENV.fetch("FIREBASE_SERVICE_ACCOUNT_PATH"),
      groups: "testers",
      release_notes: "CI ##{ENV['GITHUB_RUN_NUMBER']} - #{last_git_commit[:abbreviated_commit_hash]}"
    )
  end
end
```

- [ ] **Step 4: Workflow de CI**

`.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  secrets-guard:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: bash scripts/test-secrets-guard.sh
      - run: bash scripts/secrets-guard.sh

  android:
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21" }
      - uses: gradle/actions/setup-gradle@v4
      - name: google-services.json
        env:
          GS_DEV: ${{ secrets.GOOGLE_SERVICES_DEV_BASE64 }}
          GS_PROD: ${{ secrets.GOOGLE_SERVICES_PROD_BASE64 }}
        run: |
          mkdir -p app/src/dev app/src/prod
          echo "$GS_DEV" | base64 -d > app/src/dev/google-services.json
          echo "$GS_PROD" | base64 -d > app/src/prod/google-services.json
      - run: ./gradlew check coverage --continue
      - if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: android-reports
          path: "**/build/reports/"

  functions:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: "20", cache: npm, cache-dependency-path: functions/package-lock.json }
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21" }
      - run: npm ci --prefix functions
      - run: npm --prefix functions run lint
      - run: npm --prefix functions run build
      - run: npm --prefix functions test
      - run: npm i -g firebase-tools
      - run: firebase emulators:exec --only functions --project demo-violin-studio "npm --prefix functions run test:integration"

  rules:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: "20", cache: npm, cache-dependency-path: rules-tests/package-lock.json }
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21" }
      - run: npm ci --prefix rules-tests
      - run: npm i -g firebase-tools
      - run: firebase emulators:exec --only firestore,storage --project demo-violin-studio "npm --prefix rules-tests test"

  e2e:
    runs-on: ubuntu-latest
    timeout-minutes: 45
    continue-on-error: true # pasa a bloquear cuando sea estable
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21" }
      - uses: actions/setup-node@v4
        with: { node-version: "20" }
      - uses: gradle/actions/setup-gradle@v4
      - name: google-services.json
        env:
          GS_DEV: ${{ secrets.GOOGLE_SERVICES_DEV_BASE64 }}
          GS_PROD: ${{ secrets.GOOGLE_SERVICES_PROD_BASE64 }}
        run: |
          mkdir -p app/src/dev app/src/prod
          echo "$GS_DEV" | base64 -d > app/src/dev/google-services.json
          echo "$GS_PROD" | base64 -d > app/src/prod/google-services.json
      - run: npm ci --prefix functions && npm --prefix functions run build
      - run: npm i -g firebase-tools
      - name: Arrancar el emulador de Functions
        run: |
          nohup firebase emulators:start --only functions --project violin-app-dev > emulators.log 2>&1 &
          npx --yes wait-on tcp:127.0.0.1:5001 -t 120000
      - name: Habilitar KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules
          sudo udevadm trigger --name-match=kvm
      - uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 34
          arch: x86_64
          target: google_apis
          script: ./gradlew :app:connectedDevDebugAndroidTest
      - if: always()
        uses: actions/upload-artifact@v4
        with: { name: emulators-log, path: emulators.log }

  release:
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    needs: [secrets-guard, android, functions, rules]
    runs-on: ubuntu-latest
    timeout-minutes: 30
    env:
      KEYSTORE_PATH: ${{ github.workspace }}/../upload.jks
      STORE_PASSWORD: ${{ secrets.STORE_PASSWORD }}
      KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
      KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21" }
      - uses: gradle/actions/setup-gradle@v4
      - name: google-services.json y keystore
        env:
          GS_DEV: ${{ secrets.GOOGLE_SERVICES_DEV_BASE64 }}
          GS_PROD: ${{ secrets.GOOGLE_SERVICES_PROD_BASE64 }}
          KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
        run: |
          mkdir -p app/src/dev app/src/prod
          echo "$GS_DEV" | base64 -d > app/src/dev/google-services.json
          echo "$GS_PROD" | base64 -d > app/src/prod/google-services.json
          echo "$KEYSTORE_BASE64" | base64 -d > "$KEYSTORE_PATH"
          test -s "$KEYSTORE_PATH"
      - run: ./gradlew assembleProdRelease bundleProdRelease
      - name: Verificar firma
        run: |
          apksigner="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)/apksigner"
          "$apksigner" verify --print-certs app/build/outputs/apk/prod/release/app-prod-release.apk
      - uses: actions/upload-artifact@v4
        with: { name: release-aab, path: app/build/outputs/bundle/prodRelease/ }
      - uses: ruby/setup-ruby@v1
        with: { ruby-version: "3.3" }
      - run: bundle install
      - name: Publicar en App Distribution
        env:
          FIREBASE_APP_ID_PROD: ${{ vars.FIREBASE_APP_ID_PROD }}
          FIREBASE_SERVICE_ACCOUNT_JSON: ${{ secrets.FIREBASE_SERVICE_ACCOUNT_JSON }}
        run: |
          export FIREBASE_SERVICE_ACCOUNT_PATH="$RUNNER_TEMP/sa.json"
          printf '%s' "$FIREBASE_SERVICE_ACCOUNT_JSON" > "$FIREBASE_SERVICE_ACCOUNT_PATH"
          bundle exec fastlane android deploy_firebase
```

- [ ] **Step 5: Probar la firma en local**

Con el keystore de P3 en `keystore/upload.jks`:

```bash
export STORE_PASSWORD=... KEY_PASSWORD=...     # no dejarlos en el historial del shell
./gradlew assembleProdRelease
"$ANDROID_HOME/build-tools/$(ls $ANDROID_HOME/build-tools | sort -V | tail -1)/apksigner" verify --print-certs app/build/outputs/apk/prod/release/app-prod-release.apk
```

Expected: el certificado impreso coincide con el SHA-256 registrado en P3. Instalar el APK y comprobar que arranca (valida que R8 no rompe Hilt, Firebase ni las rutas serializables).

- [ ] **Step 6: Comprobación completa en local**

Run: `./gradlew check coverage && bash scripts/secrets-guard.sh`
Expected: BUILD SUCCESSFUL y `secrets-guard: ok`.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "ci: add secrets guard, CI workflow and App Distribution lane

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Documentación, specs importadas y publicación en GitHub

**Antes de empezar:** prerrequisito **P4**.

**Files:**
- Create: `README.md`
- Create: `openspec/specs/app/`, `openspec/specs/instrument-selection/`, `openspec/specs/tuner-configuration/` (copiados de `..\violin-master\openspec\specs\`)
- Create: `openspec/README.md`

- [ ] **Step 1: Importar las specs de Violin-master**

```bash
mkdir -p openspec/specs
cp -r ../violin-master/openspec/specs/app ../violin-master/openspec/specs/instrument-selection ../violin-master/openspec/specs/tuner-configuration openspec/specs/
```

`openspec/README.md`:

```markdown
# openspec

Specs de capacidades de Violin Studio.

- `specs/app`, `specs/instrument-selection`, `specs/tuner-configuration`: importadas de Violin-master
  (`daviddglL/Violin` @ `9163c12`). Se revisan y adaptan en la fase 3 (Práctica).
- Las specs de Proyecto_musica (`daviddglL/Prueba-app-music` @ `6390769`) se importan en la fase
  que implementa cada capacidad (consentimiento en la fase 2, captura y subida en la fase 4).
```

- [ ] **Step 2: README**

`README.md`:

````markdown
# Violin Studio

App Android para practicar violín. Une Proyecto_musica (`daviddglL/Prueba-app-music` @ `6390769`)
y Violin-master (`daviddglL/Violin` @ `9163c12`).

- Diseño de la fase 1: `docs/superpowers/specs/2026-09-25-violin-studio-fase1-design.md`
- Plan de la fase 1: `docs/superpowers/plans/2026-09-25-violin-studio-fase1.md`

## Requisitos

JDK 21, Android SDK 36, Node 20, Firebase CLI (`npm i -g firebase-tools`).

Los `google-services.json` no están en git: descárgalos de la consola de Firebase a
`app/src/dev/` (proyecto `violin-app-dev`) y `app/src/prod/` (proyecto `violin-app-795ee`).

## Comandos

```bash
./gradlew check coverage                     # ktlint, lint, tests y cobertura
./gradlew :app:recordRoborazziDevDebug       # regrabar capturas
./gradlew :app:verifyRoborazziDevDebug       # comparar capturas
npm --prefix functions test                  # tests unitarios de Functions
firebase emulators:start --only functions --project violin-app-dev
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
````

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "docs: add README and import Violin-master openspec specs

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 4: Crear el repo remoto y subir (solo con confirmación explícita del usuario)**

Preguntar al usuario antes de ejecutar. Con su "sí":

```bash
gh repo create daviddglL/violin-studio --private --source . --remote origin --push
```

- [ ] **Step 5: Configurar secretos y variables (valores del usuario)**

El usuario ejecuta (o da permiso para ejecutar) con sus valores; nunca se escriben en ficheros del repo:

```bash
gh secret set GOOGLE_SERVICES_DEV_BASE64  < <(base64 -w0 app/src/dev/google-services.json)
gh secret set GOOGLE_SERVICES_PROD_BASE64 < <(base64 -w0 app/src/prod/google-services.json)
gh secret set KEYSTORE_BASE64             < <(base64 -w0 keystore/upload.jks)
gh secret set STORE_PASSWORD
gh secret set KEY_ALIAS --body upload
gh secret set KEY_PASSWORD
gh secret set FIREBASE_SERVICE_ACCOUNT_JSON < ruta/a/cuenta-de-servicio.json
gh variable set FIREBASE_APP_ID_PROD --body "<App ID de com.violinstudio en violin-app-795ee>"
```

- [ ] **Step 6: Proteger `main` (con confirmación del usuario)**

```bash
gh api -X PUT repos/daviddglL/violin-studio/branches/main/protection \
  -F required_status_checks[strict]=true \
  -f 'required_status_checks[contexts][]=secrets-guard' \
  -f 'required_status_checks[contexts][]=android' \
  -f 'required_status_checks[contexts][]=functions' \
  -f 'required_status_checks[contexts][]=rules' \
  -F enforce_admins=false \
  -F required_pull_request_reviews=null \
  -F restrictions=null
```

Nota: la protección de ramas en repos privados necesita GitHub Pro. Si la API responde 403, avisar al usuario y seguir sin ella.

- [ ] **Step 7: Verificar los criterios de terminado en CI**

Esperar al workflow del push a `main` (`gh run watch`). Comprobar:
- `secrets-guard`, `android`, `functions`, `rules` en verde.
- `e2e` ejecutado (puede fallar sin bloquear; si falla, revisar `emulators-log`).
- `release` en verde y el APK visible en App Distribution para el grupo `testers`.

Anotar en la PR/issue de cierre qué criterio (1–8 del spec) cubre cada job.
