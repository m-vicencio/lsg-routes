package cl.usach.diinf.lsg.routes.core.network

import cl.usach.diinf.lsg.routes.BuildConfig

/** Constantes de red del proyecto. */
object NetworkConstants {
    val BASE_URL: String = BuildConfig.BASE_URL

    // Tiempo de espera para llamadas de red en segundos
    const val TIMEOUT_S = 30L
}
