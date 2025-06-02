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

package com.example.android.uamp.media.library

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Source of [MediaMetadataCompat] objects created from a basic JSON stream.
 *
 * The definition of the JSON is specified in the docs of [JsonMusic] in this file,
 * which is the object representation of it.
 */
internal class JsonSource(private val source: Uri) : AbstractMusicSource() {

    companion object {
        const val ORIGINAL_ARTWORK_URI_KEY = "com.example.android.uamp.JSON_ARTWORK_URI"
    }

    private var catalog: List<androidx.media3.common.MediaItem> = emptyList()

    init {
        state = STATE_INITIALIZING
    }

    override fun iterator(): Iterator<MediaItem> = catalog.iterator()

    override suspend fun load() {
        updateCatalog(source)?.let { updatedCatalog ->
            catalog = updatedCatalog
            state = STATE_INITIALIZED
        } ?: run {
            catalog = emptyList()
            state = STATE_ERROR
        }
    }

    /**
     * Function to connect to a remote URI and download/process the JSON file that corresponds to
     * [MediaMetadataCompat] objects.
     */
    private suspend fun updateCatalog(catalogUri: Uri): List<MediaItem>? {
        return withContext(Dispatchers.IO) {
            val musicCat = try {
                downloadJson(catalogUri)
            } catch (ioException: IOException) {
                return@withContext null
            }

            // Get the base URI to fix up relative references later.
            val baseUri = catalogUri.toString().removeSuffix(catalogUri.lastPathSegment ?: "")

            musicCat.music.map { song ->
                // The JSON may have paths that are relative to the source of the JSON
                // itself. We need to fix them up here to turn them into absolute paths.
                catalogUri.scheme?.let { scheme ->
                    if (!song.source.startsWith(scheme)) {
                        song.source = baseUri + song.source
                    }
                    if (!song.image.startsWith(scheme)) {
                        song.image = baseUri + song.image
                    }
                }

                val jsonImageUri = Uri.parse(song.image)
                val imageUri = AlbumArtContentProvider.mapUri(jsonImageUri)
                val mediaMetadata = MediaMetadata.Builder()
                    .from(song)
                    .apply {
                        setArtworkUri(imageUri) // Used by ExoPlayer and Notification
                        // Keep the original artwork URI for being included in Cast metadata object.
                        val extras = Bundle()
                        extras.putString(ORIGINAL_ARTWORK_URI_KEY, jsonImageUri.toString())
                        setExtras(extras)
                    }
                    .build()
                MediaItem.Builder()
                    .apply {
                        setMediaId(song.id)
                        setUri(song.source)
                        setMimeType(MimeTypes.AUDIO_MPEG)
                        setMediaMetadata(mediaMetadata)
                    }.build()
            }.toList()
        }
    }


    /**
     * Attempts to download a catalog from a given Uri.
     *
     * @param catalogUri URI to attempt to download the catalog form.
     * @return The catalog downloaded, or an empty catalog if an error occurred.
     */
    @Throws(IOException::class)
    /*
    private fun downloadJson(catalogUri: Uri): JsonCatalog {
        val catalogConn = URL(catalogUri.toString())
        val reader = BufferedReader(InputStreamReader(catalogConn.openStream()))
        return Gson().fromJson(reader, JsonCatalog::class.java)
    }
}
*/
private fun downloadJson(catalogUri: Uri): JsonCatalog {
    val catalogConn = URL(catalogUri.toString())
    val reader =  try {
        BufferedReader(InputStreamReader(catalogConn.openStream()))
    } catch (ioException: IOException) {
        BufferedReader(InputStreamReader(localJson.byteInputStream(Charset.defaultCharset())))
    }

    return Gson().fromJson(reader, JsonCatalog::class.java)
}
}



private var localJson = "{\n" +
        "  \"music\": [\n" +
        "    {\n" +
        "      \"id\": \"wake_up_01\",\n" +
        "      \"title\": \"WDR 2\",\n" +
        "      \"album\": \"WDR\",\n" +
        "      \"artist\": \"WDR\",\n" +
        "      \"genre\": \"Pop\",\n" +
        "      \"source\": \"https://wdr-wdr2-rheinland.icecastssl.wdr.de/wdr/wdr2/rheinland/mp3/128/stream.mp3\",\n" +
        "      \"image\": \"https://www1.wdr.de/mediathek/audio/sendereihen-bilder/wdr2-default100~_v-gseaclassicxl.jpg\",\n" +
        "      \"trackNumber\": 1,\n" +
        "      \"totalTrackCount\": 1,\n" +
        "      \"duration\": -1,\n" +
        "      \"site\": \"http://wdr2.de\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": \"wake_up_02\",\n" +
        "      \"title\": \"1LIVE\",\n" +
        "      \"album\": \"1LIVE\",\n" +
        "      \"artist\": \"WDR\",\n" +
        "      \"genre\": \"Pop\",\n" +
        "      \"source\": \"https://wdr-1live-live.icecastssl.wdr.de/wdr/1live/live/mp3/128/stream.mp3\",\n" +
        "      \"image\": \"https://www1.wdr.de/radio/1live/einslive124~_v-gseaclassicxl.jpg\",\n" +
        "      \"trackNumber\": 1,\n" +
        "      \"totalTrackCount\": 1,\n" +
        "      \"duration\": -1,\n" +
        "      \"site\": \"https://www1.wdr.de/radio/1live/\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": \"wake_up_03\",\n" +
        "      \"title\": \"1LIVE DIGGI\",\n" +
        "      \"album\": \"1LIVE\",\n" +
        "      \"artist\": \"WDR\",\n" +
        "      \"genre\": \"Pop\",\n" +
        "      \"source\": \"https://wdr-1live-diggi.icecastssl.wdr.de/wdr/1live/diggi/mp3/128/stream.mp3\",\n" +
        "      \"image\": \"https://www1.wdr.de/radio/player/tva-export/1live-diggi-106~_v-Podcast.jpg\",\n" +
        "      \"trackNumber\": 1,\n" +
        "      \"totalTrackCount\": 1,\n" +
        "      \"duration\": -1,\n" +
        "      \"site\": \"https://www1.wdr.de/radio/1live/\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": \"wake_up_04\",\n" +
        "      \"title\": \"WDR4\",\n" +
        "      \"album\": \"WDR\",\n" +
        "      \"artist\": \"WDR\",\n" +
        "      \"genre\": \"Pop\",\n" +
        "      \"source\": \"https://wdr-wdr4-live.icecastssl.wdr.de/wdr/wdr4/live/mp3/128/stream.mp3\",\n" +
        "      \"image\": \"https://www1.wdr.de/radio/wdr4/wdrvier_logo104~_v-gseaclassicxl.jpg\" ,\n" +
        "      \"trackNumber\": 1,\n" +
        "      \"totalTrackCount\": 1,\n" +
        "      \"duration\": -1,\n" +
        "      \"site\": \"https://www.wdr.de/\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": \"wake_up_05\",\n" +
        "      \"title\": \"WDR5\",\n" +
        "      \"album\": \"WDR\",\n" +
        "      \"artist\": \"WDR\",\n" +
        "      \"genre\": \"Pop\",\n" +
        "      \"source\": \"https://wdr-wdr5-live.icecastssl.wdr.de/wdr/wdr5/live/mp3/128/stream.mp3\",\n" +
        "      \"image\": \"https://www1.wdr.de/radio/startseite/symbolbilder/wellen-logo100~_v-gseaclassicxl.jpg\" ,\n" +
        "      \"trackNumber\": 1,\n" +
        "      \"totalTrackCount\": 1,\n" +
        "      \"duration\": -1,\n" +
        "      \"site\": \"https://www.wdr.de/\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": \"wake_up_06\",\n" +
        "      \"title\": \"WDR COSMO\",\n" +
        "      \"album\": \"WDR\",\n" +
        "      \"artist\": \"WDR\",\n" +
        "      \"genre\": \"Pop\",\n" +
        "      \"source\": \"https://wdr-cosmo-live.icecastssl.wdr.de/wdr/cosmo/live/mp3/128/stream.mp3\",\n" +
        "      \"image\": \"https://www1.wdr.de/mediathek/audio/sendereihen-bilder/cosmo-logo108~_v-gseaclassicxl.jpg\" ,\n" +
        "      \"trackNumber\": 1,\n" +
        "      \"totalTrackCount\": 1,\n" +
        "      \"duration\": -1,\n" +
        "      \"site\": \"https://www.wdr.de/\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": \"wake_up_07\",\n" +
        "      \"title\": \"Bremen 4\",\n" +
        "      \"album\": \"Bremen 4\",\n" +
        "      \"artist\": \"Bremen\",\n" +
        "      \"genre\": \"Pop\",\n" +
        "      \"source\": \"https://icecast.radiobremen.de/rb/bremenvier/live/mp3/128/stream.mp3\",\n" +
        "      \"image\": \"https://dein.radiobremen.de/bilder/logo-bremenvier-100~_v-512x288_c-1589529238677.jpg\" ,\n" +
        "      \"trackNumber\": 1,\n" +
        "      \"totalTrackCount\": 1,\n" +
        "      \"duration\": -1,\n" +
        "      \"site\": \"https://www.wdr.de/\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": \"wake_up_09\",\n" +
        "      \"title\": \"R.SH\",\n" +
        "      \"album\": \"R.SH\",\n" +
        "      \"artist\": \"R.SH\",\n" +
        "      \"genre\": \"Pop\",\n" +
        "      \"source\": \"https://rsh.hoerradar.de/rsh128\",\n" +
        "      \"image\": \"https://www.regiocast.de/wp-content/uploads/2019/10/R.SH_Logo.jpg\" ,\n" +
        "      \"trackNumber\": 1,\n" +
        "      \"totalTrackCount\": 1,\n" +
        "      \"duration\": -1,\n" +
        "      \"site\": \"https://www.wdr.de/\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": \"wake_up_08\",\n" +
        "      \"title\": \"R.SH Sylt\",\n" +
        "      \"album\": \"R.SH\",\n" +
        "      \"artist\": \"R.SH Sylt\",\n" +
        "      \"genre\": \"Pop\",\n" +
        "      \"source\": \"https://rsh.hoerradar.de/rsh-sylt-mp3-mq\",\n" +
        "      \"image\": \"https://liveradio.de/media/cache/station_detail/600x600%20px.jpg\" ,\n" +
        "      \"trackNumber\": 1,\n" +
        "      \"totalTrackCount\": 1,\n" +
        "      \"duration\": -1,\n" +
        "      \"site\": \"https://www.wdr.de/\"\n" +
        "    }\n" +
        "\n" +
        "\n" +
        "  ]\n" +
        "}";



/**
 * Extension method for [MediaMetadataCompat.Builder] to set the fields from
 * our JSON constructed object (to make the code a bit easier to see).
 */
fun MediaMetadata.Builder.from(jsonMusic: JsonMusic): MediaMetadata.Builder {
    setTitle(jsonMusic.title)
    setDisplayTitle(jsonMusic.title)
    setArtist(jsonMusic.artist)
    setAlbumTitle(jsonMusic.album)
    setGenre(jsonMusic.genre)
    setArtworkUri(Uri.parse(jsonMusic.image))
    setTrackNumber(jsonMusic.trackNumber.toInt())
    setTotalTrackCount(jsonMusic.totalTrackCount.toInt())
    setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
    setIsPlayable(true)
    // The duration from the JSON is given in seconds, but the rest of the code works in
    // milliseconds. Here's where we convert to the proper units.
    val durationMs = TimeUnit.SECONDS.toMillis(jsonMusic.duration)
    val bundle = Bundle()
    bundle.putLong("durationMs", durationMs)
    return this
}

/**
 * Wrapper object for our JSON in order to be processed easily by GSON.
 */
class JsonCatalog {
    var music: List<JsonMusic> = ArrayList()
}

/**
 * An individual piece of music included in our JSON catalog.
 * The format from the server is as specified:
 * ```
 *     { "music" : [
 *     { "title" : // Title of the piece of music
 *     "album" : // Album title of the piece of music
 *     "artist" : // Artist of the piece of music
 *     "genre" : // Primary genre of the music
 *     "source" : // Path to the music, which may be relative
 *     "image" : // Path to the art for the music, which may be relative
 *     "trackNumber" : // Track number
 *     "totalTrackCount" : // Track count
 *     "duration" : // Duration of the music in seconds
 *     "site" : // Source of the music, if applicable
 *     }
 *     ]}
 * ```
 *
 * `source` and `image` can be provided in either relative or
 * absolute paths. For example:
 * ``
 *     "source" : "https://www.example.com/music/ode_to_joy.mp3",
 *     "image" : "ode_to_joy.jpg"
 * ``
 *
 * The `source` specifies the full URI to download the piece of music from, but
 * `image` will be fetched relative to the path of the JSON file itself. This means
 * that if the JSON was at "https://www.example.com/json/music.json" then the image would be found
 * at "https://www.example.com/json/ode_to_joy.jpg".
 */
@Suppress("unused")
class JsonMusic {
    var id: String = ""
    var title: String = ""
    var album: String = ""
    var artist: String = ""
    var genre: String = ""
    var source: String = ""
    var image: String = ""
    var trackNumber: Long = 0
    var totalTrackCount: Long = 0
    var duration: Long = -1
    var site: String = ""
}
