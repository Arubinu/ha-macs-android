package fr.pergens.hamacs.config

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * Langue de l'application : celle du système, ou une langue choisie dans les réglages (préférence
 * `app_language`). Appliquée au contexte de l'application et de l'activité dès leur création.
 */
object AppLocale {
    /** Langues traduites (codes BCP 47), dans l'ordre du sélecteur. */
    val SUPPORTED = listOf("en", "fr", "de", "es", "it", "nl", "pt", "pl")

    private const val PREFS = "settings"
    private const val KEY = "app_language"

    fun chosen(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null).orEmpty()

    /** Contexte dans la langue choisie (inchangé si l'app suit le système). */
    fun wrap(base: Context): Context {
        val tag = chosen(base)
        if (tag.isBlank()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /** Nom d'une langue dans cette langue (« Deutsch », « Español »…). */
    fun displayName(tag: String): String {
        val l = Locale.forLanguageTag(tag)
        return l.getDisplayLanguage(l).replaceFirstChar { it.uppercase(l) }
    }

    /**
     * Enregistre [tag] ("" = langue du système) puis relance l'application, pour que tous les textes (y compris
     * ceux chargés au démarrage, comme les alertes) passent dans la nouvelle langue.
     */
    fun set(activity: Activity, tag: String) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).commit()
        val intent = Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(intent)
        activity.finish()
        Runtime.getRuntime().exit(0)
    }
}
