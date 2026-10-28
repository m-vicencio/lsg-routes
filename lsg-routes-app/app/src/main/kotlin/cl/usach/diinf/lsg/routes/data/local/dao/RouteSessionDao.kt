package cl.usach.diinf.lsg.routes.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import cl.usach.diinf.lsg.routes.data.local.entity.RouteSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteSessionDao {
    @Insert
    suspend fun insert(session: RouteSessionEntity)

    @Query("SELECT * FROM local_route_sessions WHERE id = :id")
    suspend fun getById(id: String): RouteSessionEntity?

    @Query("SELECT * FROM local_route_sessions WHERE id = :id")
    fun observeById(id: String): Flow<RouteSessionEntity?>

    @Query("SELECT * FROM local_route_sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<RouteSessionEntity>>

    @Query("UPDATE local_route_sessions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE local_route_sessions SET endedAt = :endedAt, status = :status, pendingAction = :pendingAction WHERE id = :id")
    suspend fun updateEndedAt(id: String, endedAt: Long, status: String, pendingAction: String)

    @Query("SELECT * FROM local_route_sessions WHERE status = 'COMPLETED_PENDING_UPLOAD'")
    suspend fun getPendingUpload(): List<RouteSessionEntity>

    @Query("SELECT * FROM local_route_sessions WHERE status != 'SYNCED' ORDER BY startedAt DESC")
    fun observePending(): Flow<List<RouteSessionEntity>>
}
