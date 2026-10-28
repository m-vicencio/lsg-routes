package cl.usach.diinf.lsg.routes.core.di

import cl.usach.diinf.lsg.routes.BuildConfig
import cl.usach.diinf.lsg.routes.core.network.AuthAuthenticator
import cl.usach.diinf.lsg.routes.core.network.AuthInterceptor
import cl.usach.diinf.lsg.routes.core.network.NetworkConstants
import cl.usach.diinf.lsg.routes.data.remote.service.AuthService
import cl.usach.diinf.lsg.routes.data.remote.service.RouteService
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder().create()

    // Sin certificate pinning mientras el backend se sirva por HTTP. Al pasar a HTTPS,
    // fijar la clave publica con un pin de respaldo: el certificado de Let's Encrypt
    // (Caddy) rota cada 60-90 dias y un pin sobre el certificado dejaria la app sin red.
    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        authAuthenticator: AuthAuthenticator,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .authenticator(authAuthenticator)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                        else HttpLoggingInterceptor.Level.NONE
            }
        )
        .connectTimeout(NetworkConstants.TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(NetworkConstants.TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(NetworkConstants.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    fun provideAuthService(retrofit: Retrofit): AuthService =
        retrofit.create(AuthService::class.java)

    @Provides
    @Singleton
    fun provideRouteService(retrofit: Retrofit): RouteService =
        retrofit.create(RouteService::class.java)

    @Provides
    @Singleton
    fun provideSessionService(retrofit: Retrofit): cl.usach.diinf.lsg.routes.data.remote.service.SessionService =
        retrofit.create(cl.usach.diinf.lsg.routes.data.remote.service.SessionService::class.java)

    @Provides
    @Singleton
    fun provideAppMetaService(retrofit: Retrofit): cl.usach.diinf.lsg.routes.data.remote.service.AppMetaService =
        retrofit.create(cl.usach.diinf.lsg.routes.data.remote.service.AppMetaService::class.java)

    @Provides
    @Singleton
    fun provideActivityService(retrofit: Retrofit): cl.usach.diinf.lsg.routes.data.remote.service.ActivityService =
        retrofit.create(cl.usach.diinf.lsg.routes.data.remote.service.ActivityService::class.java)
}
