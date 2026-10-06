package com.violinstudio.ui.commons.locale

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Locale

/** Idiomas que ofrece la app. [SYSTEM] no fija ninguno: se sigue el idioma del dispositivo. */
enum class AppLanguage(private val tag: String?) {
    SYSTEM(null),
    SPANISH("es"),
    ENGLISH("en");

    /** Lo que se aplica al sistema: la lista vacía devuelve el control al idioma del dispositivo. */
    val localeTags: List<String> get() = listOfNotNull(tag)

    companion object {
        /** Manda la primera etiqueta; una región ("en-GB") cuenta como su idioma y lo que no se ofrece, como sistema. */
        fun fromTags(tags: List<String>): AppLanguage {
            val language = tags.firstOrNull()?.substringBefore('-')?.lowercase(Locale.ROOT) ?: return SYSTEM
            return entries.firstOrNull { it.tag == language } ?: SYSTEM
        }
    }
}

/** Costura sobre el idioma por app del sistema, para poder probar el ViewModel sin Android. */
interface AppLocales {
    /** Etiquetas BCP 47 aplicadas ahora; vacía si se sigue el idioma del dispositivo. */
    fun current(): List<String>

    /** Aplica las etiquetas (vacía = idioma del dispositivo); el sistema recrea las pantallas abiertas. */
    fun set(tags: List<String>)
}

/**
 * Android 13+: delega en el `LocaleManager` del sistema. Antes: AppCompat guarda la lista (servicio de metadatos con
 * `autoStoreLocales`) y la aplica a las actividades AppCompat.
 */
class AppCompatAppLocales : AppLocales {
    override fun current(): List<String> {
        val locales = AppCompatDelegate.getApplicationLocales()
        return List(locales.size()) { locales[it]?.toLanguageTag().orEmpty() }.filter { it.isNotEmpty() }
    }

    override fun set(tags: List<String>) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags.joinToString(",")))
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppLocalesModule {
    @Provides
    fun provideAppLocales(): AppLocales = AppCompatAppLocales()
}
