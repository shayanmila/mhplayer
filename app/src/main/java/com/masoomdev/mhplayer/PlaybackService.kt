package com.masoomdev.mhplayer

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Single player instance shared by the activity and Android media notification. */
object PlaybackHolder {
    private var instance: ExoPlayer? = null
    private var backgroundEnabled = false
    var refreshNotification: (() -> Unit)? = null
    fun setBackgroundEnabled(enabled: Boolean) {
        if (backgroundEnabled == enabled) return
        backgroundEnabled = enabled
        refreshNotification?.invoke()
    }
    fun isBackgroundEnabled(): Boolean = backgroundEnabled

    fun current(): ExoPlayer? = instance

    fun release() {
        instance?.run {
            stop()
            clearMediaItems()
            release()
        }
        instance = null
    }

    fun player(context: Context): ExoPlayer {
        return instance ?: ExoPlayer.Builder(
            context.applicationContext,
            DefaultRenderersFactory(context.applicationContext).setEnableDecoderFallback(true)
        ).setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true
        ).setHandleAudioBecomingNoisy(true).setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 1_000, 2_000).build()
        ).build().also { instance = it }
    }
}

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var taskRemoved = false
    private val channelId = "mh_playback"
    private val notificationId = 1001
    private fun actionIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = PlaybackHolder.player(this)
        when (intent?.action) {
            "mh.PREV" -> if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0)
            "mh.NEXT" -> if (player.hasNextMediaItem()) player.seekToNextMediaItem()
            "mh.TOGGLE" -> if (player.isPlaying) player.pause() else player.play()
            "mh.STOP" -> {
                player.stop()
                player.clearMediaItems()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        updateNotification()
        return START_NOT_STICKY
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { updateNotification() }
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { updateNotification() }
        override fun onPlaybackStateChanged(playbackState: Int) { updateNotification() }
    }
    private fun updateNotification() {
        val activeSession = session ?: return
        val player = PlaybackHolder.player(this)
        if (!PlaybackHolder.isBackgroundEnabled() || player.mediaItemCount == 0 || !player.isPlaying) {
            // Do not promote a bound-only or paused player to a foreground service.
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        val title = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "MH Player"
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(if (player.isPlaying) "Playing in background" else "Paused")
            .setContentIntent(openIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(player.isPlaying)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, "Previous", actionIntent("mh.PREV", 1))
            .addAction(if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (player.isPlaying) "Pause" else "Play", actionIntent("mh.TOGGLE", 2))
            .addAction(android.R.drawable.ic_media_next, "Next", actionIntent("mh.NEXT", 3))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", actionIntent("mh.STOP", 4))
            .setStyle(androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(activeSession)
                .setShowActionsInCompactView(0, 1, 2))
            .build()
        startForeground(notificationId, notification)
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "Media playback", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession.Builder(this, PlaybackHolder.player(this)).build()
        PlaybackHolder.player(this).addListener(listener)
        PlaybackHolder.refreshNotification = { updateNotification() }
        updateNotification()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        taskRemoved = true
        val player = PlaybackHolder.player(this)
        player.pause()
        player.stop()
        player.clearMediaItems()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        PlaybackHolder.refreshNotification = null
        PlaybackHolder.current()?.removeListener(listener)
        stopForeground(STOP_FOREGROUND_REMOVE)
        session?.release()
        session = null
        // A bound-only service can be destroyed while the Activity is still using
        // the shared player. Only a removed task owns the final player cleanup.
        if (taskRemoved) PlaybackHolder.release()
        super.onDestroy()
    }
}
