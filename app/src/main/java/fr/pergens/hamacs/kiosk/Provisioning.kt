package fr.pergens.hamacs.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import fr.pergens.hamacs.App
import fr.pergens.hamacs.BuildConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest

/**
 * Propriétaire de l'appareil sans ordinateur : QR code de provisionnement, scanné sur l'écran de bienvenue d'une
 * tablette réinitialisée (6 touches au même endroit). Android télécharge l'APK de la Release, vérifie qu'il est signé
 * avec la même clé que l'app qui a affiché le code, puis l'installe comme propriétaire (appareil « entièrement géré »).
 */
object Provisioning {
    /** Empreinte SHA-256 du certificat de signature, en base64 « URL » sans remplissage (format attendu par Android). */
    fun signatureChecksum(ctx: Context): String? = runCatching {
        val pm = ctx.packageManager
        val cert = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        } ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
        Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }.getOrNull()

    /** Contenu du QR code : APK de la Release de la version installée (sinon la dernière), pour l'architecture de la tablette. */
    suspend fun payload(ctx: Context): String? {
        val apk = App.instance.updater.apkFor(BuildConfig.VERSION_NAME) ?: return null
        val checksum = signatureChecksum(ctx) ?: return null
        return buildJsonObject {
            put("android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME", JsonPrimitive(Kiosk.admin(ctx).flattenToString()))
            put("android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION", JsonPrimitive(apk))
            put("android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM", JsonPrimitive(checksum))
            // Garde les apps du système (navigateur, Play Store, appareil photo…), masquées sinon.
            put("android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED", JsonPrimitive(true))
        }.toString()
    }
}

/** Android 10+ : mode de provisionnement demandé à l'app pendant l'installation par QR code (appareil entièrement géré). */
class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 29) {
            setResult(RESULT_OK, Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE))
        } else setResult(RESULT_CANCELED)
        finish()
    }
}

/** Android 10+ : dernière étape du provisionnement (aucune règle à imposer, le kiosque se règle ensuite dans l'app). */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        finish()
    }
}
