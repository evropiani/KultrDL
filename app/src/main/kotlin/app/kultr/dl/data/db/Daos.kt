package app.kultr.dl.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observe(id: String): Flow<TrackEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(track: TrackEntity): Long

    @Update
    suspend fun update(track: TrackEntity)

    @Upsert
    suspend fun upsert(track: TrackEntity)

    @Query("SELECT * FROM tracks WHERE favorite = 1 ORDER BY favoritedAt DESC")
    fun favorites(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE saved = 1 ORDER BY savedAt DESC")
    fun saved(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE localUri IS NOT NULL ORDER BY downloadedAt DESC")
    fun downloaded(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun history(limit: Int): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE playCount > 0 ORDER BY playCount DESC, lastPlayedAt DESC LIMIT :limit")
    fun mostPlayed(limit: Int): Flow<List<TrackEntity>>

    @Query("SELECT id, favorite, saved, localUri FROM tracks WHERE favorite = 1 OR saved = 1 OR localUri IS NOT NULL")
    fun flags(): Flow<List<TrackFlags>>

    @Query("UPDATE tracks SET favorite = :on, favoritedAt = :at WHERE id = :id")
    suspend fun setFavorite(id: String, on: Boolean, at: Long?)

    @Query("UPDATE tracks SET saved = :on, savedAt = :at WHERE id = :id")
    suspend fun setSaved(id: String, on: Boolean, at: Long?)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :at WHERE id = :id")
    suspend fun markPlayed(id: String, at: Long)

    @Query("UPDATE tracks SET lastPlayedAt = NULL, playCount = 0")
    suspend fun clearHistory()

    @Query("UPDATE tracks SET matchedUrl = :url WHERE id = :id")
    suspend fun setMatchedUrl(id: String, url: String?)

    @Query("UPDATE tracks SET localUri = :uri, localFormat = :format, localSize = :size, downloadedAt = :at WHERE id = :id")
    suspend fun setLocal(id: String, uri: String?, format: String?, size: Long?, at: Long?)

    @Query("SELECT COUNT(*) FROM tracks WHERE localUri IS NOT NULL")
    fun downloadedCount(): Flow<Int>

    @Query("SELECT * FROM tracks WHERE favorite = 1 OR saved = 1 OR localUri IS NOT NULL OR id IN (SELECT trackId FROM playlist_tracks)")
    suspend fun keepers(): List<TrackEntity>
}

@Dao
interface PlaylistDao {
    @Query(
        """
        SELECT p.id, p.name, p.artworkUrl, p.updatedAt, COUNT(pt.trackId) AS count
        FROM playlists p LEFT JOIN playlist_tracks pt ON pt.playlistId = p.id
        GROUP BY p.id ORDER BY p.updatedAt DESC
        """,
    )
    fun summaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists")
    suspend fun all(): List<PlaylistEntity>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT t.* FROM playlist_tracks pt JOIN tracks t ON t.id = pt.trackId WHERE pt.playlistId = :id ORDER BY pt.position")
    fun tracks(id: Long): Flow<List<TrackEntity>>

    @Query("SELECT trackId FROM playlist_tracks WHERE playlistId = :id ORDER BY position")
    suspend fun trackIds(id: Long): List<String>

    @Query("SELECT * FROM playlist_tracks")
    suspend fun allEntries(): List<PlaylistTrackEntity>

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :id")
    suspend fun clear(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<PlaylistTrackEntity>)
}

@Dao
interface DownloadDao {
    @Query(
        """
        SELECT d.trackId, d.state, d.format, d.quality, d.progress, d.message, d.updatedAt,
               t.title, t.artist, t.artworkUrl, t.localUri, d.destination, d.upload
        FROM downloads d JOIN tracks t ON t.id = d.trackId
        ORDER BY CASE d.state WHEN 'RUNNING' THEN 0 WHEN 'QUEUED' THEN 1 WHEN 'FAILED' THEN 2 ELSE 3 END, d.updatedAt DESC
        """,
    )
    fun all(): Flow<List<DownloadWithTrack>>

    @Query("SELECT * FROM downloads WHERE state = 'QUEUED' ORDER BY createdAt LIMIT 1")
    suspend fun nextQueued(): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE trackId = :id")
    suspend fun get(id: String): DownloadEntity?

    @Query("SELECT COUNT(*) FROM downloads WHERE state IN ('QUEUED', 'RUNNING')")
    fun activeCount(): Flow<Int>

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query("UPDATE downloads SET state = :state, progress = :progress, message = :message, updatedAt = :at WHERE trackId = :id")
    suspend fun setState(id: String, state: String, progress: Float, message: String?, at: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET progress = :progress, message = :message, updatedAt = :at WHERE trackId = :id AND state = 'RUNNING'")
    suspend fun setProgress(id: String, progress: Float, message: String?, at: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET state = 'QUEUED', progress = 0, message = NULL WHERE state = 'RUNNING'")
    suspend fun requeueInterrupted()

    @Query("DELETE FROM downloads WHERE trackId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM downloads WHERE state IN ('DONE', 'CANCELLED')")
    suspend fun clearFinished()

    @Query("UPDATE downloads SET state = 'QUEUED', progress = 0, message = NULL, updatedAt = :at WHERE state = 'FAILED'")
    suspend fun retryFailed(at: Long = System.currentTimeMillis())
}

@Dao
interface SearchDao {
    @Query("SELECT `query` FROM searches ORDER BY at DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<String>>

    @Upsert
    suspend fun upsert(search: SearchEntity)

    @Query("DELETE FROM searches WHERE `query` = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM searches")
    suspend fun clear()
}
