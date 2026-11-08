package com.example.netwatch.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** key = lowercase MAC when known, otherwise "ip:<address>". */
@Entity(tableName = "devices")
data class Device(
    @PrimaryKey val key: String,
    val ip: String,
    val mac: String? = null,
    val hostname: String? = null,
    val name: String? = null,
    val trusted: Boolean = false,
    val online: Boolean = true,
    val blocked: Boolean = false,
    val firstSeen: Long,
    val lastSeen: Long,
) {
    val displayName: String get() = name ?: hostname ?: ip
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY online DESC, trusted ASC, lastSeen DESC")
    fun observeAll(): Flow<List<Device>>

    @Query("SELECT * FROM devices")
    suspend fun getAll(): List<Device>

    @Query("SELECT * FROM devices WHERE `key` = :key")
    suspend fun get(key: String): Device?

    @Upsert
    suspend fun upsert(device: Device)

    @Delete
    suspend fun delete(device: Device)
}

@Database(entities = [Device::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "netwatch.db")
                .build().also { instance = it }
        }
    }
}
