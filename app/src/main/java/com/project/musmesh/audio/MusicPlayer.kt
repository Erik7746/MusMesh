package com.project.musmesh.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.File

interface MusicPlayer {
    val positionMs: Long
    val playing: Boolean
    val ready: Boolean
    suspend fun prepare(file: File, positionMs: Long = 0)
    fun play()
    fun pause()
    fun seek(positionMs: Long)
    fun speed(value: Float)
    fun stop()
    fun release()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LocalMusicPlayer(context: Context, private val onError: (String) -> Unit) : MusicPlayer {
    init {
        if (!com.project.musmesh.BuildConfig.DEBUG) androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
    }
    val engine: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        setWakeMode(C.WAKE_MODE_LOCAL)
        setHandleAudioBecomingNoisy(true)
    }
    private var prepared: CompletableDeferred<Unit>? = null
    init { engine.addListener(object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_READY) prepared?.complete(Unit) }
        override fun onPlayerError(error: PlaybackException) {
            prepared?.completeExceptionally(IllegalStateException("No se pudo preparar el decoder de audio"))
            onError("El dispositivo no pudo reproducir este archivo")
        }
    }) }
    override val positionMs get() = engine.currentPosition.coerceAtLeast(0)
    override val playing get() = engine.isPlaying
    override val ready get() = engine.playbackState == Player.STATE_READY || engine.playbackState == Player.STATE_ENDED
    override suspend fun prepare(file: File, positionMs: Long) {
        prepared?.cancel(); val waiting = CompletableDeferred<Unit>(); prepared = waiting
        engine.pause(); engine.setPlaybackSpeed(1f)
        val source = ProgressiveMediaSource.Factory(FileDataSource.Factory()).createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))
        engine.setMediaSource(source); engine.seekTo(positionMs); engine.prepare()
        try { withTimeout(15000) { waiting.await() } } finally { if (prepared === waiting) prepared = null }
    }
    override fun play() = engine.play()
    override fun pause() = engine.pause()
    override fun seek(positionMs: Long) = engine.seekTo(positionMs.coerceAtLeast(0))
    override fun speed(value: Float) = engine.setPlaybackSpeed(value.coerceIn(0.995f, 1.005f))
    override fun stop() { engine.pause(); engine.seekTo(0); engine.setPlaybackSpeed(1f) }
    override fun release() { prepared?.cancel(); engine.release() }
}
