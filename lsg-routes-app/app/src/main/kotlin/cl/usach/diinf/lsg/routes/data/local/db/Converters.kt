package cl.usach.diinf.lsg.routes.data.local.db

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Room no persiste listas anidadas directamente: la geometria de una ruta se
 *  guarda como JSON. */
class Converters {
    private val gson = Gson()
    private val geometryType = object : TypeToken<List<List<Double>>>() {}.type

    @TypeConverter
    fun fromGeometry(value: List<List<Double>>): String = gson.toJson(value)

    @TypeConverter
    fun toGeometry(value: String): List<List<Double>> = gson.fromJson(value, geometryType)
}
