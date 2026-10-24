package com.netmusiclite.ui.m3e

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import com.netmusiclite.data.SessionStore
import com.netmusiclite.data.Song

/** Keep pagination and its saved list anchor together in the home back-stack entry. */
@Composable
internal fun rememberHomeRecommendations(
    loggedIn: Boolean,
    accountId: Long = SessionStore.uid,
    sessionEpoch: Int = SessionStore.epoch,
): MutableState<List<Song>> {
    val saver = remember(loggedIn, accountId, sessionEpoch) {
        listSaver<List<Song>, Any>(
            save = { songs ->
                listOf(loggedIn, accountId, sessionEpoch) + songs.flatMap { song ->
                    listOf(song.id, song.title, song.artist, song.artistId, song.album,
                        song.albumId, song.durationMs, song.coverUrl ?: false, ArrayList(song.aliases))
                }
            },
            restore = { saved ->
                // rememberSaveable inputs reset live state, but restored inputs are not validated
                // by Compose. Verify the saved owner too when an account changed while away.
                if (saved.take(3) != listOf(loggedIn, accountId, sessionEpoch)) emptyList()
                else saved.drop(3).chunked(9).map { fields ->
                    Song(
                        id = fields[0] as Long, title = fields[1] as String,
                        artist = fields[2] as String, artistId = fields[3] as Long,
                        album = fields[4] as String, albumId = fields[5] as Long,
                        durationMs = fields[6] as Int,
                        coverUrl = fields[7] as? String,
                        aliases = (fields[8] as ArrayList<*>).map { it as String },
                    )
                }
            },
        )
    }
    return rememberSaveable(loggedIn, accountId, sessionEpoch, stateSaver = saver) {
        mutableStateOf(emptyList())
    }
}
