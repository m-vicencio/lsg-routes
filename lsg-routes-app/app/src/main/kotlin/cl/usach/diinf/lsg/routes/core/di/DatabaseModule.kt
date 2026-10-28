package cl.usach.diinf.lsg.routes.core.di

import android.content.Context
import androidx.room.Room
import cl.usach.diinf.lsg.routes.data.local.dao.RouteDao
import cl.usach.diinf.lsg.routes.data.local.dao.RouteSessionDao
import cl.usach.diinf.lsg.routes.data.local.dao.TrackingDao
import cl.usach.diinf.lsg.routes.data.local.db.LsgRoutesDatabase
import cl.usach.diinf.lsg.routes.data.local.db.MIGRATION_1_2
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Modulo Hilt para proveer la base de datos Room y sus DAOs. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LsgRoutesDatabase =
        Room.databaseBuilder(context, LsgRoutesDatabase::class.java, "lsg_routes_local.db")
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides
    fun provideRouteSessionDao(db: LsgRoutesDatabase): RouteSessionDao = db.routeSessionDao()

    @Provides
    fun provideTrackingDao(db: LsgRoutesDatabase): TrackingDao = db.trackingDao()

    @Provides
    fun provideRouteDao(db: LsgRoutesDatabase): RouteDao = db.routeDao()
}
