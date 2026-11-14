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
import com.lm.player.core.database.entity.ServerTrackAccelEntity
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
        ResolvedStreamCacheEntity::class,
        ServerTrackAccelEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class ZdsDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun downloadDao(): DownloadDao
    abstract fun serverDao(): ServerDao
    abstract fun serverSourceDao(): ServerSourceDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun serverTrackAccelDao(): com.lm.player.core.database.dao.ServerTrackAccelDao

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

        /** 第 8 版（2026-11-14，与 TV 第 7 版同一张表）：新增服务器歌曲加速表，只建表，不动任何已有数据 */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `server_track_accel` (" +
                        "`songId` TEXT NOT NULL, `serverId` TEXT NOT NULL, `fileKey` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, " +
                        "`matchStatus` INTEGER NOT NULL, `platform` TEXT NOT NULL, `platformSongId` TEXT NOT NULL, " +
                        "`matchJson` TEXT NOT NULL, `matchScore` INTEGER NOT NULL, `platformCoverUrl` TEXT NOT NULL, " +
                        "`attempts` INTEGER NOT NULL, `lastTriedAt` INTEGER NOT NULL, `matchedAt` INTEGER NOT NULL, " +
                        "`lyricText` TEXT NOT NULL, `lyricSource` TEXT NOT NULL, `lyricAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`songId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_track_accel_serverId` ON `server_track_accel` (`serverId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_track_accel_matchStatus` ON `server_track_accel` (`matchStatus`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_server_track_accel_lyricAt` ON `server_track_accel` (`lyricAt`)")
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
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
