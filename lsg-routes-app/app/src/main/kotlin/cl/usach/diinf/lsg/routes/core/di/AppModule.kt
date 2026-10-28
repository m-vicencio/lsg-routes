package cl.usach.diinf.lsg.routes.core.di

import cl.usach.diinf.lsg.routes.data.repository.ActivityRepositoryImpl
import cl.usach.diinf.lsg.routes.data.repository.AuthRepositoryImpl
import cl.usach.diinf.lsg.routes.data.repository.LocalTrackingRepositoryImpl
import cl.usach.diinf.lsg.routes.data.repository.RouteRepositoryImpl
import cl.usach.diinf.lsg.routes.data.repository.SessionRepositoryImpl
import cl.usach.diinf.lsg.routes.domain.repository.ActivityRepository
import cl.usach.diinf.lsg.routes.domain.repository.AuthRepository
import cl.usach.diinf.lsg.routes.domain.repository.LocalTrackingRepository
import cl.usach.diinf.lsg.routes.domain.repository.RouteRepository
import cl.usach.diinf.lsg.routes.domain.repository.SessionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds @Singleton
    abstract fun bindRouteRepository(impl: RouteRepositoryImpl): RouteRepository

    @Binds @Singleton
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository

    @Binds @Singleton
    abstract fun bindLocalTrackingRepository(impl: LocalTrackingRepositoryImpl): LocalTrackingRepository

    @Binds @Singleton
    abstract fun bindActivityRepository(impl: ActivityRepositoryImpl): ActivityRepository
}
