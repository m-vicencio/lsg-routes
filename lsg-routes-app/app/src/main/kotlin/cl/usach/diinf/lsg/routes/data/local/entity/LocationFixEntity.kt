package cl.usach.diinf.lsg.routes.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "location_fixes")
data class LocationFixEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val altitudeM: Double?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val isMock: Boolean,
    val capturedAt: Long,
    val clientSeq: Int,
    val uploaded: Boolean = false,
)
