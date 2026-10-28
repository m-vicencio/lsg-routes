package cl.usach.diinf.lsg.routes.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import cl.usach.diinf.lsg.routes.data.local.dao.RouteDao
import cl.usach.diinf.lsg.routes.data.local.dao.RouteSessionDao
import cl.usach.diinf.lsg.routes.data.local.dao.TrackingDao
import cl.usach.diinf.lsg.routes.data.local.entity.AccelSampleEntity
import cl.usach.diinf.lsg.routes.data.local.entity.LocationFixEntity
import cl.usach.diinf.lsg.routes.data.local.entity.PlannedRouteEntity
import cl.usach.diinf.lsg.routes.data.local.entity.RouteSessionEntity
import cl.usach.diinf.lsg.routes.data.local.entity.StepCountEntity
import cl.usach.diinf.lsg.routes.data.local.entity.UploadBatchEntity

@Database(
    entities = [
        RouteSessionEntity::class,
        LocationFixEntity::class,
        AccelSampleEntity::class,
        StepCountEntity::class,
        UploadBatchEntity::class,
        PlannedRouteEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class LsgRoutesDatabase : RoomDatabase() {
    abstract fun routeSessionDao(): RouteSessionDao
    abstract fun trackingDao(): TrackingDao
    abstract fun routeDao(): RouteDao
}
