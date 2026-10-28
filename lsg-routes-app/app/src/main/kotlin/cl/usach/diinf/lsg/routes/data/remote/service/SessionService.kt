package cl.usach.diinf.lsg.routes.data.remote.service

import cl.usach.diinf.lsg.routes.data.remote.dto.CreateSessionRequest
import cl.usach.diinf.lsg.routes.data.remote.dto.SessionResponse
import cl.usach.diinf.lsg.routes.data.remote.dto.SessionSummaryDto
import cl.usach.diinf.lsg.routes.data.remote.dto.SessionTrackDto
import cl.usach.diinf.lsg.routes.data.remote.dto.SyncBatchRequest
import cl.usach.diinf.lsg.routes.data.remote.dto.SyncBatchResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface SessionService {
    @POST("lsg-routes/sessions")
    suspend fun createSession(@Body request: CreateSessionRequest): SessionResponse

    @POST("lsg-routes/sessions/{id}/sync")
    suspend fun syncBatch(@Path("id") id: String, @Body batch: SyncBatchRequest): SyncBatchResponse

    @POST("lsg-routes/sessions/{id}/finish")
    suspend fun finishSession(@Path("id") id: String): SessionSummaryDto

    @POST("lsg-routes/sessions/{id}/abort")
    suspend fun abortSession(@Path("id") id: String): SessionSummaryDto

    @GET("lsg-routes/sessions/{id}/summary")
    suspend fun getSummary(@Path("id") id: String): SessionSummaryDto

    @GET("lsg-routes/sessions/{id}/track")
    suspend fun getTrack(@Path("id") id: String): SessionTrackDto
}
