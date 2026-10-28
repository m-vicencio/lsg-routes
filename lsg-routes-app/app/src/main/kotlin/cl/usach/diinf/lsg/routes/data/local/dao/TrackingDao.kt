package cl.usach.diinf.lsg.routes.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import cl.usach.diinf.lsg.routes.data.local.entity.AccelSampleEntity
import cl.usach.diinf.lsg.routes.data.local.entity.LocationFixEntity
import cl.usach.diinf.lsg.routes.data.local.entity.StepCountEntity
import cl.usach.diinf.lsg.routes.data.local.entity.UploadBatchEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackingDao {
    @Insert
    suspend fun insertFix(fix: LocationFixEntity)

    @Insert
    suspend fun insertAccelSample(sample: AccelSampleEntity)

    @Insert
    suspend fun insertStepCount(step: StepCountEntity)

    @Insert
    suspend fun insertBatch(batch: UploadBatchEntity): Long

    @Query("SELECT * FROM location_fixes WHERE sessionId = :sessionId ORDER BY clientSeq ASC")
    fun observeFixes(sessionId: String): Flow<List<LocationFixEntity>>

    @Query("SELECT * FROM location_fixes WHERE sessionId = :sessionId AND uploaded = 0 ORDER BY clientSeq ASC")
    suspend fun getUnuploadedFixes(sessionId: String): List<LocationFixEntity>

    @Query("SELECT * FROM accel_samples WHERE sessionId = :sessionId AND uploaded = 0")
    suspend fun getUnuploadedAccel(sessionId: String): List<AccelSampleEntity>

    @Query("SELECT * FROM step_counts WHERE sessionId = :sessionId AND uploaded = 0")
    suspend fun getUnuploadedSteps(sessionId: String): List<StepCountEntity>

    @Query("UPDATE location_fixes SET uploaded = 1 WHERE sessionId = :sessionId AND clientSeq <= :upToSeq")
    suspend fun markFixesUploaded(sessionId: String, upToSeq: Int)

    @Query("UPDATE accel_samples SET uploaded = 1 WHERE sessionId = :sessionId")
    suspend fun markAccelUploaded(sessionId: String)

    @Query("UPDATE step_counts SET uploaded = 1 WHERE sessionId = :sessionId")
    suspend fun markStepsUploaded(sessionId: String)

    @Query("SELECT COUNT(*) FROM location_fixes WHERE sessionId = :sessionId")
    fun observeFixCount(sessionId: String): Flow<Int>

    /** Ultimo numero de secuencia grabado, o null si la sesion todavia no tiene fixes.
     *
     *  Al reanudar hay que seguir numerando desde aqui: markFixesUploaded marca por rango
     *  (clientSeq <= upToSeq) y el UploadWorker envia seqs.max(), asi que un numero
     *  repetido daria por subidos fixes que nunca salieron del telefono. */
    @Query("SELECT MAX(clientSeq) FROM location_fixes WHERE sessionId = :sessionId")
    suspend fun maxClientSeq(sessionId: String): Int?
}
