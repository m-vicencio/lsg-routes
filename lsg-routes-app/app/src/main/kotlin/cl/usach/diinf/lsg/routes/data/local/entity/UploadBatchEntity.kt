package cl.usach.diinf.lsg.routes.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "upload_batches")
data class UploadBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val clientRef: String,
    val status: String,  // PENDING | SENT | FAILED
)
