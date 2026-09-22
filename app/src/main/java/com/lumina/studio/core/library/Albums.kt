package com.lumina.studio.core.library

/**
 * M13 albums (pure JVM, no android.*).
 *
 * STORAGE DECISION (see §§43-44 milestone): albums live as a single JSON
 * document in DataStore (see AlbumRepository), NOT as Room tables.
 * Why: [Album]/[AlbumEntry] need no SQL joins — the Projects list is
 * already in memory and membership is a set lookup. A Room option would
 * need `albums` + `album_entries` tables plus Migration(3→4) and a
 * version bump, which collides with the pinned v3 suites
 * (EntitiesTest asserts `version = 3` + 4 entities; MigrationsTest pins
 * `ALL.size == 2`). New tables are also a migration path that cannot be
 * validated on this host (no local gradle runs; CI validates). The JSON
 * document keeps Room at v3 with zero migration risk, and album
 * membership never duplicates photo bytes — entries reference project
 * ids only. Removing an entry or deleting an album never deletes photos.
 */
data class Album(
    val id: String,
    val name: String,
    val createdAt: Long = 0L
)

/** Cross-ref: membership of a project in an album. Never owns photo bytes. */
data class AlbumEntry(
    val albumId: String,
    val projectId: String
)

data class AlbumStore(
    val albums: List<Album> = emptyList(),
    val entries: List<AlbumEntry> = emptyList()
) {
    companion object {
        val EMPTY = AlbumStore()
    }
}

object AlbumLimits {
    const val MAX_ALBUMS = 100
    const val MAX_ENTRIES = 5000
    const val MAX_NAME_LEN = 60
}

object AlbumOps {
    /** Trimmed + length-capped display name, or "" when blank. */
    fun cleanName(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim().replace(Regex("\\s+"), " ").take(AlbumLimits.MAX_NAME_LEN).trim()
    }

    fun memberIds(entries: List<AlbumEntry>, albumId: String): Set<String> =
        entries.filter { it.albumId == albumId }.map { it.projectId }.toSet()

    fun albumIdsOf(entries: List<AlbumEntry>, projectId: String): Set<String> =
        entries.filter { it.projectId == projectId }.map { it.albumId }.toSet()

    fun counts(entries: List<AlbumEntry>): Map<String, Int> =
        entries.groupingBy { it.albumId }.eachCount()

    /** Idempotent add; no-op when the album is unknown or the entry/caps exist. */
    fun addEntries(
        store: AlbumStore,
        albumId: String,
        projectIds: Collection<String>
    ): AlbumStore {
        if (store.albums.none { it.id == albumId }) return store
        val known = store.entries.toSet()
        val fresh = projectIds
            .filter { it.isNotBlank() }
            .distinct()
            .map { AlbumEntry(albumId, it) }
            .filter { it !in known }
        if (fresh.isEmpty()) return store
        val room = AlbumLimits.MAX_ENTRIES - store.entries.size
        if (room <= 0) return store
        return store.copy(entries = store.entries + fresh.take(room))
    }

    /** Removing entries never touches projects — membership only. */
    fun removeEntries(
        store: AlbumStore,
        albumId: String,
        projectIds: Collection<String>
    ): AlbumStore {
        if (projectIds.isEmpty()) return store
        val ids = projectIds.toSet()
        val kept = store.entries.filterNot { it.albumId == albumId && it.projectId in ids }
        if (kept.size == store.entries.size) return store
        return store.copy(entries = kept)
    }

    /** Deleting an album keeps every photo; only the album + its entries go. */
    fun deleteAlbum(store: AlbumStore, albumId: String): AlbumStore {
        if (store.albums.none { it.id == albumId }) return store
        return AlbumStore(
            albums = store.albums.filterNot { it.id == albumId },
            entries = store.entries.filterNot { it.albumId == albumId }
        )
    }

    /** Drop entries whose project no longer exists (e.g. after safe-delete). */
    fun pruneMissingProjects(store: AlbumStore, existingIds: Set<String>): AlbumStore {
        val kept = store.entries.filter { it.projectId in existingIds }
        if (kept.size == store.entries.size) return store
        return store.copy(entries = kept)
    }

    fun removeProjectFromAll(store: AlbumStore, projectId: String): AlbumStore {
        if (store.entries.none { it.projectId == projectId }) return store
        return store.copy(entries = store.entries.filterNot { it.projectId == projectId })
    }
}

object AlbumJson {
    private const val VERSION = 1

    fun encode(store: AlbumStore): String {
        val out = StringBuilder()
        out.append("{\"v\":").append(VERSION).append(",\"albums\":[")
        store.albums.forEachIndexed { index, album ->
            if (index > 0) out.append(',')
            out.append("{\"id\":").append(MiniJson.quoted(album.id))
                .append(",\"name\":").append(MiniJson.quoted(album.name))
                .append(",\"createdAt\":").append(album.createdAt).append('}')
        }
        out.append("],\"entries\":[")
        store.entries.forEachIndexed { index, entry ->
            if (index > 0) out.append(',')
            out.append("{\"a\":").append(MiniJson.quoted(entry.albumId))
                .append(",\"p\":").append(MiniJson.quoted(entry.projectId)).append('}')
        }
        return out.append("]}").toString()
    }

    /** Never throws: corrupt/oversized input decodes to [AlbumStore.EMPTY]. */
    fun decode(json: String): AlbumStore {
        return try {
            decodeOrThrow(json)
        } catch (_: Exception) {
            AlbumStore.EMPTY
        }
    }

    private fun decodeOrThrow(json: String): AlbumStore {
        val root = MiniJson.parse(json) as? JsonVal.Obj ?: return AlbumStore.EMPTY
        val albums = ArrayList<Album>()
        val seenAlbumIds = HashSet<String>()
        for (item in root.array("albums").orEmpty()) {
            val obj = item as? JsonVal.Obj ?: continue
            val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: continue
            if (!seenAlbumIds.add(id)) continue
            // Faithful decode: names were already cleaned at creation time.
            // Cleaning here would break round-trip fidelity (and drop stored
            // names); only cap length for safety.
            val rawName = obj.string("name") ?: continue
            if (rawName.isBlank()) continue
            val name = rawName.take(AlbumLimits.MAX_NAME_LEN)
            if (name.isEmpty()) continue
            albums.add(Album(id, name, obj.long("createdAt") ?: 0L))
            if (albums.size >= AlbumLimits.MAX_ALBUMS) break
        }
        val entries = ArrayList<AlbumEntry>()
        val seenEntries = HashSet<AlbumEntry>()
        for (item in root.array("entries").orEmpty()) {
            val obj = item as? JsonVal.Obj ?: continue
            val albumId = obj.string("a")?.takeIf { it.isNotBlank() } ?: continue
            val projectId = obj.string("p")?.takeIf { it.isNotBlank() } ?: continue
            if (albumId !in seenAlbumIds) continue
            val entry = AlbumEntry(albumId, projectId)
            if (!seenEntries.add(entry)) continue
            entries.add(entry)
            if (entries.size >= AlbumLimits.MAX_ENTRIES) break
        }
        return AlbumStore(albums, entries)
    }
}
