package com.vesper.flipper.data.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Device Tracking Database
 *
 * Tracks all discovered devices across different protocols:
 * - BLE devices (phones, wearables, beacons)
 * - Sub-GHz devices (remotes, sensors, key fobs)
 * - NFC/RFID tags
 * - WiFi access points (via ESP32 Marauder)
 *
 * Schema version 1.
 *
 * No upgrade migration exists yet because the schema has never been bumped.
 * `fallbackToDestructiveMigrationOnDowngrade` keeps old installs from
 * crashing after a downgrade without silently wiping data on upgrade.
 */
@Database(
    entities = [
        TrackedDevice::class,
        DeviceSighting::class,
        DeviceNote::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class DeviceDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun sightingDao(): SightingDao
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile
        private var INSTANCE: DeviceDatabase? = null

        fun getDatabase(context: Context): DeviceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DeviceDatabase::class.java,
                    "vesper_devices.db"
                )
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ==================== Entities ====================

@Entity(tableName = "tracked_devices")
data class TrackedDevice(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    // Device identification
    val identifier: String,        // MAC address, UID, or unique identifier
    val name: String?,             // Device name (if advertised)
    val type: DeviceType,          // BLE, SubGHz, NFC, RFID, WiFi
    val subType: String? = null,   // More specific type (e.g., "Apple Watch", "Tesla Key")

    // Classification
    val manufacturer: String? = null,
    val model: String? = null,
    val category: DeviceCategory = DeviceCategory.UNKNOWN,
    val threat: ThreatLevel = ThreatLevel.NONE,

    // User metadata
    val alias: String? = null,     // User-assigned friendly name
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val tags: List<String> = emptyList(),

    // Statistics
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val sightingCount: Int = 1,

    // Protocol-specific data (JSON)
    val metadata: String? = null
)

@Entity(
    tableName = "device_sightings",
    foreignKeys = [
        ForeignKey(
            entity = TrackedDevice::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["deviceId"])]
)
data class DeviceSighting(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val timestamp: Long = System.currentTimeMillis(),

    // Signal info
    val rssi: Int? = null,          // Signal strength
    val frequency: Long? = null,     // For RF devices
    val channel: Int? = null,        // WiFi/BLE channel

    // Location (if available)
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,

    // Raw data snapshot
    val rawData: String? = null
)

@Entity(
    tableName = "device_notes",
    foreignKeys = [
        ForeignKey(
            entity = TrackedDevice::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["deviceId"])]
)
data class DeviceNote(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val content: String,
    val isAiGenerated: Boolean = false
)

// ==================== Enums ====================

enum class DeviceType(val displayName: String, val icon: String) {
    BLE("Bluetooth LE", "🔵"),
    SUBGHZ("Sub-GHz RF", "📡"),
    NFC("NFC", "💳"),
    RFID("RFID 125kHz", "🏷️"),
    WIFI("WiFi", "📶"),
    INFRARED("Infrared", "🔴"),
    IBUTTON("iButton", "🔘")
}

enum class DeviceCategory(val displayName: String, val icon: String) {
    PHONE("Phone", "📱"),
    WEARABLE("Wearable", "⌚"),
    COMPUTER("Computer", "💻"),
    VEHICLE("Vehicle", "🚗"),
    ACCESS_CONTROL("Access Control", "🔐"),
    SMART_HOME("Smart Home", "🏠"),
    INDUSTRIAL("Industrial", "🏭"),
    MEDICAL("Medical", "🏥"),
    TRACKER("Tracker", "📍"),
    SENSOR("Sensor", "🌡️"),
    REMOTE("Remote", "🎮"),
    KEY_FOB("Key Fob", "🔑"),
    CARD("Card", "💳"),
    BEACON("Beacon", "📡"),
    UNKNOWN("Unknown", "❓")
}

enum class ThreatLevel(val displayName: String, val color: Long) {
    NONE("None", 0xFF4CAF50),
    LOW("Low", 0xFF8BC34A),
    MEDIUM("Medium", 0xFFFF9800),
    HIGH("High", 0xFFF44336),
    CRITICAL("Critical", 0xFF9C27B0)
}

// ==================== DAOs ====================

@Dao
interface DeviceDao {
    @Query("SELECT * FROM tracked_devices ORDER BY lastSeen DESC")
    fun getAllDevices(): Flow<List<TrackedDevice>>

    @Query("SELECT * FROM tracked_devices WHERE type = :type ORDER BY lastSeen DESC")
    fun getDevicesByType(type: DeviceType): Flow<List<TrackedDevice>>

    @Query("SELECT * FROM tracked_devices WHERE category = :category ORDER BY lastSeen DESC")
    fun getDevicesByCategory(category: DeviceCategory): Flow<List<TrackedDevice>>

    @Query("SELECT * FROM tracked_devices WHERE isFavorite = 1 ORDER BY lastSeen DESC")
    fun getFavoriteDevices(): Flow<List<TrackedDevice>>

    @Query("SELECT * FROM tracked_devices WHERE isHidden = 0 ORDER BY lastSeen DESC")
    fun getVisibleDevices(): Flow<List<TrackedDevice>>

    @Query("SELECT * FROM tracked_devices WHERE identifier = :identifier LIMIT 1")
    suspend fun getDeviceByIdentifier(identifier: String): TrackedDevice?

    @Query("SELECT * FROM tracked_devices WHERE id = :id")
    suspend fun getDeviceById(id: String): TrackedDevice?

    @Query("SELECT * FROM tracked_devices WHERE name LIKE '%' || :query || '%' OR alias LIKE '%' || :query || '%' OR identifier LIKE '%' || :query || '%'")
    fun searchDevices(query: String): Flow<List<TrackedDevice>>

    @Query("SELECT COUNT(*) FROM tracked_devices")
    fun getDeviceCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracked_devices WHERE lastSeen > :since")
    fun getRecentDeviceCount(since: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: TrackedDevice)

    @Update
    suspend fun updateDevice(device: TrackedDevice)

    @Delete
    suspend fun deleteDevice(device: TrackedDevice)

    @Query("DELETE FROM tracked_devices WHERE id = :id")
    suspend fun deleteDeviceById(id: String)

    @Query("UPDATE tracked_devices SET lastSeen = :timestamp, sightingCount = sightingCount + 1 WHERE id = :id")
    suspend fun updateSighting(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE tracked_devices SET alias = :alias WHERE id = :id")
    suspend fun updateAlias(id: String, alias: String?)

    @Query("UPDATE tracked_devices SET isFavorite = :favorite WHERE id = :id")
    suspend fun updateFavorite(id: String, favorite: Boolean)

    @Query("UPDATE tracked_devices SET isHidden = :hidden WHERE id = :id")
    suspend fun updateHidden(id: String, hidden: Boolean)

    @Query("UPDATE tracked_devices SET threat = :threat WHERE id = :id")
    suspend fun updateThreatLevel(id: String, threat: ThreatLevel)
}

@Dao
interface SightingDao {
    @Query("SELECT * FROM device_sightings WHERE deviceId = :deviceId ORDER BY timestamp DESC")
    fun getSightingsForDevice(deviceId: String): Flow<List<DeviceSighting>>

    @Query("SELECT * FROM device_sightings WHERE deviceId = :deviceId ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentSightings(deviceId: String, limit: Int): Flow<List<DeviceSighting>>

    @Query("SELECT * FROM device_sightings WHERE timestamp > :since ORDER BY timestamp DESC")
    fun getSightingsSince(since: Long): Flow<List<DeviceSighting>>

    @Insert
    suspend fun insertSighting(sighting: DeviceSighting)

    @Query("DELETE FROM device_sightings WHERE deviceId = :deviceId")
    suspend fun deleteSightingsForDevice(deviceId: String)

    @Query("DELETE FROM device_sightings WHERE timestamp < :before")
    suspend fun deleteOldSightings(before: Long)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM device_notes WHERE deviceId = :deviceId ORDER BY timestamp DESC")
    fun getNotesForDevice(deviceId: String): Flow<List<DeviceNote>>

    @Insert
    suspend fun insertNote(note: DeviceNote)

    @Delete
    suspend fun deleteNote(note: DeviceNote)
}

// ==================== Type Converters ====================

class Converters {
    @TypeConverter
    fun fromDeviceType(value: DeviceType): String = value.name

    @TypeConverter
    fun toDeviceType(value: String): DeviceType = DeviceType.valueOf(value)

    @TypeConverter
    fun fromDeviceCategory(value: DeviceCategory): String = value.name

    @TypeConverter
    fun toDeviceCategory(value: String): DeviceCategory = DeviceCategory.valueOf(value)

    @TypeConverter
    fun fromThreatLevel(value: ThreatLevel): String = value.name

    @TypeConverter
    fun toThreatLevel(value: String): ThreatLevel = ThreatLevel.valueOf(value)

    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString(",")

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(",")
}
