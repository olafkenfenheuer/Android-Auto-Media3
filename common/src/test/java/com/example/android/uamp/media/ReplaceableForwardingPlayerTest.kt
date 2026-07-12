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

import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ReplaceableForwardingPlayer.mergeStreamTitle], which folds the live ICY
 * "StreamTitle" of a radio stream into the static station metadata.
 */
// Robolectric 4.13 supports up to SDK 34, while the app targets SDK 35; pin the emulated SDK so
// these (framework-free) tests run regardless of the project's targetSdk.
@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ReplaceableForwardingPlayerTest {

    /** Static catalog metadata for a station, mirroring what [JsonSource] produces. */
    private fun station(name: String) =
        MediaMetadata.Builder().setTitle(name).setDisplayTitle(name).build()

    @Test
    fun `reversed-title station NDR 2 swaps interpret and title`() {
        // NDR 2 sends "Titel - Interpret" instead of the usual "Interpret - Titel".
        val merged = ReplaceableForwardingPlayer.mergeStreamTitle(
            station("NDR 2"),
            "Blinding Lights - The Weeknd"
        )

        assertEquals("The Weeknd", merged.artist.toString())
        assertEquals("Blinding Lights", merged.title.toString())
        // Android Auto reads displayTitle/subtitle, so they must carry the same split.
        assertEquals("The Weeknd", merged.subtitle.toString())
        assertEquals("Blinding Lights", merged.displayTitle.toString())
        // The station name is preserved separately.
        assertEquals("NDR 2", merged.station.toString())
    }

    @Test
    fun `normal station keeps interpret then title order`() {
        // A regular station (e.g. WDR 2) sends "Interpret - Titel".
        val merged = ReplaceableForwardingPlayer.mergeStreamTitle(
            station("WDR 2"),
            "The Weeknd - Blinding Lights"
        )

        assertEquals("The Weeknd", merged.artist.toString())
        assertEquals("Blinding Lights", merged.title.toString())
        assertEquals("The Weeknd", merged.subtitle.toString())
        assertEquals("Blinding Lights", merged.displayTitle.toString())
        assertEquals("WDR 2", merged.station.toString())
    }

    @Test
    fun `stream title without separator becomes title only`() {
        val merged = ReplaceableForwardingPlayer.mergeStreamTitle(
            station("WDR 2"),
            "Alle Staus im Westen auch in der WDR 2 App"
        )

        assertNull(merged.artist)
        assertNull(merged.subtitle)
        assertEquals("Alle Staus im Westen auch in der WDR 2 App", merged.title.toString())
        assertEquals("Alle Staus im Westen auch in der WDR 2 App", merged.displayTitle.toString())
    }

    @Test
    fun `blank or station-echo stream title leaves station metadata untouched`() {
        val base = station("WDR 2")

        assertSame(base, ReplaceableForwardingPlayer.mergeStreamTitle(base, null))
        assertSame(base, ReplaceableForwardingPlayer.mergeStreamTitle(base, "   "))
        // Some stations just echo their own name; treat that as "no song".
        assertSame(base, ReplaceableForwardingPlayer.mergeStreamTitle(base, "WDR 2"))
    }
}
