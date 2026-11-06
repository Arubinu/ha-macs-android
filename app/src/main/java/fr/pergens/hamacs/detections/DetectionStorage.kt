package fr.pergens.hamacs.detections

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import fr.pergens.hamacs.App
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.ha.browseMedia
import fr.pergens.hamacs.ha.resolveMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Fichier trouvé dans un stockage : [folder] relatif à la racine, [modified] en epoch ms si connu. */
data class StoredFile(val name: String, val folder: String, val modified: Long?, val size: Long?, val ref: String) {
    val stem: String get() = name.substringBeforeLast('.')
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
}

/** Stockage des captures et vidéos des détections. */
interface DetectionStorage {
    /** Date de modification disponible (sinon seule la date du nom de fichier est utilisable). */
    val hasModified: Boolean

    /** Fichiers (pas les dossiers) du dossier [folder], relatif à la racine du stockage. */
    suspend fun list(folder: String): List<StoredFile>

    /** Source affichable : adresse http(s), `content://` ou fichier local téléchargé en cache. */
    suspend fun open(file: StoredFile): String?

    /** Noms des sous-dossiers de [folder] (explorateur de dossiers des réglages). */
    suspend fun folders(folder: String): List<String>

    companion object {
        private val cache = HashMap<String, DetectionStorage>()

        /** Stockage décrit par [cfg] (`type` + paramètres), réutilisé tant que sa configuration ne change pas. */
        @Synchronized
        fun of(cfg: JsonObject): DetectionStorage? {
            val key = cfg.toString()
            cache[key]?.let { return it }
            val ctx = App.instance
            val s = when (cfg.s("type") ?: "ha_media") {
                "ha_media" -> HaMediaStorage(
                    cfg.s("path")?.takeIf { it.isNotBlank() } ?: "media-source://media_source/local",
                    cfg.s("url_prefix")?.trim().orEmpty(),
                )
                "smb" -> SmbStorage(
                    ctx, cfg.s("host")?.trim() ?: return null, cfg.s("share")?.trim()?.trim('/', '\\') ?: return null,
                    cfg.s("path").orEmpty(), cfg.s("user").orEmpty(), cfg.s("password").orEmpty(), cfg.s("domain").orEmpty(),
                )
                "nfs" -> NfsStorage(
                    ctx, cfg.s("host")?.trim() ?: return null, cfg.s("export")?.trim() ?: return null, cfg.s("path").orEmpty(),
                    cfg.s("uid")?.toIntOrNull() ?: 0, cfg.s("gid")?.toIntOrNull() ?: 0,
                )
                "local" -> LocalStorage(ctx, Uri.parse(cfg.s("uri") ?: return null), cfg.s("path").orEmpty())
                else -> return null
            }
            cache[key] = s
            return s
        }

        /** Supprime les téléchargements de plus d'un jour. */
        fun trimCache(ctx: Context) {
            val limit = System.currentTimeMillis() - 24 * 3600_000L
            cacheDir(ctx).listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
        }

        internal fun cacheDir(ctx: Context) = File(ctx.cacheDir, "detections").apply { mkdirs() }

        internal fun join(vararg parts: String) =
            parts.flatMap { it.replace('\\', '/').split('/') }.filter { it.isNotBlank() }.joinToString("/")
    }
}

/**
 * Médias de Home Assistant (`media-source://…`). L'adresse des fichiers est construite avec [urlPrefix]
 * (ex. `/local/media/Enregistrements`) ou, à défaut, demandée à Home Assistant pour chaque fichier.
 */
private class HaMediaStorage(private val root: String, private val urlPrefix: String) : DetectionStorage {
    override val hasModified = false

    override suspend fun list(folder: String): List<StoredFile> {
        val ha = App.instance.ha
        val id = if (folder.isBlank()) root else root.trimEnd('/') + "/" + DetectionStorage.join(folder)
        return ha.browseMedia(id).children.filter { !it.canExpand }.map { StoredFile(it.title, folder, null, null, it.contentId) }
    }

    override suspend fun folders(folder: String): List<String> {
        val id = if (folder.isBlank()) root else root.trimEnd('/') + "/" + DetectionStorage.join(folder)
        return App.instance.ha.browseMedia(id).children.filter { it.canExpand }.map { it.title }
    }

    override suspend fun open(file: StoredFile): String? {
        val ha = App.instance.ha
        return if (urlPrefix.isNotBlank()) ha.resolveUrl("/" + DetectionStorage.join(urlPrefix, file.folder, file.name))
        else runCatching { ha.resolveMedia(file.ref) }.getOrNull()
    }
}

/** Télécharge [file] une seule fois dans le cache (nom dérivé du chemin, de la taille et de la date). */
private suspend fun cached(ctx: Context, lock: Mutex, id: String, file: StoredFile, fetch: suspend (File) -> Unit): String? =
    withContext(Dispatchers.IO) {
        val hash = MessageDigest.getInstance("MD5").digest("$id|${file.size}|${file.modified}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dest = File(DetectionStorage.cacheDir(ctx), "$hash.${file.ext}")
        lock.withLock {
            if (!dest.exists()) {
                val part = File(dest.path + ".part")
                runCatching { fetch(part); part.renameTo(dest) }.onFailure { part.delete() }
            }
        }
        dest.takeIf { it.exists() }?.let { Uri.fromFile(it).toString() }
    }

/** Partage Windows / Samba (SMB 2 et 3). Utilisateur vide = accès invité. */
private class SmbStorage(
    private val ctx: Context,
    private val host: String,
    private val shareName: String,
    private val base: String,
    private val user: String,
    private val password: String,
    private val domain: String,
) : DetectionStorage {
    override val hasModified = true
    private val lock = Mutex()
    private val download = Mutex()
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    private fun open(): DiskShare {
        share?.takeIf { it.isConnected }?.let { return it }
        runCatching { connection?.close() }
        val port = host.substringAfter(':', "").toIntOrNull()
        val name = host.substringBefore(':')
        val conn = if (port != null) client.connect(name, port) else client.connect(name)
        val auth = if (user.isBlank()) AuthenticationContext.guest() else AuthenticationContext(user, password.toCharArray(), domain.ifBlank { null })
        val sess = conn.authenticate(auth)
        val disk = sess.connectShare(shareName) as DiskShare
        connection = conn; session = sess; share = disk
        return disk
    }

    private fun path(vararg parts: String) = DetectionStorage.join(base, *parts).replace('/', '\\')

    private suspend fun <T> withShare(block: (DiskShare) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching { block(open()) }.getOrElse {
                // Connexion perdue (veille du NAS…) : une nouvelle tentative avec une connexion neuve.
                share = null
                block(open())
            }
        }
    }

    override suspend fun list(folder: String): List<StoredFile> = withShare { disk ->
        disk.list(path(folder)).filter { info ->
            info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value == 0L
        }.map { StoredFile(it.fileName, folder, it.lastWriteTime.toEpochMillis(), it.endOfFile, path(folder, it.fileName)) }
    }

    override suspend fun folders(folder: String): List<String> = withShare { disk ->
        disk.list(path(folder)).filter { info ->
            info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L && info.fileName != "." && info.fileName != ".."
        }.map { it.fileName }.sortedBy { it.lowercase() }
    }

    override suspend fun open(file: StoredFile): String? = cached(ctx, download, "smb://$host/$shareName/${file.ref}", file) { dest ->
        withShare { disk ->
            disk.openFile(
                file.ref, setOf(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, null,
            ).use { f -> f.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } } }
        }
    }

    companion object {
        private val client by lazy {
            SMBClient(SmbConfig.builder().withTimeout(20, TimeUnit.SECONDS).withSoTimeout(30, TimeUnit.SECONDS).build())
        }
    }
}

/**
 * Partage NFS v3 (NAS, serveur Linux). Android ne pouvant pas utiliser de port « privilégié », le partage doit
 * accepter les ports non privilégiés (option `insecure`, ou « connexions depuis des ports non privilégiés »).
 */
private class NfsStorage(
    private val ctx: Context,
    private val host: String,
    private val export: String,
    private val base: String,
    private val uid: Int,
    private val gid: Int,
) : DetectionStorage {
    override val hasModified = true
    private val lock = Mutex()
    private val download = Mutex()
    private var nfs: com.emc.ecs.nfsclient.nfs.nfs3.Nfs3? = null

    private fun client(): com.emc.ecs.nfsclient.nfs.nfs3.Nfs3 =
        nfs ?: com.emc.ecs.nfsclient.nfs.nfs3.Nfs3(host, export, com.emc.ecs.nfsclient.rpc.CredentialUnix(uid, gid, null), 3).also { nfs = it }

    private fun path(vararg parts: String) = "/" + DetectionStorage.join(base, *parts)

    private suspend fun <T> withClient(block: (com.emc.ecs.nfsclient.nfs.nfs3.Nfs3) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching { block(client()) }.getOrElse {
                nfs = null
                block(client())
            }
        }
    }

    override suspend fun list(folder: String): List<StoredFile> = withClient { c ->
        com.emc.ecs.nfsclient.nfs.io.Nfs3File(c, path(folder)).listFiles().filter { it.isFile }.map {
            StoredFile(it.name, folder, it.lastModified(), it.length(), path(folder, it.name))
        }
    }

    override suspend fun folders(folder: String): List<String> = withClient { c ->
        com.emc.ecs.nfsclient.nfs.io.Nfs3File(c, path(folder)).listFiles().filter { it.isDirectory }.map { it.name }.sortedBy { it.lowercase() }
    }

    override suspend fun open(file: StoredFile): String? = cached(ctx, download, "nfs://$host$export${file.ref}", file) { dest ->
        withClient { c ->
            com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream(com.emc.ecs.nfsclient.nfs.io.Nfs3File(c, file.ref)).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }
    }
}

/**
 * Dossier de la tablette (stockage interne, carte SD, clé USB…) choisi avec le sélecteur d'Android : l'accès
 * est accordé une fois pour toutes à ce dossier, sans permission générale de stockage.
 */
private class LocalStorage(private val ctx: Context, private val tree: Uri, private val base: String) : DetectionStorage {
    override val hasModified = true

    /** Document du dossier [folder] (sous-dossiers parcourus par leur nom). */
    private fun folderId(folder: String): String? {
        var id = DocumentsContract.getTreeDocumentId(tree)
        DetectionStorage.join(base, folder).split('/').filter { it.isNotBlank() }.forEach { part ->
            id = children(id).firstOrNull { it.second == part && it.third == DocumentsContract.Document.MIME_TYPE_DIR }?.first ?: return null
        }
        return id
    }

    /** (id, nom, type, date) des enfants du document [id]. */
    private fun children(id: String): List<Triple<String, String, String>> = query(id).map { Triple(it[0] as String, it[1] as String, it[2] as String) }

    private fun query(id: String): List<Array<Any?>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = ArrayList<Array<Any?>>()
        ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += arrayOf(c.getString(0), c.getString(1), c.getString(2) ?: "", c.getLong(3), c.getLong(4))
            }
        }
        return out
    }

    override suspend fun list(folder: String): List<StoredFile> = withContext(Dispatchers.IO) {
        val id = folderId(folder) ?: error(App.instance.getString(fr.pergens.hamacs.R.string.det_err_folder, folder))
        query(id).filter { it[2] != DocumentsContract.Document.MIME_TYPE_DIR }.map {
            StoredFile(it[1] as String, folder, (it[3] as Long).takeIf { m -> m > 0 }, it[4] as Long, it[0] as String)
        }
    }

    override suspend fun open(file: StoredFile): String = DocumentsContract.buildDocumentUriUsingTree(tree, file.ref).toString()

    override suspend fun folders(folder: String): List<String> = withContext(Dispatchers.IO) {
        val id = folderId(folder) ?: return@withContext emptyList()
        children(id).filter { it.third == DocumentsContract.Document.MIME_TYPE_DIR }.map { it.second }.sortedBy { it.lowercase() }
    }
}
