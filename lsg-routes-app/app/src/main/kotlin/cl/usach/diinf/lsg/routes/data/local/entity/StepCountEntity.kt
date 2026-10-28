package cl.usach.diinf.lsg.routes.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "step_counts")
data class StepCountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val stepsDelta: Int,
    val capturedAt: Long,
    val uploaded: Boolean = false,
)
