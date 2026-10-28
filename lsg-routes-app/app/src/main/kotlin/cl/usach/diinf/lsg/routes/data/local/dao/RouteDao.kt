package cl.usach.diinf.lsg.routes.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cl.usach.diinf.lsg.routes.data.local.entity.PlannedRouteEntity

@Dao
interface RouteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(route: PlannedRouteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(routes: List<PlannedRouteEntity>)

    @Query("SELECT * FROM local_routes ORDER BY createdAt DESC")
    suspend fun getAll(): List<PlannedRouteEntity>

    @Query("SELECT * FROM local_routes WHERE id = :id")
    suspend fun getById(id: String): PlannedRouteEntity?

    @Query("DELETE FROM local_routes WHERE id = :id")
    suspend fun deleteById(id: String)
}
