package app.kultr.dl.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrackEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class, DownloadEntity::class, SearchEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class KultrDLDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun downloads(): DownloadDao
    abstract fun searches(): SearchDao

    companion object {
        /** 1.1: downloads can go to a server, or send a file that is already on the phone. */
        private val ADD_DESTINATIONS = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN destination TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN upload INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun open(context: Context): KultrDLDatabase =
            Room.databaseBuilder(context, KultrDLDatabase::class.java, "kultrdl.db")
                .addMigrations(ADD_DESTINATIONS)
                .build()
    }
}
