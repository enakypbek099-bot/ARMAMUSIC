package kz.armamus.app.library

import android.net.Uri
import java.security.MessageDigest

/**
 * Модель трека библиотеки.
 *
 * Все необязательные поля хранят именно неизвестное состояние (null),
 * если файл или Android не сообщили значение.
 */
data class Track(
    /** Стабильный идентификатор: одинаков для одного и того же Uri после перезапуска. */
    val id: String,
    val uri: Uri,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val mimeType: String?,
    val fileName: String,
    /** Файл обложки для MediaMetadata (до 1024 px по большей стороне); null, если обложки нет. */
    val artworkUri: Uri?,
    /** Частота дискретизации из заголовка файла (FLAC/WAV); null, если не определена. */
    val sampleRateHz: Int? = null,
    /** Разрядность из заголовка файла (FLAC/WAV); null, если не определена. */
    val bitsPerSample: Int? = null
) {
    val hasEmbeddedArtwork: Boolean
        get() = artworkUri != null

    companion object {
        /** Стабильный id: первые 32 hex-символа SHA-256 от строки Uri. */
        fun idFor(uri: Uri): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(32)
        }
    }
}
