package cl.usach.diinf.lsg.routes.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accel_samples")
data class AccelSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val magnitudeMean: Float,
    val magnitudeVariance: Float,
    val bucketStartedAt: Long,
    val bucketSeconds: Int,
    val uploaded: Boolean = false,
)
