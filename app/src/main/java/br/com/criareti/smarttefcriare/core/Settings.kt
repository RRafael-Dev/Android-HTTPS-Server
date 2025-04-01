package br.com.criareti.smarttefcriare.core

import android.content.SharedPreferences
import br.com.criareti.smarttefcriare.util.HTTPProtocol
import java.lang.RuntimeException
import kotlin.reflect.KProperty

class SettingsException(message: String) : RuntimeException(message)

interface ISettingsDelegate<T> {
    operator fun <T> getValue(thisRef: Settings, property: KProperty<*>): T
    operator fun <T> setValue(thisRef: Settings, property: KProperty<*>, value: T)
}

class SettingsPreferencesDelegate<T>(private val prefs: SharedPreferences) : ISettingsDelegate<T> {
    override operator fun <T> getValue(thisRef: Settings, property: KProperty<*>): T =
        prefs.all[property.name] as T

    override operator fun <T> setValue(thisRef: Settings, property: KProperty<*>, value: T) {
        val success = prefs.edit().apply {
            when (value) {
                is String -> putString(property.name, value as String)
                is Boolean -> putBoolean(property.name, value as Boolean)
                is Int -> putInt(property.name, value as Int)
                is Double -> putFloat(property.name, value.toFloat())
                is Float -> putFloat(property.name, value as Float)
                is Long -> putLong(property.name, value as Long)
                is Iterable<*> -> putStringSet(property.name, value.map { it.toString() }.toSet())
                else -> throw SettingsException("Valor inesperado para ${property.name}")
            }
        }.commit()
        if (!success) throw SettingsException("Falha ao salvar configuração ${property.name}")
    }
}

class Settings(private val delegate: ISettingsDelegate<*>) {
    companion object {
        const val DEFAULT_HTTP_SERVER_PORT = 8899

        private var mInstance: Settings? = null
        val instance: Settings
            get() = mInstance!!

        fun initInstance(prefs: SharedPreferences): Settings {
            if (mInstance != null) throw SettingsException("Configuração já inicializada")

            mInstance = Settings(SettingsPreferencesDelegate<Any>(prefs))
            return mInstance!!
        }
    }

    private var httpProtocolStr: String? by delegate;

    var enableTEFStone: Boolean by delegate
    var httpServerPort: Int? by delegate
    var httpProtocol: HTTPProtocol?
        get() = httpProtocolStr?.let { HTTPProtocol.valueOf(it) }
        set(value) { httpProtocolStr = value?.name }
}