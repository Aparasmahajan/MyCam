package io.frame.core.data.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import io.frame.core.model.RetentionPolicy
import io.frame.core.model.RetentionSettings
import io.frame.core.model.StorageSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

@Serializable
enum class GSensitivity(val thresholdG: Float?) {
    OFF(null),
    LOW(3.0f),
    MEDIUM(2.0f),
    HIGH(1.2f),
}

/** Recording quality and the bitrate used for storage estimates (not forced on the encoder). */
@Serializable
enum class VideoQuality(val label: String, val estimatedBitrate: Int) {
    HD("720p", 6_000_000),
    FHD("1080p", 12_000_000),
    UHD("4K", 40_000_000),
}

@Serializable
data class DashcamOptions(
    val gSensitivity: GSensitivity = GSensitivity.MEDIUM,
    /** Off by default: recording conversations may need everyone's consent. */
    val recordAudio: Boolean = false,
    /** Stop this long after the charger is unplugged (the car was switched off). 0 = never. */
    val stopAfterUnplugSeconds: Int = 60,
    /** Paired car Bluetooth device that triggers the "tap to start" prompt. null = off. */
    val carBluetoothAddress: String? = null,
    val carBluetoothName: String? = null,
)

@Serializable
data class SportsOptions(
    val recordAudio: Boolean = true,
    /** Every score change also creates a highlight. */
    val autoHighlightOnScore: Boolean = true,
)

/** Text burned into the recorded video (it cannot be removed afterwards). */
@Serializable
data class OverlayOptions(
    /** Date, time, speed and position. */
    val dashcam: Boolean = true,
    /** Score, period and match clock. */
    val sports: Boolean = true,
)

@Serializable
data class FrameSettings(
    val retention: RetentionSettings = RetentionSettings(),
    val quality: VideoQuality = VideoQuality.FHD,
    val dashcam: DashcamOptions = DashcamOptions(),
    val sports: SportsOptions = SportsOptions(),
    val overlay: OverlayOptions = OverlayOptions(),
    val onboardingDone: Boolean = false,
)

class SettingsRepository(context: Context) {

    private val store: DataStore<FrameSettings> = DataStoreFactory.create(
        serializer = FrameSettingsSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { FrameSettings() },
        produceFile = { context.dataStoreFile("frame_settings.json") },
    )

    val settings: Flow<FrameSettings> = store.data

    suspend fun current(): FrameSettings = store.data.first()

    suspend fun update(transform: (FrameSettings) -> FrameSettings) {
        store.updateData { transform(it) }
    }

    suspend fun setPolicy(policy: RetentionPolicy) =
        update { it.copy(retention = it.retention.withPolicy(policy)) }

    suspend fun setStorage(storage: StorageSettings) =
        update { it.copy(retention = it.retention.copy(storage = storage)) }
}

private object FrameSettingsSerializer : Serializer<FrameSettings> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue = FrameSettings()

    override suspend fun readFrom(input: InputStream): FrameSettings = try {
        json.decodeFromString(FrameSettings.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable settings", e)
    }

    override suspend fun writeTo(t: FrameSettings, output: OutputStream) {
        output.write(json.encodeToString(FrameSettings.serializer(), t).encodeToByteArray())
    }
}
