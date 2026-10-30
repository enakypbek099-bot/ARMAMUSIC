package kz.armamus.app.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Читает реальные метаданные аудиофайла через MediaMetadataRetriever.
 * Ничего не выдумывает: неизвестные поля остаются null.
 * Если title отсутствует, используется имя файла без расширения.
 * Частота и разрядность FLAC/WAV читаются из заголовка файла.
 */
class AudioMetadataReader(
    private val context: Context,
    private val artworkStore: ArtworkStore
) {

    /**
     * Возвращает Track или null, если файл недоступен для чтения.
     * displayName передаётся, когда имя уже известно (например, из дерева папки).
     */
    fun read(uri: Uri, displayName: String?): Track? {
        val fileName = displayName ?: queryDisplayName(uri) ?: uri.lastPathSegment ?: DEFAULT_FILE_NAME
        val id = Track.idFor(uri)
        val fallbackTitle = fileName.substringBeforeLast('.')
        val format = probeFormat(uri, fileName)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val title = retriever.text(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fallbackTitle
            val artist = retriever.text(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val album = retriever.text(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val durationMs = retriever.text(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
            val mimeType = retriever.text(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                ?: context.contentResolver.getType(uri)

            // Обложка сохраняется один раз; при следующих запусках файл уже есть
            if (!artworkStore.hasArtwork(id)) {
                retriever.embeddedPicture?.let { bytes -> artworkStore.save(id, bytes) }
            }

            return Track(
                id = id,
                uri = uri,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                mimeType = mimeType,
                fileName = fileName,
                artworkUri = artworkStore.notificationUri(id),
                sampleRateHz = format.first,
                bitsPerSample = format.second
            )
        } catch (e: Exception) {
            // Retriever не разобрал файл. Если файл при этом читается, его всё равно
            // можно воспроизвести: метаданные остаются неизвестными, title берётся из имени файла.
            if (!isReadable(uri)) return null
            return Track(
                id = id,
                uri = uri,
                title = fallbackTitle,
                artist = null,
                album = null,
                durationMs = null,
                mimeType = context.contentResolver.getType(uri),
                fileName = fileName,
                artworkUri = artworkStore.notificationUri(id),
                sampleRateHz = format.first,
                bitsPerSample = format.second
            )
        } finally {
            retriever.release()
        }
    }

    private fun MediaMetadataRetriever.text(key: Int): String? =
        extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    private fun isReadable(uri: Uri): Boolean = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    /**
     * Частота и разрядность из заголовка FLAC (STREAMINFO) и WAV (блок fmt).
     * Для остальных форматов возвращается (null, null): ничего не выдумываем.
     */
    private fun probeFormat(uri: Uri, fileName: String): Pair<Int?, Int?> {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension != "flac" && extension != "wav") return Pair(null, null)

        val head = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(HEADER_BYTES)
                var filled = 0
                while (filled < buffer.size) {
                    val count = stream.read(buffer, filled, buffer.size - filled)
                    if (count <= 0) break
                    filled += count
                }
                buffer.copyOf(filled)
            }
        } catch (e: Exception) {
            null
        } ?: return Pair(null, null)

        var rate = 0
        var bits = 0

        var offset = 0
        if (ascii(head, 0, 3) == "ID3" && head.size > 10) {
            // Пропуск ID3-тега перед FLAC
            offset = 10 + (((u8(head, 6) and 127) shl 21) or
                ((u8(head, 7) and 127) shl 14) or
                ((u8(head, 8) and 127) shl 7) or
                (u8(head, 9) and 127))
        }

        if (offset + 4 <= head.size && ascii(head, offset, 4) == "fLaC") {
            // STREAMINFO: 20 бит частота, 5 бит разрядность - 1
            val d = offset + 8
            if (d + 13 < head.size) {
                rate = (u8(head, d + 10) shl 12) or
                    (u8(head, d + 11) shl 4) or
                    (u8(head, d + 12) shr 4)

                bits = (((u8(head, d + 12) and 1) shl 4) or
                    (u8(head, d + 13) shr 4)) + 1
            }
        } else if (ascii(head, 0, 4) == "RIFF" && ascii(head, 8, 4) == "WAVE") {
            var i = 12
            while (i + 8 < head.size) {
                val chunkId = ascii(head, i, 4)
                val size = u8(head, i + 4) or
                    (u8(head, i + 5) shl 8) or
                    (u8(head, i + 6) shl 16) or
                    (u8(head, i + 7) shl 24)

                if (chunkId == "fmt ") {
                    if (i + 23 < head.size) {
                        rate = u8(head, i + 12) or
                            (u8(head, i + 13) shl 8) or
                            (u8(head, i + 14) shl 16) or
                            (u8(head, i + 15) shl 24)

                        bits = u8(head, i + 22) or
                            (u8(head, i + 23) shl 8)
                    }
                    break
                }

                if (size < 0) break
                i += 8 + size + (size and 1)
            }
        }

        return Pair(
            rate.takeIf { it > 0 },
            bits.takeIf { it > 0 }
        )
    }

    private fun u8(data: ByteArray, index: Int): Int =
        data[index].toInt() and 0xFF

    private fun ascii(data: ByteArray, index: Int, count: Int): String {
        val builder = StringBuilder()
        var k = index
        while (k < index + count && k < data.size) {
            builder.append(u8(data, k).toChar())
            k++
        }
        return builder.toString()
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "audio"
        const val HEADER_BYTES = 524288
    }
}
