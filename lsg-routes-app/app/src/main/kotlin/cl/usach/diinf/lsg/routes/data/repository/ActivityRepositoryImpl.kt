package cl.usach.diinf.lsg.routes.data.repository

import cl.usach.diinf.lsg.routes.data.remote.dto.ActivityLogDto
import cl.usach.diinf.lsg.routes.data.remote.service.ActivityService
import cl.usach.diinf.lsg.routes.domain.model.ActivityItem
import cl.usach.diinf.lsg.routes.domain.model.ActivityMode
import cl.usach.diinf.lsg.routes.domain.model.ActivityStats
import cl.usach.diinf.lsg.routes.domain.model.DimensionBalance
import cl.usach.diinf.lsg.routes.domain.model.PointTransaction
import cl.usach.diinf.lsg.routes.domain.model.PointsBalance
import cl.usach.diinf.lsg.routes.domain.repository.ActivityRepository
import timber.log.Timber
import javax.inject.Inject

class ActivityRepositoryImpl @Inject constructor(
    private val activityService: ActivityService,
) : ActivityRepository {

    override suspend fun getMyActivity(limit: Int?): Result<ActivityStats> = runCatching {
        val dto = activityService.getMyActivity(limit)
        ActivityStats(
            totalActivities = dto.totalActivities,
            totalDistanceM = dto.totalDistanceM,
            totalPoints = dto.totalPoints,
            recent = dto.recent.map { it.toDomain() },
        )
    }.onFailure { Timber.e(it, "Error al obtener actividad") }

    override suspend fun getPointsBalance(): Result<PointsBalance> = runCatching {
        val dto = activityService.getPointsBalance()
        PointsBalance(
            total = dto.total,
            dimensions = dto.dimensions.map {
                DimensionBalance(it.idPointDimension, it.balance, it.name)
            },
        )
    }.onFailure { Timber.e(it, "Error al obtener saldo de puntos") }

    override suspend fun getTransactions(limit: Int): Result<List<PointTransaction>> = runCatching {
        activityService.getTransactions(limit).map {
            PointTransaction(
                pointDimensionId = it.pointDimensionId,
                amount = it.amount,
                balanceBefore = it.balanceBefore,
                balanceAfter = it.balanceAfter,
                sourceType = it.sourceType,
                reason = it.reason,
                createdAt = it.createdAt,
            )
        }
    }.onFailure { Timber.e(it, "Error al obtener transacciones") }

    private fun ActivityLogDto.toDomain() = ActivityItem(
        sessionId = sessionId,
        mode = runCatching { ActivityMode.fromKey(mode) }.getOrDefault(ActivityMode.WALKING),
        status = status,
        distanceM = distanceM,
        durationS = durationS,
        avgSpeedKmh = avgSpeedKmh,
        awardedPoints = awardedPoints,
        createdAt = createdAt,
    )
}
