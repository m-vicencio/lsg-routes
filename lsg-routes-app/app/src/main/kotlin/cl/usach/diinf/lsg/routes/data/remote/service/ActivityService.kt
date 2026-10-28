package cl.usach.diinf.lsg.routes.data.remote.service

import cl.usach.diinf.lsg.routes.data.remote.dto.ActivityStatsDto
import cl.usach.diinf.lsg.routes.data.remote.dto.PointTransactionDto
import cl.usach.diinf.lsg.routes.data.remote.dto.PointsBalanceDto
import retrofit2.http.GET
import retrofit2.http.Query

interface ActivityService {
    @GET("lsg-routes/activity/me")
    suspend fun getMyActivity(@Query("limit") limit: Int? = null): ActivityStatsDto

    @GET("lsg-routes/activity/points-balance")
    suspend fun getPointsBalance(): PointsBalanceDto

    @GET("lsg-routes/activity/transactions")
    suspend fun getTransactions(@Query("limit") limit: Int = 20): List<PointTransactionDto>
}
