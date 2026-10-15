package com.lm.player.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lm.player.core.database.dao.DownloadDao
import com.lm.player.core.database.dao.PlaylistDao
import com.lm.player.core.database.dao.ServerDao
import com.lm.player.core.database.dao.SongDao
import com.lm.player.core.database.entity.DownloadEntity
import com.lm.player.core.database.entity.PlaylistEntity
import com.lm.player.core.database.entity.PlaylistSongEntity
import com.lm.player.core.database.entity.ServerEntity
import com.lm.player.core.database.entity.SongEntity

@Database(
    entities = [
        SongEntity::class,
        DownloadEntity::class,
        ServerEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class ZdsDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun downloadDao(): DownloadDao
    abstract fun serverDao(): ServerDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: ZdsDatabase? = null

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN sourcePlatform TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN platformSongId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN chosenQuality TEXT NOT NULL DEFAULT '320k'")
                db.execSQL("ALTER TABLE downloads ADD COLUMN rawMetaJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE downloads ADD COLUMN serverPushStatus INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN lastPushTime INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN pushErrorMessage TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_serverPushStatus` ON `downloads` (`serverPushStatus`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN rawMetaJson TEXT DEFAULT NULL")
            }
        }

        fun getInstance(context: Context): ZdsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ZdsDatabase::class.java,
                    "zds_player.db"
                )
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
