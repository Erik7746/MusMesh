package com.project.musmesh.data

import android.content.Context
import com.project.musmesh.mesh.MeshPeer
import com.project.musmesh.protocol.Ids
import com.project.musmesh.transfer.TransferRepository
import java.io.File
import java.util.UUID

data class SavedRoom(val server: Boolean, val code: String, val room: UUID, val root: Long)

/** Small settings use Android's atomic SharedPreferences; bulk block state uses fsynced journal slots. */
class AppDatabase(context: Context) {
    private val preferences = context.getSharedPreferences("musmesh-state", Context.MODE_PRIVATE)
    val transfers = TransferRepository(File(context.filesDir, "transfers"))
    val nodeId: Long = preferences.getLong("node", 0).takeIf { it > 0 } ?: Ids.node().also { check(preferences.edit().putLong("node", it).commit()) }
    fun room(): SavedRoom? = runCatching {
        val code = preferences.getString("code", null) ?: return null
        SavedRoom(preferences.getBoolean("server", false), code, UUID.fromString(preferences.getString("room", null)), preferences.getLong("root", 0))
    }.getOrNull()
    fun saveRoom(room: SavedRoom) { check(preferences.edit().putBoolean("server", room.server).putString("code", room.code).putString("room", room.room.toString()).putLong("root", room.root).commit()) }
    fun clearRoom() { preferences.edit().remove("room").remove("code").remove("root").remove("peers").remove("transfer").apply() }
    fun savePeers(peers: Collection<MeshPeer>, parents: Map<Long, Long>) {
        preferences.edit().putString("peers", peers.joinToString(";") { "${it.id},${it.session},${it.required},${parents[it.id] ?: 0}" }).apply()
    }
    fun peers(): List<MeshPeer> = preferences.getString("peers", "").orEmpty().split(';').take(8).mapNotNull { row ->
        runCatching { val fields = row.split(','); MeshPeer(fields[0].toLong(), fields[1].toLong(), fields[2].toBoolean()) }.getOrNull()
    }
    fun setTransfer(id: UUID) { preferences.edit().putString("transfer", id.toString()).apply() }
    fun transferId(): UUID? = runCatching { UUID.fromString(preferences.getString("transfer", null)) }.getOrNull()
    var outputOffsetMs: Int
        get() = preferences.getInt("outputOffset", 0)
        set(value) { preferences.edit().putInt("outputOffset", value.coerceIn(-1000, 1000)).apply() }
}
