package com.project.musmesh

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.project.musmesh.audio.*
import com.project.musmesh.app.MusMeshApplication
import com.project.musmesh.app.Role
import com.project.musmesh.ble.BlePermissions
import com.project.musmesh.service.MusMeshForegroundService
import com.project.musmesh.transfer.TransferRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AndroidFlowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun permissionsAreVersionedAndNetworkAndMicrophoneAreAbsent() {
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BlePermissions.required(30))
        assertEquals(3, BlePermissions.required(31).size)
        assertTrue(LocalMusicPicker.Contract().createIntent(context, arrayOf("audio/mpeg")).getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        @Suppress("DEPRECATION") val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(Manifest.permission.INTERNET in permissions)
        assertFalse(Manifest.permission.RECORD_AUDIO in permissions)
        @Suppress("DEPRECATION") val service = context.packageManager.getServiceInfo(ComponentName(context, MusMeshForegroundService::class.java), 0)
        assertFalse(service.exported)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0)
            assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0)
        }
    }
    @Test fun syntheticAacCopiesThroughContentResolverPersistsAndPreparesPaused() = runBlocking<Unit> {
        val folder = File(context.cacheDir, "instrumentation").apply { mkdirs() }
        val source = File(folder, "synthetic.m4a")
        createSyntheticAac(source)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".testfiles", source)
        val imported = LocalMusicPicker(context).import(uri)
        val repository = TransferRepository(File(folder, "transfer").apply { mkdirs() })
        val stored = repository.importFile(imported.file, imported.manifest)
        assertTrue(stored.verified)
        assertTrue(TransferRepository(File(folder, "transfer")).load(stored.manifest.id)?.verified == true)
        var error: String? = null
        withContext(Dispatchers.Main) {
            val player = LocalMusicPlayer(context) { error = it }
            try {
                player.prepare(repository.file(stored.manifest.id))
                assertTrue(player.ready); assertFalse(player.playing); assertEquals(0, player.positionMs)
            } finally { player.release() }
        }
        assertNull(error)
        repository.remove(stored.manifest.id); imported.file.delete(); source.delete()
    }
    @Test fun foregroundServiceCreatesAndClosesRoom() = runBlocking<Unit> {
        val coordinator = (context.applicationContext as MusMeshApplication).coordinator
        org.junit.Assume.assumeFalse("Conservar una sala preexistente", coordinator.hasSavedRoom())
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (permission in BlePermissions.required()) automation.grantRuntimePermission(context.packageName, permission)
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            withContext(Dispatchers.Main) { ContextCompat.startForegroundService(context, Intent(context, MusMeshForegroundService::class.java).setAction(MusMeshForegroundService.CREATE)) }
            val state = withTimeout(10000) { coordinator.state.first { it.role == Role.SERVER && it.room.admitted } }
            assertEquals(6, state.code.length)
            assertEquals(state.nodeId, state.room.root)
        } finally {
            withContext(Dispatchers.Main) { coordinator.stop(); context.stopService(Intent(context, MusMeshForegroundService::class.java)) }
            activity.close()
        }
    }
    /** Generates 1 s of 440 Hz PCM and encodes it locally. No recordings, downloads or private media. */
    private fun createSyntheticAac(file: File) {
        val rate = 44100; val totalSamples = rate
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
        }
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            var samples = 0; var ended = false; var finished = false; var track = -1
            val info = MediaCodec.BufferInfo(); val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (!finished && android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!ended) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index)).apply { clear(); order(java.nio.ByteOrder.LITTLE_ENDIAN) }
                        val count = minOf(buffer.remaining() / 2, totalSamples - samples)
                        repeat(count) { sample -> buffer.putShort((sin(2 * Math.PI * 440 * (samples + sample) / rate) * 4000).toInt().toShort()) }
                        val pts = samples * 1_000_000L / rate
                        samples += count; ended = samples == totalSamples
                        codec.queueInputBuffer(index, 0, count * 2, pts, if (ended) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                    else -> if (index >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buffer = requireNotNull(codec.getOutputBuffer(index)); buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        finished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
            check(finished) { "El encoder sintético no terminó" }
        } finally { runCatching { codec.stop() }; codec.release(); if (muxerStarted) muxer.stop(); muxer.release() }
    }
}
