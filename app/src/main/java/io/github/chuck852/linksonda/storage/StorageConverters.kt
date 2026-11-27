package io.github.chuck852.linksonda.storage

import androidx.room.TypeConverter
import io.github.chuck852.linksonda.domain.Incident
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** JSON is limited to the protocol facts already exposed by the domain models. */
class StorageConverters {
    @TypeConverter
    fun requestToString(value: ProbeRequest): String = storageJson.encodeToString(value)

    @TypeConverter
    fun stringToRequest(value: String): ProbeRequest = storageJson.decodeFromString(value)

    @TypeConverter
    fun resultToString(value: ProbeResult): String = storageJson.encodeToString(value)

    @TypeConverter
    fun stringToResult(value: String): ProbeResult = storageJson.decodeFromString(value)

    @TypeConverter
    fun incidentToString(value: Incident): String = storageJson.encodeToString(value)

    @TypeConverter
    fun stringToIncident(value: String): Incident = storageJson.decodeFromString(value)
}
