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
import com.lm.player.core.database.dao.ServerSourceDao
import com.lm.player.core.database.dao.SongDao
import com.lm.player.core.database.entity.DownloadEntity
import com.lm.player.core.database.entity.PlaylistEntity
import com.lm.player.core.database.entity.PlaylistSongEntity
import com.lm.player.core.database.entity.ResolvedStreamCacheEntity
import com.lm.player.core.database.entity.ServerEntity
import com.lm.player.core.database.entity.ServerSourceEntity
import com.lm.player.core.database.entity.ServerSourceMetaEntity
import com.lm.player.core.database.entity.SongEntity

@Database(
    entities = [
        SongEntity::class,
        DownloadEntity::class,
        ServerEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        ServerSourceEntity::class,
        ServerSourceMetaEntity::class,
        ResolvedStreamCacheEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class ZdsDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun downloadDao(): DownloadDao
    abstract fun serverDao(): ServerDao
    abstract fun serverSourceDao(): ServerSourceDao
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

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `server_sources` (" +
                        "`key` TEXT NOT NULL, `serverId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `description` TEXT NOT NULL, `author` TEXT NOT NULL, " +
                        "`version` TEXT NOT NULL, `homepage` TEXT NOT NULL, `isActive` INTEGER NOT NULL, " +
                        "`healthLevel` TEXT NOT NULL, `healthBadge` TEXT NOT NULL, `healthTip` TEXT NOT NULL, " +
                        "`healthUnhealthy` INTEGER NOT NULL, `healthSampleCount` INTEGER NOT NULL, " +
                        "`healthPreviewCount` INTEGER NOT NULL, `healthRatio` REAL NOT NULL, " +
                        "`healthPlatformsJson` TEXT NOT NULL, `platformsJson` TEXT NOT NULL, " +
                        "`disabledPlatformsJson` TEXT NOT NULL, `rawScriptCached` INTEGER NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_sources_serverId` ON `server_sources` (`serverId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_sources_isActive` ON `server_sources` (`isActive`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_sources_fetchedAt` ON `server_sources` (`fetchedAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `server_source_meta` (" +
                        "`serverId` TEXT NOT NULL, `serverName` TEXT NOT NULL, `fallbackMode` TEXT NOT NULL, " +
                        "`activeSourceCount` INTEGER NOT NULL, `totalSourceCount` INTEGER NOT NULL, " +
                        "`latencyMs` INTEGER NOT NULL, `lastOnlineAt` INTEGER NOT NULL, " +
                        "`lastSyncAt` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`serverId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `resolved_stream_cache` (" +
                        "`cacheKey` TEXT NOT NULL, `serverHost` TEXT NOT NULL, `songId` TEXT NOT NULL, " +
                        "`quality` TEXT NOT NULL, `url` TEXT NOT NULL, `format` TEXT NOT NULL, " +
                        "`bitRate` INTEGER NOT NULL, `sourceId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`expiresAt` INTEGER NOT NULL, PRIMARY KEY(`cacheKey`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_resolved_stream_cache_serverHost` ON `resolved_stream_cache` (`serverHost`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_resolved_stream_cache_expiresAt` ON `resolved_stream_cache` (`expiresAt`)")
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
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
