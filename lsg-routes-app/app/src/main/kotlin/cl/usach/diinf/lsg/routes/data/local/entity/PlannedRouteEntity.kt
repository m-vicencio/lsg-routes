package cl.usach.diinf.lsg.routes.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Copia local de una ruta ya vista con conexion: permite abrirla y usarla como
 *  guia para iniciar una sesion de tracking sin red. */
@Entity(tableName = "local_routes")
data class PlannedRouteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mode: String,
    val origin: String,
    val geometry: List<List<Double>>,
    val plannedDistanceM: Double?,
    val plannedDurationS: Int?,
    val createdAt: String,
    val isPublic: Boolean,
    val sharedByName: String?,
    val cachedAt: Long,
)
