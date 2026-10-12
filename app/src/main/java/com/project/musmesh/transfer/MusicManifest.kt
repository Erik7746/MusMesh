package com.project.musmesh.transfer

import com.project.musmesh.protocol.*
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

data class MusicManifest(
    val id: UUID,
    val size: Int,
    val sha256: ByteArray,
    val blockHashes: List<ByteArray>,
    val title: String,
    val artist: String,
    val mime: String,
    val durationMs: Long
) {
    val blockCount get() = (size + ProtocolConstants.BLOCK_SIZE - 1) / ProtocolConstants.BLOCK_SIZE
    fun blockSize(index: Int): Int {
        require(index in 0 until blockCount)
        return minOf(ProtocolConstants.BLOCK_SIZE, size - index * ProtocolConstants.BLOCK_SIZE)
    }
    fun encode(): ByteArray = WireWriter().uuid(id).int(size).raw(sha256).long(durationMs)
        .text(title).text(artist).text(mime, 40).short(blockHashes.size).apply { blockHashes.forEach { raw(it) } }.build()
    companion object {
        fun decode(bytes: ByteArray) = decodePayload(bytes) {
            val id = uuid(); val size = int(); val hash = raw(32); val duration = long()
            val title = text(); val artist = text(); val mime = text(40); val count = short()
            require(size in 1..ProtocolConstants.MAX_FILE_SIZE && duration in 1..86_400_000)
            require(mime in setOf("audio/mpeg", "audio/mp4", "audio/aac"))
            require(count == (size + ProtocolConstants.BLOCK_SIZE - 1) / ProtocolConstants.BLOCK_SIZE)
            MusicManifest(id, size, hash, List(count) { raw(32) }, title, artist, mime, duration)
        }
        fun create(file: File, title: String, artist: String, mime: String, durationMs: Long, id: UUID = UUID.randomUUID()): MusicManifest {
            require(file.length() in 1..ProtocolConstants.MAX_FILE_SIZE.toLong())
            val hashes = mutableListOf<ByteArray>()
            val full = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(ProtocolConstants.BLOCK_SIZE)
                while (true) {
                    var count = 0
                    while (count < buffer.size) { val n = input.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                    if (count == 0) break
                    full.update(buffer, 0, count)
                    hashes += MessageDigest.getInstance("SHA-256").apply { update(buffer, 0, count) }.digest()
                }
            }
            return MusicManifest(id, file.length().toInt(), full.digest(), hashes, title.take(100), artist.take(100), mime, durationMs)
        }
    }
}

object FileVerifier {
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun matches(bytes: ByteArray, expected: ByteArray) = MessageDigest.isEqual(hash(bytes), expected)
    fun verify(file: File, manifest: MusicManifest): Boolean {
        if (file.length() != manifest.size.toLong()) return false
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(ProtocolConstants.BLOCK_SIZE)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        return MessageDigest.isEqual(hash.digest(), manifest.sha256)
    }
}
