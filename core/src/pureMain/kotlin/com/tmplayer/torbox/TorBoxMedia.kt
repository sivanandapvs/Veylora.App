package com.tmplayer.torbox

import com.tmplayer.data.MediaIdentity
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaName
import com.tmplayer.data.MediaText
import com.tmplayer.data.ParsedName
import com.tmplayer.online.MetaQuery

/** Cloud file information without a fabricated Telegram message or file reference. */
data class TorBoxMedia(val file: TorBoxFile) {
    val identity: MediaIdentity.TorBox = MediaIdentity.TorBox(file.source, file.torrentId, file.fileId)
    val fileName: String = file.name.substringAfterLast('/').substringAfterLast('\\')
    private val transfer = MediaName.parse(MetaQuery.withoutIds(file.transferName))
    private val individual = MediaName.parse(MetaQuery.withoutIds(fileName))
    private val code = EPISODE_CODE.find(fileName)
    val parsedName: ParsedName = individual.copy(
        title = individual.title.takeUnless {
            MetaQuery.of(fileName)?.title.isNullOrBlank() || (individual.year == null && GENERIC.matches(it)) ||
                code?.range?.first == 0
        } ?: transfer.title,
        year = individual.year ?: transfer.year,
        season = individual.season ?: code?.groupValues?.get(1)?.toIntOrNull() ?: transfer.season,
        episode = individual.episode ?: code?.groupValues?.get(2)?.toIntOrNull(),
        airDate = individual.airDate ?: transfer.airDate,
    )

    private companion object {
        val EPISODE_CODE = Regex("""(?i)\bS(\d{1,2})[ ._-]*E(\d{1,4})\b""")
        val GENERIC = Regex("""(?i)^(?:video|episode|movie|file|s\d+e|e|ep)?\s*\d*$""")
    }
    val qualityTags: List<String> = MediaText.qualityTags(fileName)
    val title: String get() = parsedName.title.ifBlank { fileName }
    val sourceLabel: String get() = "TorBox"

    fun toMediaItem(): MediaItem {
        return MediaItem(
            chatId = 0L,
            messageId = 0,
            fileId = 0,
            title = title,
            sizeBytes = file.size,
            durationSec = 0,
            mimeType = "video/*",
            thumbnailFileId = 0,
            miniThumbnail = null,
            date = 0,
            fileName = fileName,
            caption = "",
            identity = identity,
            torBoxName = parsedName,
            torBoxTransferName = file.transferName,
            torBoxMarkers = file.metadataMarkers,
        )
    }
}

/** Media from a TorBox file. */
fun TorBoxFile.media(): TorBoxMedia = TorBoxMedia(this)

/** Convert a TorBox file to a MediaItem with proper TorBox identity. Uses real identity instead of dummy Telegram fields. */
fun TorBoxFile.toMediaItem(): MediaItem = media().toMediaItem()
