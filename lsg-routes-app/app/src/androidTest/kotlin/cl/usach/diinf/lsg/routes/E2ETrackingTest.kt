package cl.usach.diinf.lsg.routes

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cl.usach.diinf.lsg.routes.data.local.db.LsgRoutesDatabase
import cl.usach.diinf.lsg.routes.data.local.entity.LocationFixEntity
import cl.usach.diinf.lsg.routes.data.local.entity.PendingAction
import cl.usach.diinf.lsg.routes.data.local.entity.RouteSessionEntity
import cl.usach.diinf.lsg.routes.data.local.entity.SessionSyncStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prueba de integracion E2E del esquema Room: verifica que la maquina de estados
 * de la sesion de seguimiento funciona correctamente con la base de datos real en memoria.
 */
@RunWith(AndroidJUnit4::class)
class E2ETrackingTest {

    private lateinit var db: LsgRoutesDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LsgRoutesDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun tracking_session_state_machine_happy_path() = runBlocking {
        val sessionDao = db.routeSessionDao()
        val trackingDao = db.trackingDao()

        // 1. Crear sesion local en estado RECORDING
        val session = RouteSessionEntity(
            id = "session-e2e-001",
            routeId = "route-001",
            idPlayers = 46,
            mode = "walking",
            status = SessionSyncStatus.RECORDING,
            startedAt = System.currentTimeMillis(),
        )
        sessionDao.insert(session)

        // 2. Insertar 5 fixes GPS de prueba
        val baseTime = System.currentTimeMillis()
        val fixes = listOf(
            fix("session-e2e-001", 0, -33.4500, -70.6500, baseTime),
            fix("session-e2e-001", 1, -33.4510, -70.6490, baseTime + 120_000),
            fix("session-e2e-001", 2, -33.4520, -70.6480, baseTime + 240_000),
            fix("session-e2e-001", 3, -33.4530, -70.6470, baseTime + 360_000),
            fix("session-e2e-001", 4, -33.4540, -70.6460, baseTime + 480_000),
        )
        fixes.forEach { trackingDao.insertFix(it) }

        // 3. Verificar que los fixes son observables via Flow
        val observed = trackingDao.observeFixes("session-e2e-001").first()
        assertEquals(5, observed.size)
        assertEquals(-33.4500, observed[0].lat, 0.0001)

        // 4. Transicion a COMPLETED_PENDING_UPLOAD
        sessionDao.updateEndedAt(
            "session-e2e-001", System.currentTimeMillis(),
            SessionSyncStatus.COMPLETED_PENDING_UPLOAD, PendingAction.FINISH,
        )

        val updated = sessionDao.getById("session-e2e-001")
        assertNotNull(updated)
        assertEquals(SessionSyncStatus.COMPLETED_PENDING_UPLOAD, updated!!.status)
        assertNotNull(updated.endedAt)

        // 5. Verificar fixes sin subir
        val unuploaded = trackingDao.getUnuploadedFixes("session-e2e-001")
        assertEquals(5, unuploaded.size)

        // 6. Marcar fixes como subidos (simula UploadWorker)
        trackingDao.markFixesUploaded("session-e2e-001", 4)
        val afterUpload = trackingDao.getUnuploadedFixes("session-e2e-001")
        assertEquals(0, afterUpload.size)

        // 7. Transicion a SYNCED
        sessionDao.updateStatus("session-e2e-001", SessionSyncStatus.SYNCED)
        val final = sessionDao.getById("session-e2e-001")
        assertEquals(SessionSyncStatus.SYNCED, final!!.status)
    }

    @Test
    fun pending_sessions_query_returns_only_pending() = runBlocking {
        val dao = db.routeSessionDao()

        dao.insert(RouteSessionEntity("s1", "r1", 46, "walking", SessionSyncStatus.RECORDING, 1000L))
        dao.insert(RouteSessionEntity("s2", "r1", 46, "walking", SessionSyncStatus.COMPLETED_PENDING_UPLOAD, 2000L))
        dao.insert(RouteSessionEntity("s3", "r1", 46, "walking", SessionSyncStatus.SYNCED, 3000L))

        val pending = dao.getPendingUpload()
        assertEquals(1, pending.size)
        assertEquals("s2", pending[0].id)
    }

    @Test
    fun max_client_seq_permite_reanudar_sin_duplicar() = runBlocking {
        val trackingDao = db.trackingDao()

        // Sesion nueva: sin fixes no hay maximo, la numeracion tiene que empezar en 0
        assertEquals(null, trackingDao.maxClientSeq("sesion-vacia"))

        val base = 1_000L
        listOf(0, 1, 2).forEach { seq ->
            trackingDao.insertFix(fix("sesion-reanudada", seq, -33.45, -70.65, base + seq * 1000L))
        }
        // Al reanudar se sigue desde el siguiente: repetir un clientSeq haria que
        // markFixesUploaded diera por subidos fixes que nunca salieron del telefono.
        assertEquals(2, trackingDao.maxClientSeq("sesion-reanudada"))
        assertEquals(null, trackingDao.maxClientSeq("otra-sesion"))
    }

    private fun fix(sessionId: String, seq: Int, lat: Double, lon: Double, ts: Long) =
        LocationFixEntity(
            sessionId = sessionId, lat = lat, lon = lon,
            accuracyM = 5f, altitudeM = 520.0, speedMps = 1.2f,
            bearingDeg = 45f, isMock = false,
            capturedAt = ts, clientSeq = seq,
        )
}
