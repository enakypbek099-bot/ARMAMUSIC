package kz.armamus.app.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** Плейлист пользователя: хранит только id треков библиотеки. */
data class Playlist(
    val id: String,
    val name: String,
    val trackIds: List<String>
)

/**
 * Постоянные пользовательские данные ARMAMUS: избранное (новые сверху, как в HTML),
 * плейлисты, тема оформления и скорость воспроизведения.
 */
class UserDataStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _favorites = MutableStateFlow(readIds(KEY_FAVORITES))
    val favorites: StateFlow<List<String>> = _favorites.asStateFlow()

    private val _playlists = MutableStateFlow(readPlaylists())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _theme = MutableStateFlow(
        prefs.getString(KEY_THEME, THEME_DARK) ?: THEME_DARK
    )

    /** "dark" или "light". */
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _speed = MutableStateFlow(
        prefs.getFloat(KEY_SPEED, 1f)
    )

    val speed: StateFlow<Float> = _speed.asStateFlow()

    @Synchronized
    fun toggleFavorite(trackId: String) {
        val current = _favorites.value

        _favorites.value =
            if (trackId in current) {
                current - trackId
            } else {
                listOf(trackId) + current
            }

        writeIds(KEY_FAVORITES, _favorites.value)
    }

    @Synchronized
    fun createPlaylist(name: String): Playlist {
        val playlist = Playlist(
            UUID.randomUUID().toString().take(8),
            name,
            emptyList()
        )

        _playlists.value = _playlists.value + playlist

        writePlaylists()

        return playlist
    }

    @Synchronized
    fun addToPlaylist(playlistId: String, trackId: String) {
        _playlists.value = _playlists.value.map { playlist ->
            if (
                playlist.id == playlistId &&
                trackId !in playlist.trackIds
            ) {
                playlist.copy(
                    trackIds = playlist.trackIds + trackId
                )
            } else {
                playlist
            }
        }

        writePlaylists()
    }

    @Synchronized
    fun removeFromPlaylist(
        playlistId: String,
        trackId: String
    ) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(
                    trackIds = playlist.trackIds - trackId
                )
            } else {
                playlist
            }
        }

        writePlaylists()
    }

    @Synchronized
    fun deletePlaylist(playlistId: String) {
        _playlists.value =
            _playlists.value.filter { it.id != playlistId }

        writePlaylists()
    }

    /** Убирает трек из избранного и из всех плейлистов (удаление из медиатеки). */
    @Synchronized
    fun purgeTrack(trackId: String) {
        _favorites.value =
            _favorites.value.filter { it != trackId }

        writeIds(
            KEY_FAVORITES,
            _favorites.value
        )

        _playlists.value =
            _playlists.value.map {
                it.copy(
                    trackIds = it.trackIds - trackId
                )
            }

        writePlaylists()
    }

    @Synchronized
    fun setTheme(theme: String) {
        _theme.value = theme

        prefs.edit()
            .putString(KEY_THEME, theme)
            .apply()
    }

    @Synchronized
    fun setSpeed(speed: Float) {
        _speed.value = speed

        prefs.edit()
            .putFloat(KEY_SPEED, speed)
            .apply()
    }

    /** Очистка избранного и плейлистов; тема и скорость остаются, как в HTML. */
    @Synchronized
    fun clearLibraryData() {
        _favorites.value = emptyList()
        _playlists.value = emptyList()

        writeIds(
            KEY_FAVORITES,
            emptyList()
        )

        writePlaylists()
    }

    private fun readIds(key: String): List<String> {
        val raw =
            prefs.getString(key, null)
                ?: return emptyList()

        return try {
            val array = JSONArray(raw)

            List(array.length()) { index ->
                array.getString(index)
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun writeIds(
        key: String,
        values: List<String>
    ) {
        prefs.edit()
            .putString(
                key,
                JSONArray(values).toString()
            )
            .apply()
    }

    private fun readPlaylists(): List<Playlist> {
        val raw =
            prefs.getString(KEY_PLAYLISTS, null)
                ?: return emptyList()

        return try {
            val array = JSONArray(raw)

            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val ids = item.getJSONArray("ids")

                Playlist(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    trackIds = List(ids.length()) {
                        ids.getString(it)
                    }
                )
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun writePlaylists() {
        val array = JSONArray()

        for (playlist in _playlists.value) {
            array.put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("name", playlist.name)
                    .put(
                        "ids",
                        JSONArray(playlist.trackIds)
                    )
            )
        }

        prefs.edit()
            .putString(
                KEY_PLAYLISTS,
                array.toString()
            )
            .apply()
    }

    companion object {
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"

        private const val PREFS_NAME =
            "armamus_user_data"

        private const val KEY_FAVORITES =
            "favorites"

        private const val KEY_PLAYLISTS =
            "playlists"

        private const val KEY_THEME =
            "theme"

        private const val KEY_SPEED =
            "speed"
    }
}
