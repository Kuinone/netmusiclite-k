package com.netmusiclite.ui.screens.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.netmusiclite.data.DownloadStore
import com.netmusiclite.data.LocalLyricMatch
import com.netmusiclite.data.LocalLyricStore
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 歌词页与 M3E 更多页共用发音开关和数据可用性；切页不会重置开关。 */
object LyricsPronunciationState {
    var enabled by mutableStateOf(false)
    private val availability = mutableStateMapOf<Long, Boolean>()

    fun hasData(songId: Long): Boolean = availability[songId] == true

    fun record(songId: Long, available: Boolean) {
        availability[songId] = available
    }

    suspend fun ensureAvailable(context: Context, song: Song) {
        // The API also reports transport failures as an empty bundle. Retry negative results
        // when reopening More so an offline check cannot permanently hide pronunciation.
        if (availability[song.id] == true) return
        try {
            val available = withContext(Dispatchers.IO) {
                val local = DownloadStore.localRomaText(song.id)
                val raw = when {
                    !local.isNullOrBlank() -> local
                    song.id > 0 -> NcmApi.lyricV1(song.id).roma
                    LocalLyricStore.read(context, song) == null -> LocalLyricMatch.find(song)?.bundle?.roma
                    else -> null
                }
                raw != null && parseLrc(raw).isNotEmpty()
            }
            record(song.id, available)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 网络失败不缓存为无发音；下次打开更多页可以重试。
        }
    }
}
