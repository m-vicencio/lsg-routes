package cl.usach.diinf.lsg.routes.data.repository

import cl.usach.diinf.lsg.routes.data.local.dao.RouteSessionDao
import cl.usach.diinf.lsg.routes.data.local.dao.TrackingDao
import cl.usach.diinf.lsg.routes.data.local.entity.PendingAction
import cl.usach.diinf.lsg.routes.data.local.entity.RouteSessionEntity
import cl.usach.diinf.lsg.routes.data.local.entity.SessionSyncStatus
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LocalTrackingRepositoryTest {

    private lateinit var sessionDao: RouteSessionDao
    private lateinit var trackingDao: TrackingDao
    private lateinit var repository: LocalTrackingRepositoryImpl

    @Before
    fun setup() {
        sessionDao = mockk(relaxed = true)
        trackingDao = mockk(relaxed = true)
        repository = LocalTrackingRepositoryImpl(sessionDao, trackingDao)
    }

    @Test
    fun `createLocalSession inserts entity with RECORDING status`() = runTest {
        coEvery { sessionDao.insert(any()) } just Runs

        repository.createLocalSession("session-1", "route-1", 46, "walking")

        coVerify {
            sessionDao.insert(match { it.id == "session-1" && it.status == SessionSyncStatus.RECORDING })
        }
    }

    @Test
    fun `endLocalSession updates status to COMPLETED_PENDING_UPLOAD`() = runTest {
        coEvery { sessionDao.updateEndedAt(any(), any(), any(), any()) } just Runs

        repository.endLocalSession("session-1")

        coVerify {
            sessionDao.updateEndedAt(
                "session-1", any(), SessionSyncStatus.COMPLETED_PENDING_UPLOAD, PendingAction.FINISH
            )
        }
    }

    @Test
    fun `endLocalSession con isAbort marca pendingAction ABORT`() = runTest {
        coEvery { sessionDao.updateEndedAt(any(), any(), any(), any()) } just Runs

        repository.endLocalSession("session-1", isAbort = true)

        coVerify {
            sessionDao.updateEndedAt("session-1", any(), SessionSyncStatus.COMPLETED_PENDING_UPLOAD, PendingAction.ABORT)
        }
    }

    @Test
    fun `getPendingSessions returns sessions with COMPLETED_PENDING_UPLOAD status`() = runTest {
        val pending = listOf(
            RouteSessionEntity("s1", "r1", 46, "walking", SessionSyncStatus.COMPLETED_PENDING_UPLOAD, 1000L)
        )
        coEvery { sessionDao.getPendingUpload() } returns pending

        val result = repository.getPendingSessions()

        assertEquals(1, result.size)
        assertEquals(SessionSyncStatus.COMPLETED_PENDING_UPLOAD, result[0].status)
    }
}
