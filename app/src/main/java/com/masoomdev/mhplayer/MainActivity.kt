package com.masoomdev.mhplayer

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import android.util.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.media.audiofx.Equalizer
import android.net.Uri
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.util.Rational
import android.provider.MediaStore
import android.view.WindowManager
import android.content.pm.ActivityInfo
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.core.view.WindowCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private val dark = Color(0xFF0D1020)
private val purple = Color(0xFF9B72FF)
data class LocalMedia(val title: String, val uri: Uri, val video: Boolean, val duration: Long, val folder: String, val size: Long, val dateAdded: Long)

// Keep decoded previews bounded; only visible lazy-list items request a thumbnail.
private val thumbnailCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}
private val thumbnailSlots = Semaphore(3)

@Composable
private fun WatchProgress(item: LocalMedia, prefs: android.content.SharedPreferences, modifier: Modifier = Modifier) {
    if (!item.video) return
    val position = prefs.getLong("position_${item.uri}", 0L).coerceAtLeast(0L)
    val total = maxOf(item.duration, prefs.getLong("duration_${item.uri}", 0L))
    if (total <= 0L || position <= 0L) return
    val fraction = (position.toFloat() / total).coerceIn(0f, 1f)
    Column(modifier) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = Color(0xFF31C66B),
            trackColor = Color(0xFFBFC5CF).copy(alpha = 0.45f)
        )
        Text(
            if (fraction >= 0.95f) "Watched" else "${(fraction * 100).toInt()}% watched",
            color = Color(0xFF31C66B), fontSize = 10.sp, maxLines = 1
        )
    }
}

@Composable
private fun MediaThumbnail(item: LocalMedia, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val key = item.uri.toString()
    val bitmap by produceState<Bitmap?>(initialValue = thumbnailCache.get(key), key, item.video) {
        if (!item.video || item.uri.scheme != "content") {
            value = null
        } else {
            value = thumbnailCache.get(key) ?: withContext(Dispatchers.IO) {
                thumbnailSlots.withPermit {
                    currentCoroutineContext().ensureActive()
                    thumbnailCache.get(key) ?: try {
                        val loaded = if (Build.VERSION.SDK_INT >= 29) {
                            context.contentResolver.loadThumbnail(item.uri, Size(160, 108), null)
                        } else {
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(context, item.uri)
                                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    ?.let { Bitmap.createScaledBitmap(it, 160, 108, true).also { small -> if (small !== it) it.recycle() } }
                            } finally { retriever.release() }
                        }
                        loaded?.also { thumbnailCache.put(key, it) }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                }
            }
        }
    }
    if (bitmap != null && bitmap?.isRecycled == false) Image(bitmap!!.asImageBitmap(), contentDescription = "Video preview", modifier = modifier, contentScale = ContentScale.Crop)
    else Icon(if (item.video) Icons.Default.Movie else Icons.Default.MusicNote, null, modifier = modifier, tint = Color(0xFF9B72FF))
}

class MainActivity : ComponentActivity() {
    private val updateNotifier by lazy { UpdateNotifier(this) }
    fun checkForAppUpdate(manual: Boolean = false) = updateNotifier.check(manual)
    var pipActive by mutableStateOf(false)
        private set
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipActive = isInPictureInPictureMode
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        // Keep the fullscreen player's bottom controls unobscured by a forced nav-bar scrim.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent { PlayerApp(this, intent?.data) }
        checkForAppUpdate()
    }
}

private suspend fun scanMedia(activity: ComponentActivity): List<LocalMedia> {
    val output = mutableListOf<LocalMedia>()
    val permissionVideo = Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    val permissionAudio = Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
    for (video in listOf(true, false)) {
        if ((video && !permissionVideo) || (!video && !permissionAudio)) continue
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = if (Build.VERSION.SDK_INT >= 29) arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED) else arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED)
        try {
            activity.contentResolver.query(base, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                val folderIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    output.add(LocalMedia(cursor.getString(nameIndex) ?: "Unknown", ContentUris.withAppendedId(base, cursor.getLong(idIndex)), video, cursor.getLong(durationIndex), if (folderIndex >= 0) (cursor.getString(folderIndex) ?: "Other").trimEnd('/') else "Device storage", if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L, if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L))
                }
            }
        } catch (_: Exception) { }
    }
    return output
}

@Composable
private fun PlayerApp(activity: MainActivity, initialUri: Uri?) {
    val context = LocalContext.current
    val pipActive = activity.pipActive
    val prefs = remember { activity.getSharedPreferences("mh_player", 0) }
    val player = remember { PlaybackHolder.player(context) }
    DisposableEffect(player) { onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
    var splash by remember { mutableStateOf(true) }
    var exitPrompt by remember { mutableStateOf(false) }
    var streamDialog by remember { mutableStateOf(false) }
    var streamUrl by remember { mutableStateOf("") }
    var streamError by remember { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var quickSettingsOpen by remember { mutableStateOf(false) }
    var infoPage by remember { mutableStateOf<String?>(null) }
    var equalizerOpen by remember { mutableStateOf(false) }
    var equalizerEnabled by remember { mutableStateOf(prefs.getBoolean("eq_enabled", false)) }
    var eqLevels by remember { mutableStateOf(List(5) { prefs.getInt("eq_band_$it", 0) }) }
    var eqSession by remember { mutableIntStateOf(C.AUDIO_SESSION_ID_UNSET) }
    var eqAvailable by remember { mutableStateOf(true) }
    var eqBandCount by remember { mutableIntStateOf(5) }
    var eqRange by remember { mutableStateOf(-1500..1500) }
    var eqEffect by remember { mutableStateOf<Equalizer?>(null) }
    var autoHideSeconds by remember { mutableIntStateOf(prefs.getInt("auto_hide", 5)) }
    var resumePlayback by remember { mutableStateOf(prefs.getBoolean("resume", true)) }
    var autoPlay by remember { mutableStateOf(prefs.getBoolean("autoplay", true)) }
    var backgroundPlay by remember { mutableStateOf(prefs.getBoolean("background_play", false)) }

    var brightnessGesture by remember { mutableStateOf(prefs.getBoolean("brightness_gesture", true)) }
    var volumeGesture by remember { mutableStateOf(prefs.getBoolean("volume_gesture", true)) }
    var media by remember { mutableStateOf<List<LocalMedia>>(emptyList()) }
    var selected by remember { mutableStateOf<LocalMedia?>(null) }
    LaunchedEffect(Unit) {
        val current = player.currentMediaItem
        val uri = current?.localConfiguration?.uri
        if (uri != null && player.mediaItemCount > 0) {
            val title = current.mediaMetadata.title?.toString() ?: "Now playing"
            selected = LocalMedia(title, uri, true, 0L, "Now playing", 0L, 0L)
        }
    }
    DisposableEffect(backgroundPlay, selected?.uri) {
        if (backgroundPlay && selected != null) {
            val intent = Intent(context, PlaybackService::class.java)
            val connection = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {}
                override fun onServiceDisconnected(name: android.content.ComponentName?) {}
            }
            val bound = try {
                context.bindService(intent, connection, android.content.Context.BIND_AUTO_CREATE)
            } catch (_: Exception) { false }
            onDispose { if (bound) context.unbindService(connection) }
        } else {
            context.stopService(Intent(context, PlaybackService::class.java))
            onDispose { }
        }
    }
    var showVideo by remember { mutableStateOf(true) }
    var playlistOnly by rememberSaveable { mutableStateOf(false) }
    var playlistUris by remember { mutableStateOf(prefs.getStringSet("my_playlist", emptySet())?.toSet() ?: emptySet<String>()) }
    fun togglePlaylist(item: LocalMedia) {
        playlistUris = if (item.uri.toString() in playlistUris) playlistUris - item.uri.toString() else playlistUris + item.uri.toString()
        prefs.edit().putStringSet("my_playlist", playlistUris.toSet()).apply()
    }
    var currentFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(prefs.getString("sort_mode", "Name") ?: "Name") }
    var sortDescending by remember { mutableStateOf(prefs.getBoolean("sort_desc", false)) }
    var libraryView by remember { mutableStateOf(prefs.getString("library_view", "Folders") ?: "Folders") }
    var gridView by remember { mutableStateOf(prefs.getBoolean("grid_view", false)) }
    var libraryOptions by remember { mutableStateOf(false) }
    var libraryLoading by remember { mutableStateOf(false) }
    var listSettingsOpen by remember { mutableStateOf(false) }
    var markLastPlayed by remember { mutableStateOf(prefs.getBoolean("list_last_played", true)) }
    var scrollToLast by remember { mutableStateOf(prefs.getBoolean("list_scroll_last", false)) }
    var floatingResume by remember { mutableStateOf(prefs.getBoolean("list_floating_resume", false)) }
    var newTagDays by remember { mutableIntStateOf(prefs.getInt("list_new_days", 7)) }
    var includeAndroidMedia by remember { mutableStateOf(prefs.getBoolean("include_android_media", false)) }
    var videoLayout by remember { mutableStateOf(prefs.getString("video_layout", "Classic") ?: "Classic") }
    var audioLayout by remember { mutableStateOf(prefs.getString("audio_layout", "Classic") ?: "Classic") }
    var themeChoice by remember { mutableStateOf(prefs.getString("ui_theme", "White") ?: "White") }
    val lightTheme = themeChoice == "White" || (themeChoice == "System" && !isSystemInDarkTheme())
    val dark = when { lightTheme -> Color(0xFFF8F9FD); themeChoice == "Dark" || themeChoice == "System" -> Color(0xFF111318); else -> Color(0xFF0D1020) }
    val purple = when { lightTheme -> Color(0xFF4259A7); themeChoice == "Dark" || themeChoice == "System" -> Color(0xFF8EA3FF); else -> Color(0xFF9B72FF) }
    val foreground = if (lightTheme) Color(0xFF151827) else Color.White
    val secondaryText = if (lightTheme) Color(0xFF565D70) else Color.LightGray
    SideEffect {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = lightTheme
        controller.isAppearanceLightNavigationBars = lightTheme
    }
    var showHidden by remember { mutableStateOf(prefs.getBoolean("list_show_hidden", false)) }
    var includedFolders by remember { mutableStateOf(prefs.getString("list_folders", "") ?: "") }
    var foldersDialog by remember { mutableStateOf(false) }
    var lastPlayedUri by remember { mutableStateOf(prefs.getString("last_played_uri", "") ?: "") }
    var recentUris by remember { mutableStateOf((prefs.getString("recent_played_uris", "") ?: "").split("|").filter { it.isNotBlank() }) }
    val scope = rememberCoroutineScope()
    var scanJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun refreshMedia() {
        scanJob?.cancel()
        scanJob = scope.launch {
            libraryLoading = true
            try {
                media = withContext(Dispatchers.IO) { scanMedia(activity) }
            } finally {
                libraryLoading = false
            }
        }
    }
    var locked by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsInteraction by remember { mutableIntStateOf(0) }
    var gestureLevel by remember { mutableFloatStateOf(-1f) }
    var gestureBrightness by remember { mutableStateOf(true) }
    var speedMenu by remember { mutableStateOf(false) }
    var trackDialog by remember { mutableStateOf(false) }
    var subtitleDialog by remember { mutableStateOf(false) }
    var trackRevision by remember { mutableIntStateOf(0) }
    var resizeChoice by remember { mutableIntStateOf(prefs.getInt("ratio_mode", 0)) }
    var ratioMenu by remember { mutableStateOf(false) }
    var rememberRatio by remember { mutableStateOf(prefs.getBoolean("remember_ratio", false)) }
    var overlayOpacity by remember { mutableFloatStateOf(prefs.getFloat("overlay_opacity", 1f)) }
    var playerMoreMenu by remember { mutableStateOf(false) }
    var expandedControls by remember { mutableStateOf(false) }
    var playerActionsMenu by remember { mutableStateOf(false) }
    var orientationLocked by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var sleepSeconds by remember { mutableStateOf("300") }
    var sleepMenu by remember { mutableStateOf(false) }
    var sleepDeadline by remember { mutableLongStateOf(0L) }
    var folderAction by remember { mutableStateOf<String?>(null) }
    var folderRename by remember { mutableStateOf("") }
    var folderRenameDialog by remember { mutableStateOf(false) }
    var folderDeleteDialog by remember { mutableStateOf(false) }
    var folderAliases by remember { mutableStateOf(emptyMap<String, String>()) }
    var showFolderActions by remember { mutableStateOf(false) }
    LaunchedEffect(sleepDeadline) {
        while (sleepDeadline > 0L) {
            val remaining = sleepDeadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0L) { player.pause(); sleepDeadline = 0L; break }
            delay(minOf(remaining, 1000L))
        }
    }
    if (folderRenameDialog) AlertDialog(onDismissRequest = { folderRenameDialog = false }, title = { Text("Rename folder") }, text = { Text("Android MediaStore folders cannot be renamed safely without document-tree permission. Use your file manager to rename this folder.") }, confirmButton = { TextButton(onClick = { folderRenameDialog = false }) { Text("OK") } })
    if (folderDeleteDialog) AlertDialog(onDismissRequest = { folderDeleteDialog = false }, title = { Text("Delete folder") }, text = { Text("For safety, delete the folder using your file manager. No files have been deleted.") }, confirmButton = { TextButton(onClick = { folderDeleteDialog = false }) { Text("OK") } })

    var pinchZoom by remember { mutableFloatStateOf(1f) }
    var repeatOne by remember { mutableStateOf(false) }
    var seekSeconds by remember { mutableLongStateOf(prefs.getLong("seek_seconds", 10L)) }
    DisposableEffect(selected != null, pipActive) {
        val window = activity.window
        if (selected != null && !pipActive) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN) }
    }
    var holdSpeedActive by remember { mutableStateOf(false) }
    var seekFeedback by remember { mutableStateOf("") }
    LaunchedEffect(selected?.uri, controlsVisible, controlsInteraction, locked, trackDialog, subtitleDialog, speedMenu, quickSettingsOpen, autoHideSeconds) {
        if (selected != null && controlsVisible && !locked && !trackDialog && !subtitleDialog && !speedMenu && !quickSettingsOpen) {
            delay(autoHideSeconds * 1000L)
            controlsVisible = false
        }
    }
    var lastRecoveryUri by remember { mutableStateOf<String?>(null) }
    val latestMedia by rememberUpdatedState(media)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { trackRevision++ }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val uri = mediaItem?.localConfiguration?.uri ?: return
                if (selected?.uri != uri) selected = latestMedia.firstOrNull { it.uri == uri }
                    ?: player.currentMediaItem?.let { current ->
                        LocalMedia(current.mediaMetadata.title?.toString() ?: uri.lastPathSegment ?: "Media", uri, selected?.video ?: true, 0L, selected?.folder ?: "Opened files", 0L, 0L)
                    }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                trackRevision++
                if (playbackState == Player.STATE_ENDED && playlistOnly && selected != null && !player.hasNextMediaItem()) {
                    val queue = media.filter { it.uri.toString() in playlistUris && it.video == showVideo }
                    val index = queue.indexOfFirst { it.uri == selected?.uri }
                    if (index >= 0 && index + 1 < queue.size) selected = queue[index + 1]
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val uri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
                val recoverable = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                if (recoverable && lastRecoveryUri != uri) {
                    lastRecoveryUri = uri
                    player.prepare()
                }
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (selected?.video == true && videoSize.width > 0 && videoSize.height > 0) {
                    val displayWidth = videoSize.width * videoSize.pixelWidthHeightRatio
                    val desired = if (displayWidth > videoSize.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    if (activity.requestedOrientation != desired) activity.requestedOrientation = desired
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    DisposableEffect(eqSession, equalizerEnabled) {
        var effect: Equalizer? = null
        if (equalizerEnabled && eqSession != C.AUDIO_SESSION_ID_UNSET) {
            try {
                effect = Equalizer(0, eqSession)
                eqBandCount = effect.numberOfBands.toInt()
                val range = effect.bandLevelRange
                eqRange = range[0].toInt()..range[1].toInt()
                effect.enabled = true
                eqAvailable = true
                eqEffect = effect
            } catch (_: Exception) {
                eqAvailable = false
                eqEffect = null
                effect?.release()
                effect = null
            }
        } else eqEffect = null
        onDispose {
            if (eqEffect === effect) eqEffect = null
            effect?.release()
        }
    }
    LaunchedEffect(eqEffect, eqLevels) {
        val effect = eqEffect ?: return@LaunchedEffect
        try {
            for (band in 0 until effect.numberOfBands.toInt()) {
                val value = eqLevels.getOrElse(band) { 0 }.coerceIn(eqRange.first, eqRange.last)
                effect.setBandLevel(band.toShort(), value.toShort())
            }
        } catch (_: Exception) { eqAvailable = false }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player, backgroundPlay, pipActive) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !backgroundPlay && !pipActive && !activity.isInPictureInPictureMode) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selected = LocalMedia(uri.lastPathSegment ?: "Opened file", uri, true, 0L, "Opened files", 0L, 0L)
    }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val current = selected
            if (current != null) {
                val position = player.currentPosition
                val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
                    .setMimeType(if (uri.toString().lowercase().endsWith(".vtt")) "text/vtt" else "application/x-subrip")
                    .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                    .build()
                player.setMediaItem(MediaItem.Builder().setUri(current.uri).setSubtitleConfigurations(listOf(subtitle)).build())
                player.prepare(); player.seekTo(position); player.play()
            }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshMedia() }
    LaunchedEffect(Unit) {
        splash = false
        val needed = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = needed.filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray()) else refreshMedia()
        if (initialUri != null) selected = LocalMedia("Opened file", initialUri, true, 0L, "Opened files", 0L, 0L)
    }
    LaunchedEffect(selected?.uri) {
        val item = selected ?: return@LaunchedEffect
        if (lastRecoveryUri != item.uri.toString()) lastRecoveryUri = null
        pinchZoom = 1f
        if (!rememberRatio) resizeChoice = 0
        holdSpeedActive = false
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        lastPlayedUri = item.uri.toString()
        recentUris = (listOf(lastPlayedUri) + recentUris.filter { it != lastPlayedUri }).take(12)
        prefs.edit().putString("last_played_uri", lastPlayedUri).putString("recent_played_uris", recentUris.joinToString("|")).apply()
        val alreadyPlaying = player.currentMediaItem?.localConfiguration?.uri == item.uri && player.mediaItemCount > 0
        if (!alreadyPlaying) {
            val queue = if (playlistOnly) media.filter { it.uri.toString() in playlistUris && it.video == showVideo }
                .let { if (it.any { entry -> entry.uri == item.uri }) it else listOf(item) }
                else media.filter { it.folder == item.folder && it.video == item.video }
                    .let { folderItems ->
                        if (folderItems.any { entry -> entry.uri == item.uri }) folderItems else listOf(item)
                    }
            val items = queue.map { entry ->
                MediaItem.Builder().setUri(entry.uri)
                    .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(entry.title).build())
                    .build()
            }
            val index = queue.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
            val saved = if (resumePlayback) prefs.getLong("position_${item.uri}", 0L).coerceAtLeast(0L) else 0L
            player.setMediaItems(items, index, saved)
            player.playWhenReady = autoPlay
            player.prepare()
        }
        locked = false
        controlsVisible = true
        controlsInteraction++
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    LaunchedEffect(selected?.uri) {
        val current = selected ?: return@LaunchedEffect
        var lastSavedAt = 0L
        while (selected?.uri == current.uri) {
            val session = player.audioSessionId
            if (eqSession != session) eqSession = session
            val nextProgress = player.currentPosition.coerceAtLeast(0L)
            val nextDuration = player.duration.coerceAtLeast(0L)
            val nextPlaying = player.isPlaying
            if (progress != nextProgress) progress = nextProgress
            if (duration != nextDuration) duration = nextDuration
            if (playing != nextPlaying) playing = nextPlaying
            val now = android.os.SystemClock.elapsedRealtime()
            if (nextProgress > 0 && now - lastSavedAt >= 5000L) {
                prefs.edit().putLong("position_${current.uri}", nextProgress).putLong("duration_${current.uri}", nextDuration).apply()
                lastSavedAt = now
            }
            delay(if (nextPlaying) 1000L else 1500L)
        }
    }
    fun closePlayer() {
        selected?.let { prefs.edit().putLong("position_${it.uri}", player.currentPosition.coerceAtLeast(0L)).putLong("duration_${it.uri}", player.duration.coerceAtLeast(0L)).apply() }
        player.pause(); player.clearMediaItems(); if (holdSpeedActive) player.setPlaybackSpeed(playbackSpeed); holdSpeedActive = false; selected = null; locked = false; pinchZoom = 1f; activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    androidx.compose.runtime.DisposableEffect(selected, pipActive) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (selected != null && !pipActive) {
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler(enabled = !pipActive) { if (quickSettingsOpen) quickSettingsOpen = false else if (exitPrompt) exitPrompt = false else if (selected != null) { if (locked) locked = false else closePlayer() } else if (infoPage != null) infoPage = null else if (settingsOpen) settingsOpen = false else if (currentFolder != null) currentFolder = null else exitPrompt = true }
    if (streamDialog) AlertDialog(
        onDismissRequest = { streamDialog = false },
        title = { Text("Network stream") },
        text = {
            Column {
                Text("Paste a direct video or audio URL (HTTPS). MP4, MP3, HLS (.m3u8) and DASH (.mpd) supported.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = streamUrl,
                    onValueChange = { streamUrl = it; streamError = "" },
                    label = { Text("Stream URL") },
                    singleLine = true
                )
                if (streamError.isNotEmpty()) Text(streamError, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val url = streamUrl.trim()
                val parsed = Uri.parse(url)
                if (parsed.scheme?.lowercase() != "https" || parsed.host.isNullOrBlank()) {
                    streamError = "Enter a valid HTTPS stream URL"
                } else {
                    selected = LocalMedia(parsed.lastPathSegment?.takeIf { it.isNotBlank() } ?: "Network stream", parsed, true, 0L, "Network", 0L, 0L)
                    streamDialog = false
                }
            }) { Text("Play") }
        },
        dismissButton = { TextButton(onClick = { streamDialog = false }) { Text("Cancel") } }
    )
    if (trackDialog || subtitleDialog) {
        val isAudio = trackDialog
        val trackType = if (isAudio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        val revision = trackRevision
        val groups = player.currentTracks.groups.filter { it.type == trackType }
        val tracksLoading = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE
        AlertDialog(
            onDismissRequest = { trackDialog = false; subtitleDialog = false },
            title = { Text(if (isAudio) "Audio track selection" else "Subtitle selection") },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    TextButton(onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(trackType).setTrackTypeDisabled(trackType, !isAudio).build()
                        trackDialog = false; subtitleDialog = false
                    }) { Text(if (isAudio) "Automatic audio" else "Subtitles off") }
                    groups.forEachIndexed { groupNumber, group ->
                        for (index in 0 until group.length) {
                            val format = group.getTrackFormat(index)
                            val supported = group.isTrackSupported(index)
                            val languageCode = format.language?.takeUnless { it.isBlank() || it == "und" }
                            val languageName = languageCode?.let { code ->
                                java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH)
                                    .takeUnless { it.isBlank() || it.equals(code, ignoreCase = true) }
                            }
                            val trackName = format.label?.takeUnless { it.isBlank() }
                                ?: languageName?.replaceFirstChar { it.uppercase() }
                                ?: (if (isAudio) "Audio" else "Subtitle") + " ${groupNumber + 1}.${index + 1}"
                            val codec = format.sampleMimeType?.substringAfter('/') ?: "unknown codec"
                            val detail = listOfNotNull(
                                languageCode?.uppercase(),
                                if (isAudio) codec.uppercase() else null,
                                if (!supported) "Unsupported codec" else null
                            ).joinToString(" • ")
                            TextButton(
                                enabled = supported,
                                onClick = {
                                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                        .clearOverridesOfType(trackType)
                                        .setTrackTypeDisabled(trackType, false)
                                        .addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(index)))
                                        .build()
                                    trackDialog = false; subtitleDialog = false
                                }
                            ) {
                                Column {
                                    Text((if (group.isTrackSelected(index)) "✓ " else "") + trackName)
                                    if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    if (groups.isEmpty()) Text(if (tracksLoading) "Loading embedded tracks..." else "No embedded tracks found in this file")
                    if (groups.isNotEmpty() && groups.none { group -> (0 until group.length).any { group.isTrackSupported(it) } })
                        Text("This file contains tracks, but their codecs are not supported by this device.")
                    if (revision < 0) Text("")
                }
            },
            confirmButton = { TextButton(onClick = { trackDialog = false; subtitleDialog = false }) { Text("Close") } }
        )
    }
    if (equalizerOpen) AlertDialog(
        onDismissRequest = { equalizerOpen = false },
        title = { Text("Audio Equalizer") },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
                Text("Applies to video and audio played in MH Player")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Flat", "Bass", "Treble").forEach { preset ->
                        TextButton(onClick = {
                            val n = eqBandCount.coerceAtLeast(1)
                            val max = eqRange.last.coerceAtLeast(0)
                            val next = List(n) { index ->
                                when (preset) {
                                    "Bass" -> if (index < n / 2) max / 2 else 0
                                    "Treble" -> if (index >= n / 2) max / 2 else 0
                                    else -> 0
                                }
                            }
                            eqLevels = next
                            next.forEachIndexed { i, v -> prefs.edit().putInt("eq_band_$i", v).apply() }
                        }) { Text(preset) }
                    }
                }
                for (band in 0 until eqBandCount.coerceIn(1, 10)) {
                    val level = eqLevels.getOrElse(band) { 0 }
                    val hz = try { eqEffect?.getCenterFreq(band.toShort())?.div(1000) } catch (_: Exception) { null }
                    Text(if (hz != null) "$hz Hz: ${level / 100} dB" else "Band ${band + 1}: ${level / 100} dB")
                    Slider(
                        value = level.toFloat().coerceIn(eqRange.first.toFloat(), eqRange.last.toFloat()),
                        onValueChange = { value ->
                            val next = eqLevels.toMutableList()
                            while (next.size <= band) next.add(0)
                            next[band] = value.toInt()
                            eqLevels = next
                            prefs.edit().putInt("eq_band_$band", value.toInt()).apply()
                        },
                        valueRange = eqRange.first.toFloat()..eqRange.last.toFloat()
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { equalizerOpen = false }) { Text("Done") } }
    )
    if (exitPrompt) AlertDialog(onDismissRequest = { exitPrompt = false }, title = { Text("Exit MH Player?") }, text = { Text("Are you sure you want to close MH Player?") }, confirmButton = { TextButton(onClick = { activity.finish() }) { Text("Exit app") } }, dismissButton = { TextButton(onClick = { exitPrompt = false }) { Text("Stay in app") } })
    // Keep the active player mounted while changing its settings.
    MaterialTheme(colorScheme = if (lightTheme)
        lightColorScheme(primary = purple, background = dark, surface = Color.White, onBackground = foreground, onSurface = foreground)
        else darkColorScheme(primary = purple, background = dark,
            surface = if (themeChoice == "Dark") Color(0xFF20232A) else Color(0xFF1A2034))) {
        Box(Modifier.fillMaxSize().then(if (selected == null || pipActive) Modifier.safeDrawingPadding() else Modifier)) {
        if (quickSettingsOpen && selected != null && !pipActive) {
            AlertDialog(
                onDismissRequest = { quickSettingsOpen = false },
                title = { Column { Text("Player settings", fontSize = 22.sp); Text("Customize without leaving playback", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                text = {
                    Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("PLAYER DESIGN", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                        Text(if (selected?.video == true) "Video layout" else "Audio layout")
                        val options = if (selected?.video == true) listOf("Classic", "Modern", "Cinema")
                            else listOf("Classic", "Modern", "Studio")
                        options.forEach { option ->
                            val active = if (selected?.video == true) videoLayout == option else audioLayout == option
                            Row(Modifier.fillMaxWidth().clickable {
                                if (selected?.video == true) {
                                    videoLayout = option
                                    prefs.edit().putString("video_layout", option).apply()
                                } else {
                                    audioLayout = option
                                    prefs.edit().putString("audio_layout", option).apply()
                                }
                                controlsVisible = true
                                controlsInteraction++
                            }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = active, onClick = null)
                                Text(option)
                            }
                        }
                        HorizontalDivider()
                        Text("AUDIO", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                        SettingsToggle("Equalizer", "Apply audio effects during playback", equalizerEnabled) {
                            equalizerEnabled = it
                            prefs.edit().putBoolean("eq_enabled", it).apply()
                        }
                        TextButton(onClick = {
                            quickSettingsOpen = false
                            equalizerOpen = true
                        }) { Text("Equalizer bands & presets") }
                        if (equalizerEnabled && !eqAvailable) {
                            Text("Equalizer is unavailable on this device.", color = MaterialTheme.colorScheme.error)
                        }
                        HorizontalDivider()
                        Text("PLAYBACK", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                        SettingsToggle("Resume playback", "Remember playback position", resumePlayback) {
                            resumePlayback = it; prefs.edit().putBoolean("resume", it).apply()
                        }
                        SettingsToggle("Background playback", "Continue audio outside the app", backgroundPlay) {
                            backgroundPlay = it; prefs.edit().putBoolean("background_play", it).apply()
                        }
                        HorizontalDivider()
                        Text("THEME", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("System", "White", "Dark", "Purple").forEach { option ->
                                FilterChip(selected = themeChoice == option, onClick = {
                                    themeChoice = option; prefs.edit().putString("ui_theme", option).apply()
                                }, label = { Text(option) })
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { quickSettingsOpen = false; controlsVisible = true; controlsInteraction++ }) {
                        Text("Done")
                    }
                }
            )
        }
        if (splash) {
            Box(Modifier.fillMaxSize().background(dark), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.PlayCircle, null, Modifier.size(86.dp), tint = purple); Spacer(Modifier.height(12.dp)); Text("MH PLAYER", fontSize = 32.sp, color = foreground); Text("Your media. Your device.", color = secondaryText) }
            }
        } else if (infoPage != null) {
            Column(Modifier.fillMaxSize().background(dark).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { infoPage = null }) { Icon(Icons.Default.ArrowBack, "Back", tint = foreground) }
                    Text(infoPage ?: "", fontSize = 24.sp, color = foreground)
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text(
                        when (infoPage) {
                            "About" -> """MH Player
Version ${context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown"} (Build ${context.packageManager.getPackageInfo(context.packageName, 0).versionCode})

Developed by MasoomDev.

An audio and video player for local media files and supported network streams.

Features include folders, playlists, playback resume, audio tracks, external subtitles, equalizer, gestures, Picture-in-Picture and optional background playback.

Updates are checked through Google Play only. When an update is available, MH Player may show an in-app prompt and a notification if allowed.

No account or sign-in is required."""
                            "Privacy Policy" -> """MH Player Privacy Policy
Last updated: 27 October 2026

MH Player is a media player developed by MasoomDev for local media and user-provided network streams.

Media access: With your permission, MH Player reads video and audio files on your device to show your media library and play selected files. Local media is not uploaded to the developer by the app.

Network streams: When you enter a stream URL, MH Player connects to that provider to play media. The provider may receive your IP address and standard request information.

Local data: Playback positions, equalizer settings and player preferences are saved locally on your device. You can remove this app data using Android settings or by uninstalling the app.

Data collection: The current app does not contain advertising SDKs, analytics SDKs, account registration or cloud syncing. The app does not intentionally transmit personal data to the developer.

Permissions: Audio and video access permissions are used for browsing and playing local files. Android audio settings access is used for audio effects. Notification permission, if granted, is used for playback controls and app update alerts.

App updates: MH Player checks update availability through Google Play services, which may communicate with Google under its privacy practices. GitHub is not queried for updates.

Children: MH Player is a general-purpose local media player and does not provide child-directed online services.

Changes: This policy may be updated if app functionality changes. A public version will be made available on the developer's website before publication.

Contact: Please use the support contact provided on the developer's website or Google Play listing."""
                            "Terms & Conditions" -> """MH Player Terms & Conditions
Last updated: 27 October 2026

MH Player is provided as a media playback utility for local files and supported user-provided streams.

You are responsible for ensuring that you have the right to access and play your media files. MH Player does not supply, host or distribute video or audio content.

Media format support depends on your device and Android decoding capabilities. Playback features and audio effects may vary by device.

Updates are provided through Google Play. Users can choose to update or defer.

The app is provided as-is to the extent permitted by applicable law. Nothing in these terms excludes rights that cannot legally be excluded.

For support, use the contact details on the developer website or Google Play listing."""
                            else -> """Open Source Licenses

MH Player uses open-source components including:

AndroidX Media3 / ExoPlayer — Apache License 2.0
AndroidX libraries and Jetpack Compose — Apache License 2.0
Kotlin — Apache License 2.0
Google Play In-App Updates SDK — Google Play SDK terms

Copyright and license notices for dependencies remain with their respective owners.

Full license texts and dependency notices should be made available on the developer website or in the release package before publication."""
                        },
                        color = foreground,
                        lineHeight = 24.sp,
                        modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
                    )
                }
            }
        } else if (listSettingsOpen) {
            Column(Modifier.fillMaxSize().background(dark).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { listSettingsOpen = false }) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = foreground)
                    }
                    Text("List", fontSize = 26.sp, color = foreground)
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text("APPEARANCE", color = purple, modifier = Modifier.padding(top = 20.dp))
                    SettingsToggle("Last media in each folder", "Mark the last played media in the library.", markLastPlayed) {
                        markLastPlayed = it; prefs.edit().putBoolean("list_last_played", it).apply()
                    }
                    SettingsToggle("Scroll down to last media", "Auto-scroll to the last played item when opening its folder.", scrollToLast) {
                        scrollToLast = it; prefs.edit().putBoolean("list_scroll_last", it).apply()
                    }
                    SettingsToggle("Floating button", "Show a shortcut to resume the last played media in the library.", floatingResume) {
                        floatingResume = it; prefs.edit().putBoolean("list_floating_resume", it).apply()
                    }
                    Text("Period tagged as NEW", color = foreground, modifier = Modifier.padding(top = 16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 1, 7, 30).forEach { days ->
                            FilterChip(selected = newTagDays == days, onClick = {
                                newTagDays = days; prefs.edit().putInt("list_new_days", days).apply()
                            }, label = { Text(if (days == 0) "Off" else "${days}d") })
                        }
                    }
                    Text("SCAN", color = purple, modifier = Modifier.padding(top = 24.dp))
                    Row(Modifier.fillMaxWidth().clickable { foldersDialog = true }.padding(vertical = 16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Folders", color = foreground)
                            Text("Choose which media folders appear in the library.", color = secondaryText, fontSize = 12.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = purple)
                    }
                    SettingsToggle("Include Android app media",
                        "Off by default. Hide WhatsApp and other app media under Android/ from the library.",
                        includeAndroidMedia) {
                        includeAndroidMedia = it
                        prefs.edit().putBoolean("include_android_media", it).apply()
                    }
                    SettingsToggle("Show hidden files and folders",
                        "Include media with names or folder paths starting with a dot, if Android exposes them.",
                        showHidden) {
                        showHidden = it; prefs.edit().putBoolean("list_show_hidden", it).apply()
                    }
                    Text("Android MediaStore normally excludes .nomedia folders. System media scanning rules still apply.",
                        color = secondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
            if (foldersDialog) {
                var folderSelection by remember { mutableStateOf(includedFolders) }
                AlertDialog(onDismissRequest = { foldersDialog = false },
                    title = { Text("Included folders") },
                    text = {
                        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                            Text("Leave all selected to scan every available folder.")
                            val options = media.map { it.folder }.distinct().sorted()
                            options.forEach { folder ->
                                val active = folderSelection.isBlank() || folder in folderSelection.split("|")
                                Row(Modifier.fillMaxWidth().clickable {
                                    val selectedFolders = if (folderSelection.isBlank()) options.toMutableSet()
                                        else folderSelection.split("|").filter { it.isNotBlank() }.toMutableSet()
                                    if (active) selectedFolders.remove(folder) else selectedFolders.add(folder)
                                    folderSelection = selectedFolders.joinToString("|")
                                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = active, onCheckedChange = null)
                                    Text(folder, color = Color.DarkGray, fontSize = 12.sp)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            includedFolders = folderSelection
                            prefs.edit().putString("list_folders", includedFolders).apply()
                            foldersDialog = false
                        }) { Text("Done") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            includedFolders = ""
                            prefs.edit().putString("list_folders", "").apply()
                            foldersDialog = false
                        }) { Text("All folders") }
                    })
            }
        } else if (settingsOpen) {
            val cardColor = MaterialTheme.colorScheme.surface
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Column(Modifier.fillMaxSize().background(dark)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { settingsOpen = false }) { Icon(Icons.Default.ArrowBack, "Back", tint = foreground) }
                    Text("Profile & Settings", fontSize = 23.sp, color = foreground)
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                    Card(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = cardColor)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = purple.copy(alpha = .15f)) {
                                Icon(Icons.Default.PlayCircle, null, Modifier.padding(14.dp).size(26.dp), tint = purple)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("MH Player", color = foreground, fontSize = 19.sp)
                                Text("Your media. Your device.", color = muted, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    SettingsSectionTitle("PLAYBACK", muted)
                    Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = cardColor)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Video controls opacity: ${(overlayOpacity * 100).toInt()}%", color = foreground)
                            Slider(value = overlayOpacity, onValueChange = { overlayOpacity = it; prefs.edit().putFloat("overlay_opacity", it).apply() }, valueRange = .1f..1f)
                            HorizontalDivider()
                            Text("Controls auto-hide: $autoHideSeconds seconds", color = foreground)
                            Slider(value = autoHideSeconds.toFloat(), onValueChange = { autoHideSeconds = it.toInt().coerceIn(3, 15); prefs.edit().putInt("auto_hide", autoHideSeconds).apply() }, valueRange = 3f..15f, steps = 11)
                            SettingsToggle("Resume playback", "Continue from saved position", resumePlayback) { resumePlayback = it; prefs.edit().putBoolean("resume", it).apply() }
                            SettingsToggle("Auto-play", "Play immediately when opening media", autoPlay) { autoPlay = it; prefs.edit().putBoolean("autoplay", it).apply() }
                            SettingsToggle("Background playback", "Continue audio outside the app", backgroundPlay) { backgroundPlay = it; prefs.edit().putBoolean("background_play", it).apply() }
                            Text("Skip duration", color = foreground)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(5L, 10L, 30L).forEach { seconds ->
                                    FilterChip(selected = seekSeconds == seconds, onClick = { seekSeconds = seconds; prefs.edit().putLong("seek_seconds", seconds).apply() }, label = { Text("${seconds}s") })
                                }
                            }
                        }
                    }
                    SettingsSectionTitle("AUDIO & GESTURES", muted)
                    Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = cardColor)) {
                        Column(Modifier.padding(12.dp)) {
                            SettingsToggle("Equalizer", "Audio effects for music and video", equalizerEnabled) { equalizerEnabled = it; prefs.edit().putBoolean("eq_enabled", it).apply() }
                            SettingsNavRow(Icons.Default.Equalizer, "Equalizer bands & presets", "Adjust audio effects", purple) { equalizerOpen = true }
                            if (equalizerEnabled && !eqAvailable) Text("Equalizer unavailable on this device", color = MaterialTheme.colorScheme.error)
                            HorizontalDivider()
                            SettingsToggle("Brightness swipe", "Left-side vertical gesture", brightnessGesture) { brightnessGesture = it; prefs.edit().putBoolean("brightness_gesture", it).apply() }
                            SettingsToggle("Volume swipe", "Right-side vertical gesture", volumeGesture) { volumeGesture = it; prefs.edit().putBoolean("volume_gesture", it).apply() }
                        }
                    }
                    SettingsSectionTitle("APPEARANCE", muted)
                    Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = cardColor)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Theme", color = foreground)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("System", "White", "Dark", "Purple").forEach { option ->
                                    FilterChip(selected = themeChoice == option, onClick = { themeChoice = option; prefs.edit().putString("ui_theme", option).apply() }, label = { Text(option) })
                                }
                            }
                            Text("Video player layout", color = foreground)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Classic", "Modern", "Cinema").forEach { option ->
                                    FilterChip(selected = videoLayout == option, onClick = { videoLayout = option; prefs.edit().putString("video_layout", option).apply() }, label = { Text(option) })
                                }
                            }
                            Text("Audio player layout", color = foreground)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Classic", "Modern", "Studio").forEach { option ->
                                    FilterChip(selected = audioLayout == option, onClick = { audioLayout = option; prefs.edit().putString("audio_layout", option).apply() }, label = { Text(option) })
                                }
                            }
                        }
                    }
                    SettingsSectionTitle("LIBRARY", muted)
                    Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = cardColor)) {
                        SettingsNavRow(Icons.Default.Folder, "Library & folder settings", "Folders, hidden media and list options", purple) { listSettingsOpen = true }
                    }
                    SettingsSectionTitle("APP & SUPPORT", muted)
                    Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = cardColor)) {
                        SettingsNavRow(Icons.Default.SystemUpdate, "Check for Updates", "Check Google Play for a new version", purple) {
                            activity.checkForAppUpdate(manual = true)
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                        SettingsNavRow(Icons.Default.Info, "About MH Player", "Version and developer information", purple) { infoPage = "About" }
                        SettingsNavRow(Icons.Default.PrivacyTip, "Privacy Policy", "How MH Player handles your data", purple) {
                            infoPage = "Privacy Policy"
                        }
                        SettingsNavRow(Icons.Default.Description, "Terms & Conditions", null, purple) { infoPage = "Terms & Conditions" }
                        SettingsNavRow(Icons.Default.Code, "Open Source Licenses", null, purple) { infoPage = "Open Source Licenses" }
                        SettingsNavRow(Icons.Default.Language, "MH Player website", "MasoomDev", purple) {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://masoomdev-website.vercel.app/apps/mh-player/"))) } catch (_: Exception) {}
                        }
                    }
                    Text("MH Player • Developed by MasoomDev", modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp), color = muted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        } else if (selected != null) {
            val audioMode = selected?.video == false
            val activeLayout = if (audioMode) audioLayout else videoLayout
            val overlayText = Color.White
            // Keep the audio playback surface dark so white transport controls remain visible
            // even when the app's library theme is light.
            Box(Modifier.fillMaxSize().background(if (audioMode) Color(0xFF090D16) else Color.Black)) {
                Box(Modifier.fillMaxSize()) {
                    if (!audioMode) {
                        AndroidView(factory = { ctx -> (android.view.LayoutInflater.from(ctx).inflate(com.masoomdev.mhplayer.R.layout.mh_video_view, null) as PlayerView).apply { this.player = player; useController = false } }, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = pinchZoom; scaleY = pinchZoom }, update = { it.resizeMode = if (resizeChoice in 0..4) resizeChoice else 3;  })
                    } else {
                        // Default animated music artwork for audio without embedded cover art.
                        // Animation pauses with playback; video rendering remains untouched.
                        val audioArtworkTransition = rememberInfiniteTransition(label = "audioArtwork")
                        val audioArtworkPulse by audioArtworkTransition.animateFloat(
                            initialValue = 0.94f, targetValue = 1.06f,
                            animationSpec = infiniteRepeatable(animation = tween(1300, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
                            label = "artworkPulse"
                        )
                        val artworkScale = if (playing) audioArtworkPulse else 1f
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 85.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (activeLayout == "Studio") {
                                Text("STUDIO", color = purple, fontSize = 14.sp)
                                Spacer(Modifier.height(18.dp))
                                Row(Modifier.fillMaxWidth().height(100.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                    val bars = listOf(20, 48, 32, 78, 56, 94, 45, 68, 35, 82, 52, 25, 60, 40, 72)
                                    bars.forEach { height ->
                                        Box(Modifier.width(6.dp).height((height * artworkScale).dp).background(Color(0xFF8EA3FF), androidx.compose.foundation.shape.RoundedCornerShape(5.dp)))
                                    }
                                }
                            } else {
                                Surface(
                                    modifier = Modifier.size(if (activeLayout == "Modern") 200.dp else 140.dp).graphicsLayer { scaleX = artworkScale; scaleY = artworkScale },
                                    color = Color(0xFF172238),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(if (activeLayout == "Modern") 32.dp else 18.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(if (activeLayout == "Modern") Icons.Default.Album else Icons.Default.MusicNote,
                                            null, Modifier.size(90.dp), tint = Color(0xFF8EA3FF))
                                    }
                                }
                            }
                            Spacer(Modifier.height(22.dp))
                            Text(selected?.title ?: "Audio", color = Color.White, fontSize = if (activeLayout == "Modern") 24.sp else 20.sp, maxLines = 2)
                            Text(if (activeLayout == "Studio") "Audio playback • Equalizer available in Settings" else "MH Player • Offline audio",
                                color = Color(0xFFB9C4D8), fontSize = 12.sp)
                        }
                    }
                    if (!pipActive) Box(Modifier.fillMaxSize()
                        .pointerInput(audioMode, locked) {
                            detectTransformGestures { _, _, zoom, _ ->
                                if (!audioMode && !locked && zoom != 1f) {
                                    pinchZoom = (pinchZoom * zoom).coerceIn(1f, 4f)
                                    controlsInteraction++
                                }
                            }
                        }
                        .pointerInput(locked) {
                            detectTapGestures(
                                onTap = {
                                    if (!locked) {
                                        controlsVisible = !controlsVisible
                                        controlsInteraction++
                                    }
                                },
                                onDoubleTap = { offset ->
                                    if (!locked && !audioMode) {
                                        val skipMs = seekSeconds * 1000L
                                        val forward = offset.x >= size.width / 2f
                                        val target = if (forward) player.currentPosition + skipMs else player.currentPosition - skipMs
                                        val maxPosition = if (player.duration > 0) player.duration else Long.MAX_VALUE
                                        player.seekTo(target.coerceIn(0L, maxPosition))
                                        seekFeedback = (if (forward) "+" else "-") + "${seekSeconds}s"
                                        controlsInteraction++
                                    }
                                },
                                onLongPress = { offset ->
                                    if (!locked && !audioMode && offset.x >= size.width / 2f && !holdSpeedActive) {
                                        holdSpeedActive = true
                                        player.setPlaybackSpeed(2f)
                                        controlsInteraction++
                                    }
                                },
                                onPress = {
                                    tryAwaitRelease()
                                    if (holdSpeedActive) {
                                        holdSpeedActive = false
                                        player.setPlaybackSpeed(playbackSpeed)
                                        controlsInteraction++
                                    }
                                }
                            )
                        }
                        .pointerInput(locked) {
                            var startX = 0f
                            var startLevel = 0f
                            var accumulated = 0f
                            var brightnessSide = true
                            var horizontalGesture = false
                            var verticalGesture = false
                            var horizontalDistance = 0f
                            detectDragGestures(
                                onDragStart = { offset ->
                                    startX = offset.x
                                    brightnessSide = startX < size.width / 2f
                                    accumulated = 0f
                                    horizontalDistance = 0f
                                    horizontalGesture = false
                                    verticalGesture = false
                                    startLevel = if (brightnessSide) {
                                        val value = activity.window.attributes.screenBrightness
                                        if (value < 0f) 0.5f else value
                                    } else {
                                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
                                    }
                                    gestureBrightness = brightnessSide
                                    gestureLevel = -1f
                                    controlsInteraction++
                                },
                                onDragEnd = { gestureLevel = -1f; horizontalGesture = false; verticalGesture = false },
                                onDragCancel = { gestureLevel = -1f },
                                onDrag = { change, dragAmount ->
                                    if (locked) return@detectDragGestures
                                    if (!horizontalGesture && !verticalGesture) {
                                        if (kotlin.math.abs(dragAmount.x) > kotlin.math.abs(dragAmount.y) * 1.2f) horizontalGesture = true
                                        else if (kotlin.math.abs(dragAmount.y) > kotlin.math.abs(dragAmount.x) * 1.2f) verticalGesture = true
                                    }
                                    if (horizontalGesture) {
                                        change.consume()
                                        horizontalDistance += dragAmount.x
                                        val seekMs = (horizontalDistance / size.width.coerceAtLeast(1).toFloat() * 60000f).toLong()
                                        if (kotlin.math.abs(seekMs) >= 500L) {
                                            val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                            player.seekTo((player.currentPosition + seekMs).coerceIn(0L, end))
                                            horizontalDistance = 0f
                                            controlsInteraction++
                                        }
                                    } else if (verticalGesture && (if (brightnessSide) brightnessGesture else volumeGesture)) {
                                        change.consume()
                                        accumulated -= dragAmount.y / size.height.coerceAtLeast(1).toFloat()
                                        val level = (startLevel + accumulated * 1.5f).coerceIn(0f, 1f)
                                        gestureLevel = level
                                        if (brightnessSide) {
                                            val params = activity.window.attributes
                                            params.screenBrightness = level.coerceAtLeast(0.01f)
                                            activity.window.attributes = params
                                        } else {
                                            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt().coerceIn(0, max), 0)
                                        }
                                    }
                                }
                            )
                        }
                    )
                    if (holdSpeedActive && !audioMode) {
                        Text("2× Speed • Release to resume", modifier = Modifier.align(Alignment.Center), color = Color.White)
                    }
                    if (gestureLevel >= 0f && !locked && !audioMode) {
                        Surface(Modifier.align(Alignment.Center), color = Color(0xDD151827), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(if (gestureBrightness) Icons.Default.Brightness6 else Icons.Default.VolumeUp, null, tint = purple)
                                Spacer(Modifier.height(8.dp))
                                Text((if (gestureBrightness) "Brightness " else "Volume ") + "${(gestureLevel * 100).toInt()}%", color = foreground)
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(progress = { gestureLevel }, modifier = Modifier.width(130.dp))
                            }
                        }
                    }
                }
                if (!pipActive && !expandedControls && (controlsVisible || locked || audioMode)) Column(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(Color.Transparent).padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (locked) locked = false else closePlayer() }) {
                            Icon(Icons.Default.ArrowBack, "Back", tint = foreground)
                        }
                        Column(Modifier.weight(1f)) { Text(selected?.title ?: "", color = foreground, maxLines = 1, fontSize = 16.sp); Text(if (audioMode) "AUDIO PLAYER" else "VIDEO PLAYER", color = Color(0xFFB5A1FF), fontSize = 10.sp) }

                    }
                }
                if (!pipActive && !locked && !expandedControls && (controlsVisible || audioMode)) {
                    Row(Modifier.align(Alignment.TopStart).padding(top = 54.dp, start = 4.dp).background(Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { backgroundPlay = !backgroundPlay; prefs.edit().putBoolean("background_play", backgroundPlay).apply(); controlsInteraction++ }) { Icon(Icons.Default.Headphones, if (backgroundPlay) "Background playback on" else "Background playback off", tint = if (backgroundPlay) Color.White else Color.White.copy(alpha = 0.65f)) }
                        IconButton(onClick = { orientationLocked = !orientationLocked; activity.requestedOrientation = if (orientationLocked) (if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; controlsInteraction++ }) { Icon(Icons.Default.ScreenRotation, "Orientation lock", tint = Color.White) }
                        IconButton(onClick = { equalizerOpen = true; controlsInteraction++ }) { Icon(Icons.Default.Equalizer, "Equalizer", tint = Color.White) }
                        IconButton(onClick = { muted = !muted; player.volume = if (muted) 0f else 1f; controlsInteraction++ }) { Icon(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, "Mute", tint = Color.White) }
                        Box {
                            IconButton(onClick = { expandedControls = !expandedControls }) { Icon(Icons.Default.ExpandMore, "All player actions", tint = Color.White) }

                        }
                    }
                    Row(Modifier.align(Alignment.TopEnd).padding(top = 54.dp, end = 4.dp).background(Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { sleepMenu = true }) { Icon(Icons.Default.Timer, "Sleep timer", tint = Color.White) }
                            DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                                DropdownMenuItem(text = { Text("Off") }, onClick = { sleepDeadline = 0L; sleepMenu = false })
                                listOf(30,60,120,300,600,1800).forEach { sec -> DropdownMenuItem(text = { Text("${sec} seconds") }, onClick = { sleepDeadline = android.os.SystemClock.elapsedRealtime() + sec*1000L; sleepMenu = false }) }
                            }
                        }
                        Box {
                            IconButton(onClick = { speedMenu = true }) { Icon(Icons.Default.Speed, "Speed", tint = Color.White) }
                            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                                listOf(.5f,.75f,1f,1.25f,1.5f,2f).forEach { rate -> DropdownMenuItem(text = { Text("${rate}x") }, onClick = { playbackSpeed = rate; player.setPlaybackSpeed(rate); speedMenu = false }) }
                            }
                        }
                        Box {
                            IconButton(onClick = { expandedControls = !expandedControls }) { Icon(Icons.Default.MoreVert, "All controls", tint = Color.White) }

                        }
                    }
                }
                if (!pipActive && !locked) {
                    AnimatedVisibility(
                        visible = expandedControls,
                        modifier = Modifier.align(Alignment.CenterStart)
                            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
                        enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                        exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
                    ) {
                        Column(
                            Modifier.widthIn(max = 320.dp)
                                .background(Color.Black.copy(alpha = 0.30f))
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("PLAYER CONTROLS", color = Color.White, fontSize = 12.sp)
                                IconButton(onClick = { expandedControls = false }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, "Close controls", tint = Color.White)
                                }
                            }
                            val actions = listOf(
                                Triple("Background", Icons.Default.Headphones, 0),
                                Triple("Rotate", Icons.Default.ScreenRotation, 1),
                                Triple("Equalizer", Icons.Default.Equalizer, 2),
                                Triple("Mute", Icons.Default.VolumeOff, 3),
                                Triple("Timer", Icons.Default.Timer, 4),
                                Triple("PiP", Icons.Default.PictureInPictureAlt, 5),
                                Triple("Speed", Icons.Default.Speed, 6),
                                Triple("Ratio", Icons.Default.AspectRatio, 7),
                                Triple("Audio", Icons.Default.Audiotrack, 8),
                                Triple("Subtitles", Icons.Default.Subtitles, 9),
                                Triple("Add subtitle", Icons.Default.ClosedCaption, 10),
                                Triple("Repeat", Icons.Default.Repeat, 11),
                                Triple("Settings", Icons.Default.Settings, 12),
                                Triple("Lock", Icons.Default.Lock, 13),
                                Triple("Previous", Icons.Default.SkipPrevious, 14),
                                Triple("Next", Icons.Default.SkipNext, 15)
                            )
                            actions.chunked(4).forEach { group ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    group.forEach { (label, actionIcon, id) ->
                                        Column(
                                            Modifier.width(72.dp).clickable {
                                                expandedControls = false
                                                when (id) {
                                                    0 -> { player.pause(); backgroundPlay = true }
                                                    1 -> { orientationLocked = !orientationLocked; activity.requestedOrientation = if (orientationLocked) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
                                                    2 -> equalizerOpen = true
                                                    3 -> { muted = !muted; player.volume = if (muted) 0f else 1f }
                                                    4 -> sleepMenu = true
                                                    5 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                        try { activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) } catch (_: Exception) {}
                                                    }
                                                    6 -> speedMenu = true
                                                    7 -> ratioMenu = true
                                                    8 -> trackDialog = true
                                                    9 -> subtitleDialog = true
                                                    10 -> subtitlePicker.launch(arrayOf("*/*"))
                                                    11 -> { repeatOne = !repeatOne; player.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
                                                    12 -> quickSettingsOpen = true
                                                    13 -> locked = true
                                                    14 -> player.seekToPreviousMediaItem()
                                                    15 -> player.seekToNextMediaItem()
                                                }
                                                controlsInteraction++
                                            }.padding(vertical = 7.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(actionIcon, label, modifier = Modifier.size(21.dp), tint = Color.White)
                                            Spacer(Modifier.height(4.dp))
                                            Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (!pipActive && !locked && !expandedControls && (controlsVisible || audioMode)) Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        .background(Color.Transparent).padding(start = 6.dp, end = 6.dp, top = 0.dp, bottom = 4.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(modifier = Modifier.size(32.dp), onClick = { locked = true }) { Icon(Icons.Default.Lock, "Lock screen", tint = Color.White) }
                        Row {
                            IconButton(modifier = Modifier.size(32.dp), onClick = { speedMenu = true }) { Icon(Icons.Default.Speed, "Playback speed", tint = Color.White) }
                            Box {
                                IconButton(modifier = Modifier.size(32.dp), onClick = { ratioMenu = true }) { Icon(Icons.Default.AspectRatio, "Screen ratio", tint = Color.White) }
                                DropdownMenu(expanded = ratioMenu, onDismissRequest = { ratioMenu = false }) {
                                    listOf("Fit","Fill","Original","Stretch","16:9","4:3","18:9","1.85:1","2.21:1").forEachIndexed { index, label ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = { resizeChoice = when(index) { 0 -> 0; 1 -> 2; 2 -> 4; else -> 3 }; prefs.edit().putInt("ratio_mode", resizeChoice).apply(); ratioMenu = false })
                                    }
                                    DropdownMenuItem(text = { Text(if (rememberRatio) "✓ Remember ratio" else "Remember ratio") }, onClick = { rememberRatio = !rememberRatio; prefs.edit().putBoolean("remember_ratio", rememberRatio).apply() })
                                }
                            }
                        }
                    }
                    if (activeLayout == "Cinema") Text("CINEMA", color = purple, fontSize = 9.sp)
                    if (!audioMode && activity.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) Text("SWIPE TO SEEK  •  DOUBLE TAP TO SKIP", color = Color(0xFFB9B9C9), fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Slider(
                        modifier = Modifier.height(28.dp),
                        value = if (duration > 0) (progress.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { if (duration > 0) player.seekTo((it * duration).toLong()); controlsInteraction++ }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${progress / 60000}:${"%02d".format(progress / 1000 % 60)}", color = foreground, fontSize = 11.sp)
                        Text(if (duration > 0) "-${(duration - progress).coerceAtLeast(0) / 60000}:${"%02d".format((duration - progress).coerceAtLeast(0) / 1000 % 60)}" else "0:00", color = Color(0xFFB9B9C9), fontSize = 11.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (activeLayout == "Modern") Arrangement.SpaceAround else Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(modifier = Modifier.size(32.dp), onClick = {
                            player.seekTo((player.currentPosition - seekSeconds * 1000).coerceAtLeast(0))
                            controlsInteraction++
                        }) { Icon(Icons.Default.Replay10, "Seek backward", tint = Color.White) }
                        IconButton(modifier = Modifier.size(32.dp), onClick = { player.seekToPreviousMediaItem(); controlsInteraction++ }) { Icon(Icons.Default.SkipPrevious, "Previous file", tint = Color.White) }
                        IconButton(modifier = Modifier.size(32.dp), onClick = { if (player.isPlaying) player.pause() else player.play(); controlsInteraction++ }) {
                            Icon(if (playing) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                "Play or pause", Modifier.size(34.dp), tint = Color.White)
                        }
                        IconButton(modifier = Modifier.size(32.dp), onClick = { player.seekToNextMediaItem(); controlsInteraction++ }) { Icon(Icons.Default.SkipNext, "Next file", tint = Color.White) }
                        IconButton(modifier = Modifier.size(32.dp), onClick = { player.seekTo(player.currentPosition + seekSeconds * 1000); controlsInteraction++ }) {
                            Icon(Icons.Default.Forward10, "Seek forward", tint = Color.White)
                        }
                        if (activeLayout != "Cinema") TextButton(onClick = {
                            seekSeconds = if (seekSeconds == 10L) 30L else 10L
                            controlsInteraction++
                        }) { Text("${seekSeconds}s", fontSize = 11.sp) }
                    }
                } else if (!pipActive && locked && !expandedControls) Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)).padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(onClick = { locked = false; controlsVisible = true; controlsInteraction++ }) {
                        Text("Unlock controls")
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().background(dark)) {
            Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 74.dp)) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MH Player", fontSize = 30.sp, color = foreground, modifier = Modifier.weight(1f))
                    var mainMenuExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { searchExpanded = !searchExpanded; if (!searchExpanded) searchText = "" }) { Icon(if (searchExpanded) Icons.Default.Close else Icons.Default.Search, "Search files", tint = purple) }
                    Box {
                        IconButton(onClick = { mainMenuExpanded = true }) { Icon(Icons.Default.MoreVert, "More options", tint = purple) }
                        DropdownMenu(expanded = mainMenuExpanded, onDismissRequest = { mainMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text("Select file / folder") }, leadingIcon = { Icon(Icons.Default.FolderOpen, null) }, onClick = { mainMenuExpanded = false; filePicker.launch(arrayOf("video/*", "audio/*", "*/*")) })
                            DropdownMenuItem(text = { Text("Refresh") }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, onClick = { mainMenuExpanded = false; refreshMedia() })
                            DropdownMenuItem(text = { Text("Equalizer") }, leadingIcon = { Icon(Icons.Default.Equalizer, null) }, onClick = { mainMenuExpanded = false; settingsOpen = true })
                            DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { mainMenuExpanded = false; settingsOpen = true })
                            DropdownMenuItem(text = { Text("Suggest features") }, leadingIcon = { Icon(Icons.Default.Feedback, null) }, onClick = { mainMenuExpanded = false; val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_SUBJECT, "MH Player feature suggestion"); try { activity.startActivity(intent) } catch (_: Exception) {} })
                        }
                    }
                }
                Text("Offline video & audio", color = secondaryText)
                if (searchExpanded) {
                    OutlinedTextField(value = searchText, onValueChange = { searchText = it }, label = { Text("Search files and folders") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (playlistOnly) {
                    PlaylistLibrary(prefs = prefs, media = media.filter { includeAndroidMedia || !it.folder.trimStart('/').startsWith("Android/", ignoreCase = true) }, onPlay = { selected = it })
                } else {
                val recentItems = remember(media, recentUris) { recentUris.mapNotNull { uri -> media.firstOrNull { it.uri.toString() == uri } } }
                if (currentFolder == null) {
                    Spacer(Modifier.height(12.dp))
                    if (recentItems.isEmpty()) {
                        val previous = media.firstOrNull { it.uri.toString() == lastPlayedUri }
                        if (previous != null) {
                            Text("Last played", color = foreground, fontSize = 18.sp)
                            Row(Modifier.fillMaxWidth().clickable { selected = previous }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column { MediaThumbnail(previous, Modifier.size(width = 112.dp, height = 64.dp)); WatchProgress(previous, prefs, Modifier.width(112.dp)) }
                                Spacer(Modifier.width(10.dp))
                                Text(previous.title, color = foreground, maxLines = 2)
                            }
                        } else {
                            Text("Last played", color = foreground, fontSize = 18.sp)
                            Text("Play a video or song to see it here", color = secondaryText, fontSize = 12.sp)
                        }
                    } else {
                    Text("Last played", color = foreground, fontSize = 18.sp)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recentItems, key = { it.uri.toString() }) { item ->
                            Column(Modifier.width(136.dp).clickable { selected = item }) {
                                MediaThumbnail(item, Modifier.fillMaxWidth().height(82.dp))
                                WatchProgress(item, prefs, Modifier.fillMaxWidth())
                                Text(item.title, color = foreground, maxLines = 1, fontSize = 12.sp)
                            }
                        }
                    }
                    }
                }
                Spacer(Modifier.height(20.dp))
                if (libraryOptions) AlertDialog(
                    onDismissRequest = { libraryOptions = false },
                    title = { Text("Library view & sort") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text("View mode")
                            Row {
                                listOf("Folders", "All files").forEach { mode ->
                                    FilterChip(selected = libraryView == mode, onClick = {
                                        libraryView = mode; currentFolder = null
                                        prefs.edit().putString("library_view", mode).apply()
                                    }, label = { Text(mode) })
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
                            Text("Layout")
                            Row {
                                listOf(false to "List", true to "Grid").forEach { (grid, label) ->
                                    FilterChip(selected = gridView == grid, onClick = {
                                        gridView = grid; prefs.edit().putBoolean("grid_view", grid).apply()
                                    }, label = { Text(label) })
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
                            Text("Sort by")
                            listOf("Name", "Date", "Duration", "Size").chunked(2).forEach { modes ->
                                Row {
                                    modes.forEach { mode ->
                                        FilterChip(selected = sortMode == mode, onClick = {
                                            sortMode = mode; prefs.edit().putString("sort_mode", mode).apply()
                                        }, label = { Text(mode) })
                                        Spacer(Modifier.width(8.dp))
                                    }
                                }
                            }
                            Row {
                                FilterChip(selected = !sortDescending, onClick = {
                                    sortDescending = false; prefs.edit().putBoolean("sort_desc", false).apply()
                                }, label = { Text("Ascending") })
                                Spacer(Modifier.width(8.dp))
                                FilterChip(selected = sortDescending, onClick = {
                                    sortDescending = true; prefs.edit().putBoolean("sort_desc", true).apply()
                                }, label = { Text("Descending") })
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { libraryOptions = false }) { Text("Done") } }
                )
                if (currentFolder != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { currentFolder = null }) { Icon(Icons.Default.ArrowBack, "Folders", tint = purple) }
                        Text(currentFolder ?: "", color = foreground, maxLines = 2)
                    }
                } else Text(if (libraryView == "Folders") "Folders" else "All files", color = foreground, fontSize = 20.sp)
                if (libraryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val filtered = remember(media, showVideo, playlistOnly, playlistUris, includeAndroidMedia, includedFolders, showHidden, searchText) {
                    val allowedFolders = includedFolders.takeIf { it.isNotBlank() }?.split("|")?.toHashSet()
                    media.filter {
                        it.video == showVideo &&
                        (!playlistOnly || it.uri.toString() in playlistUris) &&
                        (includeAndroidMedia || !it.folder.trimStart('/').startsWith("Android/", ignoreCase = true)) &&
                        (allowedFolders == null || it.folder in allowedFolders) &&
                        (showHidden || (!it.title.startsWith(".") && it.folder.split("/").none { part -> part.startsWith(".") })) &&
                        (searchText.isBlank() || it.title.contains(searchText, true) || it.folder.contains(searchText, true))
                    }
                }
                val folders = remember(filtered) { filtered.groupBy { it.folder } }
                if (floatingResume && lastPlayedUri.isNotBlank()) {
                    val resumeItem = media.firstOrNull { it.uri.toString() == lastPlayedUri }
                    if (resumeItem != null) TextButton(onClick = { selected = resumeItem }) {
                        Icon(Icons.Default.PlayCircle, "Resume last played", tint = purple)
                        Text("Resume last played")
                    }
                }
                if (filtered.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No media found. Tap Open file to browse.", color = secondaryText)
                } else if (currentFolder == null && libraryView == "Folders") {
                    LazyColumn {
                        items(
                            folders.entries.let { groups ->
                                val orderedFolders = when (sortMode) {
                                    "Date" -> groups.sortedWith(compareBy({ it.value.maxOfOrNull { media -> media.dateAdded } ?: 0L }, { it.key.lowercase() }))
                                    "Duration" -> groups.sortedWith(compareBy({ it.value.sumOf { media -> media.duration.coerceAtLeast(0L) } }, { it.key.lowercase() }))
                                    "Size" -> groups.sortedWith(compareBy({ it.value.sumOf { media -> media.size.coerceAtLeast(0L) } }, { it.key.lowercase() }))
                                    else -> groups.sortedBy { it.key.lowercase() }
                                }
                                if (sortDescending) orderedFolders.reversed() else orderedFolders
                            },
                            key = { it.key }
                        ) { folder ->
                            Row(Modifier.fillMaxWidth().clickable { currentFolder = folder.key }.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, null, tint = purple, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(folder.key.substringAfterLast('/').ifBlank { "Device storage" }, color = foreground, fontSize = 17.sp)
                                    Text(folder.key + " • " + folder.value.size + " files", color = secondaryText, fontSize = 12.sp, maxLines = 2)
                                    val watchedCount = folder.value.count { entry ->
                                        entry.video && prefs.getLong("position_${entry.uri}", 0L) > 0L
                                    }
                                    if (watchedCount > 0) Text(
                                        "${watchedCount} of ${folder.value.count { it.video }} videos started",
                                        color = Color(0xFF31C66B), fontSize = 11.sp
                                    )
                                    if (markLastPlayed && folder.value.any { it.uri.toString() == lastPlayedUri }) {
                                        Text("Last played", color = purple, fontSize = 12.sp)
                                    }
                                }
                                Box {
                                    IconButton(onClick = { folderAction = folder.key; showFolderActions = true }) { Icon(Icons.Default.MoreVert, "Folder actions", tint = purple) }
                                    DropdownMenu(expanded = showFolderActions && folderAction == folder.key, onDismissRequest = { showFolderActions = false }) {
                                        listOf("Background play","Add to playlist","Rename","Delete","Share").forEach { action ->
                                            DropdownMenuItem(text = { Text(action) }, leadingIcon = { Icon(Icons.Default.Folder, null) }, onClick = {
                                                showFolderActions = false
                                                when(action) {
                                                    "Background play" -> { val first = folder.value.firstOrNull(); if (first != null) selected = first }
                                                    "Add to playlist" -> { playlistUris = playlistUris + folder.value.map { it.uri.toString() }; prefs.edit().putStringSet("my_playlist", playlistUris.toSet()).apply() }
                                                    "Rename" -> { folderRename = folder.key.substringAfterLast('/'); folderRenameDialog = true }
                                                    "Delete" -> folderDeleteDialog = true
                                                    "Share" -> { val first = folder.value.firstOrNull(); if (first != null) { val intent = Intent(Intent.ACTION_SEND).setType(if (first.video) "video/*" else "audio/*").putExtra(Intent.EXTRA_STREAM, first.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); activity.startActivity(Intent.createChooser(intent, "Share media")) } }
                                                }
                                            })
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(color = Color(0xFF30364C))
                        }
                    }
                } else {
                    val sorted = remember(filtered, currentFolder, sortMode, sortDescending) {
                        val entries = if (currentFolder == null) filtered else filtered.filter { it.folder == currentFolder }
                        val ordered = when (sortMode) {
                        "Date" -> entries.sortedWith(compareBy({ it.dateAdded }, { it.title.lowercase() }))
                        "Duration" -> entries.sortedWith(compareBy({ it.duration }, { it.title.lowercase() }))
                        "Size" -> entries.sortedWith(compareBy({ it.size }, { it.title.lowercase() }))
                        else -> entries.sortedBy { it.title.lowercase() }
                    }
                        if (sortDescending) ordered.reversed() else ordered
                    }
                    if (gridView) {
                        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 140.dp)) {
                            gridItems(sorted, key = { it.uri.toString() }) { item ->
                                Column(
                                    Modifier.fillMaxWidth().clickable { selected = item }.padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    MediaThumbnail(item, Modifier.size(width = 112.dp, height = 70.dp))
                                    WatchProgress(item, prefs, Modifier.width(112.dp))
                                    Spacer(Modifier.height(8.dp))
                                    Text(item.title, color = foreground, maxLines = 2)
                                    TextButton(onClick = { togglePlaylist(item) }) { Text(if (item.uri.toString() in playlistUris) "Remove" else "+ Playlist") }
                                    if (markLastPlayed && item.uri.toString() == lastPlayedUri) Text("Last played", color = purple, fontSize = 12.sp)
                                    if (newTagDays > 0 && item.dateAdded > 0 && System.currentTimeMillis() / 1000 - item.dateAdded <= newTagDays * 86400L) Text("NEW", color = purple, fontSize = 11.sp)
                                    Text("${item.duration / 60000} min", color = secondaryText, fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        LazyColumn {
                            items(sorted, key = { it.uri.toString() }) { item ->
                                Row(Modifier.fillMaxWidth().clickable { selected = item }.padding(vertical = 15.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column { MediaThumbnail(item, Modifier.size(width = 76.dp, height = 50.dp)); WatchProgress(item, prefs, Modifier.width(76.dp)) }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title, color = foreground, maxLines = 2)
                                        Text("${item.duration / 60000} min", color = secondaryText, fontSize = 12.sp)
                                    }
                                    IconButton(onClick = { togglePlaylist(item) }) {
                                        Icon(if (item.uri.toString() in playlistUris) Icons.Default.PlaylistRemove else Icons.Default.PlaylistAdd, "Toggle playlist", tint = purple)
                                    }
                                }
                                HorizontalDivider(color = Color(0xFF30364C))
                            }
                        }
                    }
                }
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(dark)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(selected = showVideo && !playlistOnly, onClick = { showVideo = true; playlistOnly = false; currentFolder = null }, label = { Text("Videos") })
                FilterChip(selected = !showVideo && !playlistOnly, onClick = { showVideo = false; playlistOnly = false; currentFolder = null }, label = { Text("Music") })
                FilterChip(selected = playlistOnly, onClick = { playlistOnly = true; currentFolder = null }, label = { Text("Playlist") })
            }
            }
        }
        }
    }
}

@Composable
private fun SettingsToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsSectionTitle(title: String, color: Color) {
    Text(title, color = color, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp), letterSpacing = 1.sp)
}

@Composable
private fun SettingsNavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?, accent: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = accent.copy(alpha = .10f), shape = androidx.compose.foundation.shape.RoundedCornerShape(9.dp)) {
            Icon(icon, null, Modifier.padding(9.dp).size(20.dp), tint = accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
