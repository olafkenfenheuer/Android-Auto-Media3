/*
 * Copyright 2022 Google Inc. All rights reserved.
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

import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION
import androidx.media3.common.Player.EVENT_MEDIA_METADATA_CHANGED
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import java.util.ArrayDeque
import kotlin.math.min

/**
 * A [Player] implementation that delegates to an actual [Player] implementation that is
 * replaceable by another instance by calling [setPlayer].
 */
class ReplaceableForwardingPlayer(private var player: Player) : Player {

    private val listeners: MutableList<Player.Listener> = arrayListOf()
    // After disconnecting from the Cast device, the timeline of the CastPlayer is empty, so we
    // need to track the playlist to be able to transfer the playlist back to the local player after
    // having disconnected.
    private val playlist: MutableList<MediaItem> = arrayListOf()
    private var currentMediaItemIndex: Int = 0
    // The "StreamTitle" (usually "Interpret - Titel") reported live by the currently playing ICY
    // stream. ExoPlayer delivers it via onMetadata but does not fold it into getMediaMetadata(), so
    // we track it here and merge it in [applyStreamTitle]. Reset whenever the media item changes.
    private var currentStreamTitle: String? = null

    private val playerListener: Player.Listener = PlayerListener()

    init {
        player.addListener(playerListener)
    }

    /** Sets a new [Player] instance to which the state of the previous player is transferred. */
    fun setPlayer(player: Player) {
        // Remove add all listeners before changing the player state.
        for (listener in listeners) {
            this.player.removeListener(listener)
            player.addListener(listener)
        }
        // Add/remove our listener we use to workaround the missing metadata support of CastPlayer.
        this.player.removeListener(playerListener)
        player.addListener(playerListener)

        player.repeatMode = this.player.repeatMode
        player.shuffleModeEnabled = this.player.shuffleModeEnabled
        player.playlistMetadata = this.player.playlistMetadata
        player.trackSelectionParameters = this.player.trackSelectionParameters
        player.volume = this.player.volume
        player.playWhenReady = this.player.playWhenReady

        // Prepare the new player.
        player.setMediaItems(playlist, currentMediaItemIndex, this.player.contentPosition)
        player.prepare()

        // Stop the previous player. Don't release so it can be used again.
        this.player.clearMediaItems()
        this.player.stop()

        this.player = player
    }

    override fun getApplicationLooper(): Looper {
        return player.applicationLooper
    }

    override fun addListener(listener: Player.Listener) {
        player.addListener(listener)
        listeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        player.removeListener(listener)
        listeners.remove(listener)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        player.setMediaItems(mediaItems)
        playlist.clear()
        playlist.addAll(mediaItems)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        player.setMediaItems(mediaItems, resetPosition)
        playlist.clear()
        playlist.addAll(mediaItems)
    }

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startWindowIndex: Int,
        startPositionMs: Long
    ) {
        player.setMediaItems(mediaItems, startWindowIndex, startPositionMs)
        playlist.clear()
        playlist.addAll(mediaItems)
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        player.setMediaItem(mediaItem)
        playlist.clear()
        playlist.add(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        player.setMediaItem(mediaItem, startPositionMs)
        playlist.clear()
        playlist.add(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        player.setMediaItem(mediaItem, resetPosition)
        playlist.clear()
        playlist.add(mediaItem)
    }

    override fun addMediaItem(mediaItem: MediaItem) {
        player.addMediaItem(mediaItem)
        playlist.add(mediaItem)
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        player.addMediaItem(index, mediaItem)
        playlist.add(index, mediaItem)
    }

    override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
        player.addMediaItems(mediaItems)
        playlist.addAll(mediaItems)
    }

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        player.addMediaItems(index, mediaItems)
        playlist.addAll(index, mediaItems)
    }

    override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
        player.moveMediaItem(currentIndex, newIndex)
        playlist.add(min(newIndex, playlist.size), playlist.removeAt(currentIndex))
    }

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
        val removedItems: ArrayDeque<MediaItem> = ArrayDeque()
        val removedItemsLength = toIndex - fromIndex
        for (i in removedItemsLength - 1 downTo 0) {
            removedItems.addFirst(playlist.removeAt(fromIndex + i))
        }
        playlist.addAll(min(newIndex, playlist.size), removedItems)
    }

    override fun removeMediaItem(index: Int) {
        player.removeMediaItem(index)
        playlist.removeAt(index)
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        player.removeMediaItems(fromIndex, toIndex)
        val removedItemsLength = toIndex - fromIndex
        for (i in removedItemsLength - 1 downTo 0) {
            playlist.removeAt(fromIndex + i)
        }
    }

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        player.replaceMediaItem(index, mediaItem)
        if (index < playlist.size) {
            playlist[index] = mediaItem
        }
    }

    override fun replaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: MutableList<MediaItem>
    ) {
        player.replaceMediaItems(fromIndex, toIndex, mediaItems)
        val clampedToIndex = min(toIndex, playlist.size)
        for (i in clampedToIndex - 1 downTo fromIndex) {
            playlist.removeAt(i)
        }
        playlist.addAll(min(fromIndex, playlist.size), mediaItems)
    }

    override fun clearMediaItems() {
        player.clearMediaItems()
        playlist.clear()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        return player.isCommandAvailable(command)
    }

    override fun canAdvertiseSession(): Boolean {
        return player.canAdvertiseSession()
    }

    override fun getAvailableCommands(): Player.Commands {
        return player.availableCommands
    }

    override fun prepare() {
        player.prepare()
    }

    override fun getPlaybackState(): Int {
        return player.playbackState
    }

    override fun getPlaybackSuppressionReason(): Int {
        return player.playbackSuppressionReason
    }

    override fun isPlaying(): Boolean {
        return player.isPlaying
    }

    override fun getPlayerError(): PlaybackException? {
        return player.playerError
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        player.playWhenReady = playWhenReady
    }

    override fun getPlayWhenReady(): Boolean {
        return player.playWhenReady
    }

    override fun setRepeatMode(repeatMode: Int) {
        player.repeatMode = repeatMode
    }

    override fun getRepeatMode(): Int {
        return player.repeatMode
    }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        player.shuffleModeEnabled = shuffleModeEnabled
    }

    override fun getShuffleModeEnabled(): Boolean {
        return player.shuffleModeEnabled
    }

    override fun isLoading(): Boolean {
        return player.isLoading
    }

    override fun seekToDefaultPosition() {
        player.seekToDefaultPosition()
    }

    override fun seekToDefaultPosition(windowIndex: Int) {
        player.seekToDefaultPosition(windowIndex)
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun seekTo(windowIndex: Int, positionMs: Long) {
        player.seekTo(windowIndex, positionMs)
    }

    override fun getSeekBackIncrement(): Long {
        return player.seekBackIncrement
    }

    override fun seekBack() {
        player.seekBack()
    }

    override fun getSeekForwardIncrement(): Long {
        return player.seekForwardIncrement
    }

    override fun seekForward() {
        player.seekForward()
    }

    @OptIn(UnstableApi::class)
    override fun hasPrevious(): Boolean {
        return player.hasPrevious()
    }

    @OptIn(UnstableApi::class)
    override fun hasPreviousWindow(): Boolean {
        return player.hasPreviousWindow()
    }

    override fun hasPreviousMediaItem(): Boolean {
        return player.hasPreviousMediaItem()
    }

    @OptIn(UnstableApi::class)
    override fun previous() {
        player.previous()
    }

    @OptIn(UnstableApi::class)
    override fun seekToPreviousWindow() {
        player.seekToPreviousWindow()
    }

    override fun seekToPreviousMediaItem() {
        player.seekToPreviousMediaItem()
    }

    override fun getMaxSeekToPreviousPosition(): Long {
        return player.maxSeekToPreviousPosition
    }

    override fun seekToPrevious() {
        player.seekToPrevious()
    }

    @OptIn(UnstableApi::class)
    override fun hasNext(): Boolean {
        return player.hasNext()
    }

    @OptIn(UnstableApi::class)
    override fun hasNextWindow(): Boolean {
        return player.hasNextWindow()
    }

    override fun hasNextMediaItem(): Boolean {
        return player.hasNextMediaItem()
    }

    @OptIn(UnstableApi::class)
    override fun next() {
        player.next()
    }

    @OptIn(UnstableApi::class)
    override fun seekToNextWindow() {
        player.seekToNextWindow()
    }

    override fun seekToNextMediaItem() {
        player.seekToNextMediaItem()
    }

    override fun seekToNext() {
        player.seekToNext()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        player.playbackParameters = playbackParameters
    }

    override fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return player.playbackParameters
    }

    override fun stop() {
        player.stop()
    }

    override fun release() {
        player.release()
        playlist.clear()
    }

    override fun getCurrentTracks(): Tracks {
        return player.currentTracks
    }

    override fun getTrackSelectionParameters(): TrackSelectionParameters {
        return player.trackSelectionParameters
    }

    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {
        player.trackSelectionParameters = parameters
    }

    override fun getMediaMetadata(): MediaMetadata {
        return applyStreamTitle(player.mediaMetadata)
    }

    /**
     * Overlays the song currently on air (reported live by the stream as ICY metadata, see
     * [currentStreamTitle]) on top of the static station metadata. The ICY "StreamTitle" is a single
     * "Interpret - Titel" string, so it is split into [MediaMetadata.artist] and
     * [MediaMetadata.title] and the station name is preserved in [MediaMetadata.station]. Returns the
     * unchanged station metadata when the stream reports no song (or only echoes the station name).
     *
     * A few stations (see [REVERSED_TITLE_STATIONS]) send "Titel - Interpret" instead, so the two
     * halves are swapped for them.
     */
    private fun applyStreamTitle(base: MediaMetadata): MediaMetadata {
        val streamTitle = currentStreamTitle?.trim()
        if (streamTitle.isNullOrEmpty() || streamTitle == base.title?.toString()) {
            return base
        }
        val builder = base.buildUpon().setStation(base.title)
        val separator = streamTitle.indexOf(" - ")
        if (separator > 0) {
            val left = streamTitle.substring(0, separator).trim()
            val right = streamTitle.substring(separator + 3).trim()
            if (base.title?.toString() in REVERSED_TITLE_STATIONS) {
                builder.setSongTitle(left).setArtist(right).setSubtitle(right)
            } else {
                builder.setArtist(left).setSubtitle(left).setSongTitle(right)
            }
        } else {
            builder.setArtist(null).setSubtitle(null).setSongTitle(streamTitle)
        }
        return builder.build()
    }

    /**
     * Sets the song title in both [MediaMetadata.title] and [MediaMetadata.displayTitle]. The static
     * catalog metadata puts the station name in `displayTitle` (see JsonSource), and Android Auto's
     * "Now Playing" screen prefers `displayTitle`/`subtitle` over `title`/`artist`. Overwriting only
     * `title` therefore left Android Auto showing the station name instead of the current song, so we
     * update both fields here.
     */
    private fun MediaMetadata.Builder.setSongTitle(title: String) =
        setTitle(title).setDisplayTitle(title)

    override fun getPlaylistMetadata(): MediaMetadata {
        return player.playlistMetadata
    }

    override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) {
        player.playlistMetadata = mediaMetadata
    }

    @OptIn(UnstableApi::class)
    override fun getCurrentManifest(): Any? {
        return player.currentManifest
    }

    override fun getCurrentTimeline(): Timeline {
        return player.currentTimeline
    }

    override fun getCurrentPeriodIndex(): Int {
        return player.currentPeriodIndex
    }

    @OptIn(UnstableApi::class)
    override fun getCurrentWindowIndex(): Int {
        return player.currentWindowIndex
    }

    override fun getCurrentMediaItemIndex(): Int {
        return player.currentMediaItemIndex
    }

    @OptIn(UnstableApi::class)
    override fun getNextWindowIndex(): Int {
        return player.nextWindowIndex
    }

    override fun getNextMediaItemIndex(): Int {
        return player.nextMediaItemIndex
    }

    @OptIn(UnstableApi::class)
    override fun getPreviousWindowIndex(): Int {
        return player.previousWindowIndex
    }

    override fun getPreviousMediaItemIndex(): Int {
        return player.previousMediaItemIndex
    }

    override fun getCurrentMediaItem(): MediaItem? {
        return player.currentMediaItem
    }

    override fun getMediaItemCount(): Int {
        return player.mediaItemCount
    }

    override fun getMediaItemAt(index: Int): MediaItem {
        return player.getMediaItemAt(index)
    }

    override fun getDuration(): Long {
        return player.duration
    }

    override fun getCurrentPosition(): Long {
        return player.currentPosition
    }

    override fun getBufferedPosition(): Long {
        return player.bufferedPosition
    }

    override fun getBufferedPercentage(): Int {
        return player.bufferedPercentage
    }

    override fun getTotalBufferedDuration(): Long {
        return player.totalBufferedDuration
    }

    @OptIn(UnstableApi::class)
    override fun isCurrentWindowDynamic(): Boolean {
        return player.isCurrentWindowDynamic
    }

    override fun isCurrentMediaItemDynamic(): Boolean {
        return player.isCurrentMediaItemDynamic
    }

    @OptIn(UnstableApi::class)
    override fun isCurrentWindowLive(): Boolean {
        return player.isCurrentWindowLive
    }

    override fun isCurrentMediaItemLive(): Boolean {
        return player.isCurrentMediaItemLive
    }

    override fun getCurrentLiveOffset(): Long {
        return player.currentLiveOffset
    }

    @OptIn(UnstableApi::class)
    override fun isCurrentWindowSeekable(): Boolean {
        return player.isCurrentWindowSeekable
    }

    override fun isCurrentMediaItemSeekable(): Boolean {
        return player.isCurrentMediaItemSeekable
    }

    override fun isPlayingAd(): Boolean {
        return player.isPlayingAd
    }

    override fun getCurrentAdGroupIndex(): Int {
        return player.currentAdGroupIndex
    }

    override fun getCurrentAdIndexInAdGroup(): Int {
        return player.currentAdIndexInAdGroup
    }

    override fun getContentDuration(): Long {
        return player.contentDuration
    }

    override fun getContentPosition(): Long {
        return player.contentPosition
    }

    override fun getContentBufferedPosition(): Long {
        return player.contentBufferedPosition
    }

    override fun getAudioAttributes(): AudioAttributes {
        return player.audioAttributes
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) {
        player.setAudioAttributes(audioAttributes, handleAudioFocus)
    }

    override fun setVolume(volume: Float) {
        player.volume = volume
    }

    override fun getVolume(): Float {
        return player.volume
    }

    override fun clearVideoSurface() {
        player.clearVideoSurface()
    }

    override fun clearVideoSurface(surface: Surface?) {
        player.clearVideoSurface(surface)
    }

    override fun setVideoSurface(surface: Surface?) {
        player.setVideoSurface(surface)
    }

    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        player.setVideoSurfaceHolder(surfaceHolder)
    }

    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        player.clearVideoSurfaceHolder(surfaceHolder)
    }

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
        return player.setVideoSurfaceView(surfaceView)
    }

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
        return player.clearVideoSurfaceView(surfaceView)
    }

    override fun setVideoTextureView(textureView: TextureView?) {
        return player.setVideoTextureView(textureView)
    }

    override fun clearVideoTextureView(textureView: TextureView?) {
        return player.clearVideoTextureView(textureView)
    }

    override fun getVideoSize(): VideoSize {
        return player.videoSize
    }

    @OptIn(UnstableApi::class)
    override fun getSurfaceSize(): Size {
        return player.surfaceSize
    }

    override fun getCurrentCues(): CueGroup {
        return player.currentCues
    }

    override fun getDeviceInfo(): DeviceInfo {
        return player.deviceInfo
    }

    override fun getDeviceVolume(): Int {
        return player.deviceVolume
    }

    override fun isDeviceMuted(): Boolean {
        return player.isDeviceMuted
    }

    override fun setDeviceVolume(volume: Int) {
        player.deviceVolume = volume
    }

    override fun setDeviceVolume(volume: Int, flags: Int) {
        player.setDeviceVolume(volume, flags)
    }

    override fun increaseDeviceVolume() {
        player.increaseDeviceVolume()
    }

    override fun increaseDeviceVolume(flags: Int) {
        player.increaseDeviceVolume(flags)
    }

    override fun decreaseDeviceVolume() {
        player.decreaseDeviceVolume()
    }

    override fun decreaseDeviceVolume(flags: Int) {
        player.decreaseDeviceVolume(flags)
    }

    override fun setDeviceMuted(muted: Boolean) {
        player.isDeviceMuted = muted
    }

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        player.setDeviceMuted(muted, flags)
    }

    private inner class PlayerListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(EVENT_MEDIA_ITEM_TRANSITION)) {
                // A new station is playing; drop the previous station's song info.
                currentStreamTitle = null
            }
            if (events.contains(EVENT_MEDIA_ITEM_TRANSITION)
                && !events.contains(EVENT_MEDIA_METADATA_CHANGED)) {
                // CastPlayer does not support onMetaDataChange. We can trigger this here when the
                // media item changes.
                if (playlist.isNotEmpty()) {
                    for (listener in listeners) {
                        listener.onMediaMetadataChanged(
                            playlist[player.currentMediaItemIndex].mediaMetadata
                        )
                    }
                }
            }
            if (events.contains(EVENT_POSITION_DISCONTINUITY)
                || events.contains(EVENT_MEDIA_ITEM_TRANSITION)
                || events.contains(EVENT_TIMELINE_CHANGED)) {
                if (!player.currentTimeline.isEmpty) {
                    currentMediaItemIndex = player.currentMediaItemIndex
                }
            }
        }

        override fun onMetadata(metadata: Metadata) {
            // Live radio streams report the current song via ICY "StreamTitle" metadata. ExoPlayer
            // surfaces it here but does not fold it into getMediaMetadata(), so extract it (via the
            // generic Metadata.Entry -> MediaMetadata population), remember it and notify listeners
            // so the session/UI refresh with the new song.
            val builder = MediaMetadata.Builder()
            for (i in 0 until metadata.length()) {
                metadata.get(i).populateMediaMetadata(builder)
            }
            val streamTitle = builder.build().title?.toString()
            if (!streamTitle.isNullOrEmpty() && streamTitle != currentStreamTitle) {
                currentStreamTitle = streamTitle
                val merged = mediaMetadata
                for (listener in listeners) {
                    listener.onMediaMetadataChanged(merged)
                }
            }
        }
    }

    companion object {
        // Stations whose ICY StreamTitle is "Titel - Interpret" instead of the usual
        // "Interpret - Titel" (matched by station name); their two halves are swapped.
        private val REVERSED_TITLE_STATIONS = setOf("NDR 2")
    }
}