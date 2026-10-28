package cl.usach.diinf.lsg.routes.domain.model

/** Modos de actividad soportados en v1. */
enum class ActivityMode(val key: String, val labelEs: String, val labelEn: String, val pointsPerKm: Int) {
    WALKING("walking", "Caminata", "Walking", 8),
    CYCLING("cycling", "Bicicleta", "Cycling", 4),
    JOGGING("jogging", "Trote", "Jogging", 12),
    HIKING("hiking", "Senderismo", "Hiking", 10);

    companion object {
        fun fromKey(key: String): ActivityMode = entries.first { it.key == key }
    }
}
