package com.sese.keepix.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BinItemEntity::class, KeptItemEntity::class],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun binItemDao(): BinItemDao
    abstract fun keptItemDao(): KeptItemDao

    companion object {
        /**
         * Adds [BinItemEntity.pendingDeletion]. Existing rows default to 0 (not
         * pending), so an upgrade never marks a user's bin for removal.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE bin_items ADD COLUMN pendingDeletion INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Adds [KeptItemEntity.isFavorite] and [KeptItemEntity.pendingFavoriteSync].
         * Existing rows default to 0, so an upgrade never marks anything favorite
         * and never queues a sync.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE kept_items ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE kept_items ADD COLUMN pendingFavoriteSync INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "keepix_database"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
