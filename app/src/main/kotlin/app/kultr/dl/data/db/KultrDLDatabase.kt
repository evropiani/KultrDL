package app.kultr.dl.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class, DownloadEntity::class, SearchEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class KultrDLDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun downloads(): DownloadDao
    abstract fun searches(): SearchDao

    companion object {
        fun open(context: Context): KultrDLDatabase =
            Room.databaseBuilder(context, KultrDLDatabase::class.java, "kultrdl.db").build()
    }
}
