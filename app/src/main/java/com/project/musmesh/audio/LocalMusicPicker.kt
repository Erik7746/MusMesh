package com.project.musmesh.audio

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.project.musmesh.protocol.ProtocolConstants
import com.project.musmesh.transfer.MusicManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

data class ImportedTrack(val file: File, val manifest: MusicManifest, val artwork: ByteArray?)

interface AudioNormalizer {
    suspend fun normalize(source: File, targetBitrate: Int): File
}

class TrackMetadataReader {
    fun read(file: File): ImportedTrack {
        val header = ByteArray(12)
        RandomAccessFile(file, "r").use { it.readFully(header) }
        val mp4 = header.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp"
        val id3 = header.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "ID3"
        val frameSync = header[0].toInt() and 255 == 255 && header[1].toInt() and 224 == 224
        require(mp4 || id3 || frameSync) { "Formato no compatible: usa MP3, M4A o AAC" }
        val extractor = MediaExtractor()
        var audioMime: String? = null
        try {
            extractor.setDataSource(file.absolutePath)
            require(extractor.drmInitData == null) { "No se admiten archivos protegidos" }
            require(extractor.trackCount in 1..4)
            repeat(extractor.trackCount) { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                require(!mime.startsWith("video/")) { "Selecciona un archivo de audio" }
                if (mime.startsWith("audio/")) {
                    require(audioMime == null && mime in setOf("audio/mpeg", "audio/mp4a-latm")) { "Codec no compatible" }
                    audioMime = mime
                    extractor.selectTrack(index)
                    require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "No se admiten archivos protegidos" }
                }
            }
        } finally { extractor.release() }
        require(audioMime != null) { "El archivo no contiene audio compatible" }
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(file.absolutePath)
            val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(duration in 1..86_400_000) { "Duración de audio inválida" }
            val title = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().filter { !it.isISOControl() }.take(100).ifBlank { "Archivo local" }
            val artist = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().filter { !it.isISOControl() }.take(100)
            val mime = if (mp4) "audio/mp4" else if (audioMime == "audio/mpeg") "audio/mpeg" else "audio/aac"
            val artwork = metadata.embeddedPicture?.takeIf { it.size <= 256 * 1024 }
            return ImportedTrack(file, MusicManifest.create(file, title, artist, mime, duration), artwork)
        } finally { metadata.release() }
    }
}

class LocalMusicPicker(private val context: Context) {
    class Contract : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }
    suspend fun import(uri: Uri): ImportedTrack = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Selecciona un archivo mediante el selector de Android" }
        val reported = context.contentResolver.getType(uri)
        require(reported == null || reported.startsWith("audio/") || reported == "application/octet-stream") { "El tipo de archivo no es audio" }
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val file = File.createTempFile("music-", ".import", context.cacheDir)
        try {
            require(context.filesDir.usableSpace > ProtocolConstants.MAX_FILE_SIZE * 2L + 1024 * 1024) { "Espacio insuficiente" }
            var total = 0
            requireNotNull(context.contentResolver.openInputStream(uri)) { "No se pudo abrir el archivo" }.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= ProtocolConstants.MAX_FILE_SIZE) { "Archivo demasiado grande: máximo 5 MiB" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(total >= 12) { "El archivo está vacío o no es válido" }
            TrackMetadataReader().read(file)
        } catch (e: Exception) { file.delete(); throw e }
        finally { runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    }
}
