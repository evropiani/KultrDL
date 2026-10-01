package app.kultr.dl.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class, DownloadEntity::class, SearchEntity::class,
        PlayEntity::class, OwnedSongEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class KultrDLDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun downloads(): DownloadDao
    abstract fun searches(): SearchDao
    abstract fun plays(): PlayDao
    abstract fun owned(): OwnedSongDao

    companion object {
        /** 1.1: downloads can go to a server, or send a file that is already on the phone. */
        private val ADD_DESTINATIONS = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN destination TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN upload INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 1.2: the listening log, and the songs on the phone and on Navidrome, for recommendations. */
        private val ADD_LISTENING = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plays` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, `title` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `listenedMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER, `completed` INTEGER NOT NULL, `skipped` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plays_trackId` ON `plays` (`trackId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plays_startedAt` ON `plays` (`startedAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `owned_songs` (`id` TEXT NOT NULL, `owner` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, `album` TEXT, `albumArtist` TEXT, `genre` TEXT, `year` INTEGER, `trackNumber` INTEGER, " +
                        "`durationMs` INTEGER, `playCount` INTEGER NOT NULL, `lastPlayedAt` INTEGER, `starred` INTEGER NOT NULL, " +
                        "`rating` INTEGER NOT NULL, `artworkUrl` TEXT, `streamUrl` TEXT, `artistId` TEXT, `addedAt` INTEGER, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_owned_songs_owner` ON `owned_songs` (`owner`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_owned_songs_artist` ON `owned_songs` (`artist`)")
            }
        }

        fun open(context: Context): KultrDLDatabase =
            Room.databaseBuilder(context, KultrDLDatabase::class.java, "kultrdl.db")
                .addMigrations(ADD_DESTINATIONS, ADD_LISTENING)
                .build()
    }
}
