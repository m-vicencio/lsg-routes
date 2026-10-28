package cl.usach.diinf.lsg.routes.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v2 agrega el soporte offline: cache local de rutas y la accion pendiente
 *  (finalizar/cancelar) de una sesion todavia sin subir. No toca datos existentes. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_routes` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `name` TEXT NOT NULL,
                `mode` TEXT NOT NULL,
                `origin` TEXT NOT NULL,
                `geometry` TEXT NOT NULL,
                `plannedDistanceM` REAL,
                `plannedDurationS` INTEGER,
                `createdAt` TEXT NOT NULL,
                `isPublic` INTEGER NOT NULL,
                `sharedByName` TEXT,
                `cachedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "ALTER TABLE `local_route_sessions` ADD COLUMN `pendingAction` TEXT NOT NULL DEFAULT 'FINISH'"
        )
    }
}
