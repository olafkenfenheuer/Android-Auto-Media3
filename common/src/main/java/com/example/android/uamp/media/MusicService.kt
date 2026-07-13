/*
 * Copyright 2017 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.uamp.media

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ConditionVariable
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION
import androidx.media3.common.Player.EVENT_PLAY_WHEN_READY_CHANGED
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.Listener
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.android.uamp.media.library.BrowseTree
import com.example.android.uamp.media.library.JsonSource
import com.example.android.uamp.media.library.MEDIA_SEARCH_SUPPORTED
import com.example.android.uamp.media.library.MusicSource
import com.example.android.uamp.media.library.UAMP_ALBUMS_ROOT
import com.example.android.uamp.media.library.UAMP_BROWSABLE_ROOT
import com.example.android.uamp.media.library.UAMP_RECENT_ROOT
import com.example.android.uamp.media.library.UAMP_RECOMMENDED_ROOT
import com.google.android.gms.cast.framework.CastContext
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Service for browsing the catalogue and and receiving a [MediaController] from the app's UI
 * and other apps that wish to play music via UAMP (for example, Android Auto or
 * the Google Assistant).
 *
 * Browsing begins with the method [MusicService.MusicServiceCallback.onGetLibraryRoot], and
 * continues in the callback [MusicService.MusicServiceCallback.onGetChildren].
 *
 * This class also handles playback for Cast sessions. When a Cast session is active, playback
 * commands are passed to a [CastPlayer].
 */
@OptIn(UnstableApi::class)
open class MusicService : MediaLibraryService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    protected lateinit var mediaSession: MediaLibrarySession
    private var currentMediaItemIndex: Int = 0

    private lateinit var musicSource: MusicSource
    private lateinit var packageValidator: PackageValidator
    private lateinit var storage: PersistentStorage

    private val browseTreeLock = Any()

    /**
     * Cached [BrowseTree] built from the current [musicSource]. It is built lazily on first access
     * (the [musicSource] won't initially be ready — use [callWhenMusicSourceReady] to be sure it is)
     * and rebuilt from scratch after a periodic catalog refresh by calling [invalidateBrowseTree].
     */
    @Volatile
    private var browseTreeCache: BrowseTree? = null

    private val browseTree: BrowseTree
        get() = browseTreeCache ?: synchronized(browseTreeLock) {
            browseTreeCache ?: BrowseTree(applicationContext, musicSource).also {
                browseTreeCache = it
            }
        }

    /** Drops the cached [BrowseTree] so the next access rebuilds it from the current catalog. */
    private fun invalidateBrowseTree() {
        synchronized(browseTreeLock) { browseTreeCache = null }
    }

    private val recentRootMediaItem: MediaItem by lazy {
        MediaItem.Builder()
            .setMediaId(UAMP_RECENT_ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setIsPlayable(false)
                    .build())
            .build()
    }

    private val catalogueRootMediaItem: MediaItem by lazy {
        MediaItem.Builder()
            .setMediaId(UAMP_BROWSABLE_ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setIsPlayable(false)
                    .build())
            .build()
    }

    private val executorService by lazy {
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    }

    private val remoteJsonSource: Uri =
        //Uri.parse("https://storage.googleapis.com/uamp/catalog.json")
//        Uri.parse("https://mustransport.de/music.json")
    Uri.parse("https://app.kenfenheuer.net/uamp/music.json")

    private val uAmpAudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

    private val playerListener = PlayerEventListener()

    /**
     * Configure ExoPlayer to handle audio focus for us. See [ExoPlayer.Builder.setAudioAttributes]
     * for details.
     */
    private val exoPlayer: Player by lazy {
        // Follow cross-protocol (http -> https) redirects so streams that answer with a 302
        // (e.g. some station URLs) load instead of failing with ERROR_CODE_IO_BAD_HTTP_STATUS.
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        // The stations are progressive Icecast MP3 streams, so use ProgressiveMediaSource directly.
        // Its continue-loading check interval (default 1 MB, ~60s at 128 kbit/s) is the granularity
        // at which the LoadControl's time cap below can take effect: with the 1 MB default the player
        // reads a huge block before re-checking, so the buffer overshoots the cap by ~20s. Shrinking
        // it to 96 KB (~6s at 128 kbit/s) lets the cap actually bite, keeping the paused spool at
        // roughly one block (~5-8s) ahead of live instead of ~20-50s.
        val mediaSourceFactory = ProgressiveMediaSource.Factory(httpDataSourceFactory)
            .setContinueLoadingCheckIntervalBytes(96 * 1024)
        // These are live radio streams. Keep the spool buffer short so that while paused the player
        // only pre-loads a small amount of audio; on resume it plays back at most this much behind
        // the live edge instead of drifting far behind (which is what a large buffer would cause).
        // Values are in ms and must satisfy the DefaultLoadControl ordering (min/max buffer >= the
        // buffer-for-playback thresholds). The effective spool is governed jointly by this cap and
        // the media source's read-block size above.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs= */ 2_000,
                /* maxBufferMs= */ 2_000,
                /* bufferForPlaybackMs= */ 1_000,
                /* bufferForPlaybackAfterRebufferMs= */ 2_000
            )
            .build()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().apply {
            setAudioAttributes(uAmpAudioAttributes, true)
            setHandleAudioBecomingNoisy(true)
            addListener(playerListener)
        }
        player.addAnalyticsListener(EventLogger(null, "exoplayer-uamp"))
        player
    }

    /**
     * If Cast is available, create a CastPlayer to handle communication with a Cast session.
     */
    private val castPlayer: CastPlayer? by lazy {
        try {
            val castContext = CastContext.getSharedInstance(this)
            CastPlayer(castContext, CastMediaItemConverter()).apply {
                setSessionAvailabilityListener(UampCastSessionAvailabilityListener())
                addListener(playerListener)
            }
        } catch (e : Exception) {
            // We wouldn't normally catch the generic `Exception` however
            // calling `CastContext.getSharedInstance` can throw various exceptions, all of which
            // indicate that Cast is unavailable.
            // Related internal bug b/68009560.
            Log.i(TAG, "Cast is not available on this device. " +
                    "Exception thrown when attempting to obtain CastContext. " + e.message)
            null
        }
    }

    private val replaceableForwardingPlayer: ReplaceableForwardingPlayer by lazy {
        ReplaceableForwardingPlayer(exoPlayer)
    }

    /** @return the {@link MediaLibrarySessionCallback} to be used to build the media session. */
    open fun getCallback(): MediaLibrarySession.Callback {
        return MusicServiceCallback()
    }

    override fun onCreate() {
        super.onCreate()

        if (castPlayer?.isCastSessionAvailable == true) {
            replaceableForwardingPlayer.setPlayer(castPlayer!!)
        }

        mediaSession = with(MediaLibrarySession.Builder(
            this, replaceableForwardingPlayer, getCallback())) {
            setId(packageName)
            packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
                setSessionActivity(
                    PendingIntent.getActivity(
                        /* context= */ this@MusicService,
                        /* requestCode= */ 0,
                        sessionIntent,
                        if (Build.VERSION.SDK_INT >= 23) FLAG_IMMUTABLE
                        else FLAG_UPDATE_CURRENT
                    )
                )
            }
            build()
        }

        // The media library is built from a remote JSON file. We start loading asynchronously here.
        // Use [callWhenMusicSourceReady] to execute code that needs the source load being
        // completed.
        musicSource = JsonSource(source = remoteJsonSource)
        serviceScope.launch {
            musicSource.load()
        }
        startPeriodicCatalogRefresh()

        packageValidator = PackageValidator(this, R.xml.allowed_media_browser_callers)
        storage = PersistentStorage.getInstance(applicationContext)
    }

    /**
     * Periodically re-downloads the station catalog so long-running sessions (e.g. Android Auto left
     * connected for hours) pick up server-side catalog changes without restarting the app. A refresh
     * that can't reach the network is a no-op that keeps the current catalog in place; playback of
     * the current station is never interrupted (only the browsable catalog is rebuilt). The loop is
     * bound to [serviceScope], so it stops when the service is destroyed.
     */
    private fun startPeriodicCatalogRefresh() {
        serviceScope.launch {
            while (isActive) {
                delay(CATALOG_REFRESH_INTERVAL_MS)
                val source = musicSource as? JsonSource ?: continue
                if (source.refresh()) {
                    // Rebuild the browse tree from the refreshed catalog and tell subscribed
                    // browsers (Android Auto, the app UI) to reload the affected nodes.
                    invalidateBrowseTree()
                    notifyCatalogChanged()
                }
            }
        }
    }

    /** Notifies subscribed browsers that the browsable catalog roots changed after a refresh. */
    private fun notifyCatalogChanged() {
        for (parentId in listOf(UAMP_BROWSABLE_ROOT, UAMP_ALBUMS_ROOT, UAMP_RECOMMENDED_ROOT)) {
            val itemCount = browseTree[parentId]?.size ?: 0
            mediaSession.notifyChildrenChanged(parentId, itemCount, null)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return if ("android.media.session.MediaController" == controllerInfo.packageName
            || packageValidator.isKnownCaller(controllerInfo.packageName, controllerInfo.uid)) {
            mediaSession
        } else null
    }

    /** Called when swiping the activity away from recents. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        saveRecentSongToStorage()
        super.onTaskRemoved(rootIntent)
        // The choice what to do here is app specific. Some apps stop playback, while others allow
        // playback to continue and allow users to stop it with the notification.
        releaseMediaSession()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaSession()
    }

    private fun releaseMediaSession() {
        mediaSession.run {
            release()
            if (player.playbackState != Player.STATE_IDLE) {
                player.removeListener(playerListener)
                player.release()
            }
        }
        // Cancel coroutines when the service is going away.
        serviceJob.cancel()
    }

    private fun saveRecentSongToStorage() {
        // Obtain the current song details *before* saving them on a separate thread, otherwise
        // the current player may have been unloaded by the time the save routine runs.
        val currentMediaItem = replaceableForwardingPlayer.currentMediaItem ?: return
        serviceScope.launch {
            val mediaItem =
                browseTree.getMediaItemByMediaId(currentMediaItem.mediaId) ?: return@launch
            storage.saveRecentSong(mediaItem, replaceableForwardingPlayer.currentPosition)
        }
    }

    private fun preparePlayerForResumption(mediaItem: MediaItem) {
        musicSource.whenReady {
            if (it) {
                val playableMediaItem = browseTree.getMediaItemByMediaId(mediaItem.mediaId)
                val startPositionMs =
                    mediaItem.mediaMetadata.extras?.getLong(
                        MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS
                    ) ?: 0
                playableMediaItem?.let {
                    exoPlayer.setMediaItem(playableMediaItem)
                    exoPlayer.seekTo(startPositionMs)
                    exoPlayer.prepare()
                }
            }
        }
    }

    /** Returns a function that opens the condition variable when called. */
    private fun openWhenReady(conditionVariable: ConditionVariable): (Boolean) -> Unit = {
        val successfullyInitialized = it
        if (!successfullyInitialized) {
            Log.e(TAG, "loading music source failed")
        }
        conditionVariable.open()
    }

    /**
     * Returns a future that executes the action when the music source is ready. This may be an
     * immediate execution if the music source is ready, or a deferred asynchronous execution if the
     * music source is still loading.
     *
     * @param action The function to be called when the music source is ready.
     */
    private fun <T> callWhenMusicSourceReady(action: () -> T): ListenableFuture<T> {
        val conditionVariable = ConditionVariable()
        return if (musicSource.whenReady(openWhenReady(conditionVariable))) {
            Futures.immediateFuture(action())
        } else {
            executorService.submit<T> {
                conditionVariable.block();
                action()
            }
        }
    }

    open inner class MusicServiceCallback: MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // Advertise our custom "refresh catalog" command so controllers (the app's
            // pull-to-refresh) are allowed to trigger a catalog reload via onCustomCommand.
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .add(SessionCommand(COMMAND_REFRESH_CATALOG, Bundle.EMPTY))
                    .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // By default, all known clients are permitted to search, but only tell unknown callers
            // about search if permitted by the [BrowseTree].
            val isKnownCaller = packageValidator.isKnownCaller(browser.packageName, browser.uid)
            val rootExtras = Bundle().apply {
                putBoolean(
                    MEDIA_SEARCH_SUPPORTED,
                    isKnownCaller || browseTree.searchableByUnknownCaller
                )
                putBoolean(CONTENT_STYLE_SUPPORTED, true)
                putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID)
                putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
            }
            if (!isKnownCaller) {
                // Reject unknown callers with an error rather than an empty root — passing
                // MediaItem.EMPTY to LibraryResult.ofItem throws "mediaId must not be empty" and
                // crashes the service.
                return Futures.immediateFuture(
                    LibraryResult.ofError<MediaItem>(LibraryResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
            val libraryParams = LibraryParams.Builder().setExtras(rootExtras).build()
            val rootMediaItem = if (params?.isRecent == true) {
                if (exoPlayer.currentTimeline.isEmpty) {
                    storage.loadRecentSong()?.let {
                        preparePlayerForResumption(it)
                    }
                }
                recentRootMediaItem
            } else {
                catalogueRootMediaItem
            }
            return Futures.immediateFuture(LibraryResult.ofItem(rootMediaItem, libraryParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId == recentRootMediaItem.mediaId) {
                // When Android Auto connects it queries the recent root to build the resumption UI.
                // If nothing has been played yet, there is no recent song — return an empty list
                // instead of crashing with an NPE (previously `!!`).
                val recentSong = storage.loadRecentSong()
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(
                        recentSong?.let { listOf(it) } ?: emptyList(),
                        LibraryParams.Builder().build()
                    )
                )
            }
            return callWhenMusicSourceReady {
                LibraryResult.ofItemList(
                    browseTree[parentId] ?: ImmutableList.of(),
                    LibraryParams.Builder().build()
                )
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return callWhenMusicSourceReady {
                val item = browseTree.getMediaItemByMediaId(mediaId)
                if (item != null) {
                    LibraryResult.ofItem(item, LibraryParams.Builder().build())
                } else {
                    // The requested id isn't in the current catalog. Returning MediaItem.EMPTY here
                    // makes LibraryResult.ofItem throw "mediaId must not be empty" and crashes the
                    // service — Android Auto hit this repeatedly while subscribing to items. Report
                    // an error result instead.
                    LibraryResult.ofError<MediaItem>(LibraryResult.RESULT_ERROR_BAD_VALUE)
                }
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            return callWhenMusicSourceReady {
                val searchResult = musicSource.search(query, params?.extras ?: Bundle())
                mediaSession.notifySearchResultChanged(browser, query, searchResult.size, params)
                LibraryResult.ofVoid()
            }
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return callWhenMusicSourceReady {
                val searchResult = musicSource.search(query, params?.extras ?: Bundle())
                val fromIndex = max((page - 1) * pageSize, searchResult.size - 1)
                val toIndex = max(fromIndex + pageSize, searchResult.size)
                LibraryResult.ofItemList(searchResult.subList(fromIndex, toIndex), params)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            return callWhenMusicSourceReady {
                // Resolve each requested id against the current catalog. Drop ids that are no longer
                // present (e.g. the catalog was refreshed after the controller cached the id) rather
                // than crashing with an NPE (previously `!!`).
                mediaItems.mapNotNull { browseTree.getMediaItemByMediaId(it.mediaId) }.toMutableList()
            }
        }

        /**
         * Called when playback is resumed without an explicit media item (e.g. the user presses
         * play on the Android Auto / car media button while the session is idle, or after a reboot).
         * The recent-media contract (see [onGetLibraryRoot] with [LibraryParams.isRecent]) requires
         * this to be implemented; the default implementation throws [UnsupportedOperationException],
         * which surfaced as a crash/resume failure. We resume the last played station.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return callWhenMusicSourceReady {
                val recentSong = storage.loadRecentSong()
                    ?: throw UnsupportedOperationException("No recent media to resume")
                val playableMediaItem = browseTree.getMediaItemByMediaId(recentSong.mediaId)
                    ?: throw UnsupportedOperationException("Recent media no longer in catalog")
                val startPositionMs = recentSong.mediaMetadata.extras
                    ?.getLong(MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS) ?: 0L
                MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.of(playableMediaItem),
                    /* startIndex= */ 0,
                    startPositionMs
                )
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_REFRESH_CATALOG) {
                // Reload the remote catalog on demand (app pull-to-refresh). On success rebuild the
                // browse tree and notify browsers; the future completes once the reload is done so
                // the caller can stop its refresh spinner.
                return serviceScope.future {
                    if ((musicSource as? JsonSource)?.refresh() == true) {
                        invalidateBrowseTree()
                        notifyCatalogChanged()
                    }
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    private inner class UampCastSessionAvailabilityListener : SessionAvailabilityListener {

        /**
         * Called when a Cast session has started and the user wishes to control playback on a
         * remote Cast receiver rather than play audio locally.
         */
        override fun onCastSessionAvailable() {
            replaceableForwardingPlayer.setPlayer(castPlayer!!)
        }

        /**
         * Called when a Cast session has ended and the user wishes to control playback locally.
         */
        override fun onCastSessionUnavailable() {
            replaceableForwardingPlayer.setPlayer(exoPlayer)
        }
    }

    /** Listen for events from ExoPlayer. */
    private inner class PlayerEventListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(EVENT_POSITION_DISCONTINUITY)
                || events.contains(EVENT_MEDIA_ITEM_TRANSITION)
                || events.contains(EVENT_PLAY_WHEN_READY_CHANGED)) {
                currentMediaItemIndex = player.currentMediaItemIndex
                saveRecentSongToStorage()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            var message = R.string.generic_error;
            Log.e(TAG, "Player error: " + error.errorCodeName + " (" + error.errorCode + ")", error);
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                || error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
                message = R.string.error_media_not_found;
            }
            Toast.makeText(
                applicationContext,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

/** Content styling constants */
private const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
private const val CONTENT_STYLE_LIST = 1
private const val CONTENT_STYLE_GRID = 2

const val MEDIA_DESCRIPTION_EXTRAS_START_PLAYBACK_POSITION_MS = "playback_start_position_ms"

/**
 * Custom session command a controller sends to make the service re-download the remote catalog on
 * demand (used by the app's pull-to-refresh). Handled in [MusicService.MusicServiceCallback].
 */
const val COMMAND_REFRESH_CATALOG = "com.example.android.uamp.media.REFRESH_CATALOG"

/** How often the station catalog is re-downloaded while the service is alive. Tune as needed. */
private val CATALOG_REFRESH_INTERVAL_MS = TimeUnit.HOURS.toMillis(1)

private const val TAG = "MusicService"
