package kz.armamus.app.library

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONException

/**
 * Постоянное хранилище выбранных пользователем источников:
 * отдельные файлы и деревья папок (SAF), а также список треков,
 * убранных из медиатеки ARMAMUS (сами файлы пользователя не затрагиваются).
 * После перезапуска приложения MusicRepository заново читает эти источники,
 * если URI-разрешение ещё действует.
 */
class MusicFolderStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun files(): List<Uri> = read(KEY_FILES).map { Uri.parse(it) }

    @Synchronized
    fun folders(): List<Uri> = read(KEY_FOLDERS).map { Uri.parse(it) }

    @Synchronized
    fun excludedIds(): Set<String> = read(KEY_EXCLUDED).toSet()

    @Synchronized
    fun addFiles(uris: Collection<Uri>) =
        add(KEY_FILES, uris.map { it.toString() })

    @Synchronized
    fun addFolder(uri: Uri) =
        add(KEY_FOLDERS, listOf(uri.toString()))

    @Synchronized
    fun removeFile(uri: Uri) =
        remove(KEY_FILES, uri.toString())

    @Synchronized
    fun removeFolder(uri: Uri) =
        remove(KEY_FOLDERS, uri.toString())

    /** Скрывает трек из медиатеки (нужно для треков, найденных в папке). */
    @Synchronized
    fun exclude(trackId: String) =
        add(KEY_EXCLUDED, listOf(trackId))

    /** Возвращает ранее скрытые треки, когда пользователь добавил их снова. */
    @Synchronized
    fun unexclude(trackIds: Collection<String>) {
        val remaining = read(KEY_EXCLUDED).filter { it !in trackIds }
        write(KEY_EXCLUDED, remaining)
    }

    /** Полная очистка всех сохранённых источников. */
    @Synchronized
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun add(key: String, items: Collection<String>) {
        val values = read(key).toMutableList()
        for (item in items) {
            if (item !in values) values.add(item)
        }
        write(key, values)
    }

    private fun remove(key: String, item: String) {
        write(key, read(key).filter { it != item })
    }

    private fun read(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()

        return try {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.getString(index) }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun write(key: String, values: List<String>) {
        prefs.edit()
            .putString(key, JSONArray(values).toString())
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "armamus_library_sources"
        const val KEY_FILES = "files"
        const val KEY_FOLDERS = "folders"
        const val KEY_EXCLUDED = "excluded"
    }
}
