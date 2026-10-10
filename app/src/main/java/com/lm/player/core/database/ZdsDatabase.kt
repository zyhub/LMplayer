package com.lm.player.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 4,
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

        fun getInstance(context: Context): ZdsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ZdsDatabase::class.java,
                    "zds_player.db"
                )
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    // 只允许「降级」时清库（用户装了旧版本，结构不兼容，无法安全降级）。
                    // 升级绝不清库：此前用 fallbackToDestructiveMigration() 意味着只要下次发版把
                    // version 从 4 改成 5，用户的 songs / downloads / playlists / servers 会被
                    // 整体 DROP 重建 —— 本地曲库索引、已下载记录、自建歌单、服务器地址与令牌
                    // 全部丢失，而磁盘上音频文件会变成永不识别的孤儿文件。
                    // 新增字段时请务必补 Migration(N, N+1) 并在此处 addMigrations(...)。
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
