package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.computeruse.api.CaptureColorModel
import io.aequicor.heartbeat.feature.computeruse.api.CaptureEncoding
import io.aequicor.heartbeat.feature.computeruse.api.CaptureFormat
import io.aequicor.heartbeat.feature.computeruse.api.CaptureId
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapabilities
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapturePresentation
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermission
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePresentation
import io.aequicor.heartbeat.feature.computeruse.api.EncodedFrame
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.api.WindowId
import io.aequicor.heartbeat.feature.computeruse.api.WindowTarget
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ComputerUsePreferences
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ComputerUseSettings
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameEncoder
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameStore
import io.aequicor.heartbeat.feature.computeruse.impl.domain.InputInjector
import io.aequicor.heartbeat.feature.computeruse.impl.domain.OsPermissions
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PixelGrid
import io.aequicor.heartbeat.feature.computeruse.impl.domain.RawFrame
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenCapturer
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenPoint
import io.aequicor.heartbeat.feature.computeruse.impl.domain.WindowCatalog
import io.aequicor.heartbeat.feature.computeruse.impl.domain.solidGrid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher

/** Test dispatchers on the standard test scheduler. */
internal class TestDispatchers(dispatcher: CoroutineDispatcher = StandardTestDispatcher()) : DispatcherProvider {
    override val main: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
}

/** Toggle reader over a mutable map. */
internal class FakeToggles(values: Map<String, Boolean> = emptyMap()) : FeatureToggles {
    private val current = values.mapValues { MutableStateFlow(it.value) }.toMutableMap()

    /** Overrides one toggle value. */
    fun set(key: String, value: Boolean) {
        current.getOrPut(key) { MutableStateFlow(value) }.value = value
    }

    @Suppress("UNCHECKED_CAST") // Tests observe boolean flags only.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> =
        current.getOrPut(toggle.key) { MutableStateFlow(toggle.default as Boolean) } as Flow<T>

    @Suppress("UNCHECKED_CAST") // tests read boolean flags only
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T =
        (current[toggle.key]?.value ?: toggle.default) as T
}

/** Live profile preference used by tests with computer tools explicitly enabled. */
internal class FakeComputerUsePreferences(isEnabled: Boolean = true) : ComputerUsePreferences {
    private val current = MutableStateFlow(ComputerUseSettings(isEnabled = isEnabled))

    override suspend fun read(): ComputerUseSettings = current.value
    override fun observe(): Flow<ComputerUseSettings> = current
    override suspend fun setEnabled(isEnabled: Boolean) {
        current.value = current.value.copy(isEnabled = isEnabled)
    }
    override suspend fun setPreset(name: String): Boolean {
        current.value = current.value.copy(preset = name)
        return true
    }
    override suspend fun setCursorIncluded(isIncluded: Boolean) {
        current.value = current.value.copy(isCursorIncluded = isIncluded)
    }
}

/** Real presentation barrier with a registered synthetic window for host operation assertions. */
internal class TestComputerUseCapturePresentation(dispatchers: DispatcherProvider) : ComputerUseCapturePresentation {
    private val delegate = DefaultComputerUseCapturePresentation(dispatchers)
    var isSuppressed: Boolean = false
        private set
    var suppressions: Int = 0
        private set

    /** Makes restoring the app windows fail after the operation with this error. */
    var restoreFailure: Throwable? = null

    init {
        delegate.register {
            isSuppressed = true
            suppressions++
            AutoCloseable {
                isSuppressed = false
                restoreFailure?.let { throw it }
            }
        }
    }

    override fun register(presentation: ComputerUsePresentation): AutoCloseable = delegate.register(presentation)
    override suspend fun <T> withoutPresentation(action: suspend () -> T): T = delegate.withoutPresentation(action)
}

/** Captures a fixed frame and reports fixed bounds; can be told to disappear. */
internal class FakeScreenCapturer(private val widthPx: Int = 200, private val heightPx: Int = 100) : ScreenCapturer {
    var bounds: ScreenBounds? = ScreenBounds(10, 20, widthPx, heightPx)
    var captures: Int = 0
        private set
    var onCapture: () -> Unit = {}

    override suspend fun capture(mode: ComputerUseMode, region: CaptureRegion?, isCursorIncluded: Boolean): RawFrame? {
        onCapture()
        val current = bounds ?: return null
        captures++
        return RawFrame(solidGrid(widthPx, heightPx, 0xFF336699.toInt()), current, 0L)
    }

    override suspend fun currentBounds(mode: ComputerUseMode): ScreenBounds? = bounds
}

/** Window list and activation recorder. */
internal class FakeWindowCatalog(
    private val targets: List<WindowTarget> = emptyList(),
    override val isAvailable: Boolean = true,
) : WindowCatalog {
    var activations: Int = 0
        private set
    var isActivationAllowed: Boolean = true

    override suspend fun list(): List<WindowTarget> = targets

    override suspend fun resolve(id: WindowId): WindowTarget? = targets.firstOrNull { it.id == id }

    override suspend fun activate(target: WindowTarget): Boolean {
        activations++
        return isActivationAllowed
    }
}

/** Input recorder; refuses on demand. */
internal class FakeInputInjector(override val isAvailable: Boolean = true) : InputInjector {
    val applied = mutableListOf<InputAction>()
    val points = mutableListOf<ScreenPoint>()
    var refusal: ComputerUseFailure? = null
    var onInput: () -> Unit = {}

    override suspend fun apply(action: InputAction, map: (FramePoint) -> ScreenPoint?): InputOutcome {
        onInput()
        val refused = refusal ?: return mappedOutcome(action, map)
        return InputOutcome.Rejected(refused)
    }

    private fun mappedOutcome(action: InputAction, map: (FramePoint) -> ScreenPoint?): InputOutcome {
        val point = if (action.space == null) null else action.firstPoint()
        val mapped = if (point == null) null else map(point)
        if (action.space != null && mapped == null) {
            return InputOutcome.Rejected(ComputerUseFailure.RegionOutOfBounds)
        }
        if (mapped != null) points += mapped
        applied += action
        return InputOutcome.Applied
    }

    private fun InputAction.firstPoint(): FramePoint? = when (this) {
        is InputAction.MoveTo -> point
        is InputAction.Click -> point
        is InputAction.Drag -> from
        is InputAction.Scroll -> point
        is InputAction.Type -> null
        is InputAction.Key -> null
    }
}

/** Deterministic codec: the encoded size grows with the pixel count and shrinks with the quality. */
internal class FakeFrameEncoder : FrameEncoder {
    val encoded = mutableListOf<EncodedFrame>()
    var minimumBytes: Int = 0

    override suspend fun encode(frame: PixelGrid, encoding: CaptureEncoding): EncodedFrame {
        val factor = when (encoding.format) {
            CaptureFormat.Png -> PNG_FACTOR
            CaptureFormat.Jpeg -> encoding.quality.toDouble() / JPEG_QUALITY_DIVISOR
        }
        val colorFactor = when (encoding.colorModel) {
            CaptureColorModel.Rgb -> 1.0
            CaptureColorModel.Gray8, CaptureColorModel.Indexed -> GRAY_FACTOR
        }
        val size = (frame.argb.size * factor * colorFactor).toInt().coerceAtLeast(minimumBytes)
            .coerceAtLeast(HEADER_BYTES)
        val content = ByteArray(size) { (it % BYTE_VALUES).toByte() }
        writeInt(content, 0, frame.widthPx)
        writeInt(content, INT_BYTES, frame.heightPx)
        val result = EncodedFrame(encoding.format, frame.widthPx, frame.heightPx, content)
        encoded += result
        return result
    }

    override suspend fun decode(content: ByteArray): PixelGrid {
        if (content.size < HEADER_BYTES) return solidGrid(1, 1, 0)
        val width = readInt(content, 0)
        val height = readInt(content, INT_BYTES)
        if (width <= 0 || height <= 0) return solidGrid(1, 1, 0)
        return solidGrid(width, height, 0)
    }

    private companion object {
        const val PNG_FACTOR = 0.5
        const val GRAY_FACTOR = 0.4
        const val JPEG_QUALITY_DIVISOR = 200.0
        const val BYTE_VALUES = 7
        const val INT_BYTES = 4
        const val HEADER_BYTES = 8
        const val BYTE_BITS = 8
        const val BYTE_MASK = 0xFF

        fun writeInt(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value shr (BYTE_BITS * 3) and BYTE_MASK).toByte()
            target[offset + 1] = (value shr (BYTE_BITS * 2) and BYTE_MASK).toByte()
            target[offset + 2] = (value shr BYTE_BITS and BYTE_MASK).toByte()
            target[offset + 3] = (value and BYTE_MASK).toByte()
        }

        fun readInt(source: ByteArray, offset: Int): Int = (source[offset].toInt() and BYTE_MASK shl BYTE_BITS * 3) or
            (source[offset + 1].toInt() and BYTE_MASK shl BYTE_BITS * 2) or
            (source[offset + 2].toInt() and BYTE_MASK shl BYTE_BITS) or
            (source[offset + 3].toInt() and BYTE_MASK)
    }
}

/** In-memory frame storage. */
internal class FakeFrameStore : FrameStore {
    val files = mutableMapOf<String, ByteArray>()
    var deleteFailure: Exception? = null
    var deletedSessions: Int = 0
        private set

    override suspend fun write(session: CaptureSessionId, id: CaptureId, frame: EncodedFrame): String {
        val path = "${session.value}/${id.value}.${frame.format.name}"
        files[path] = frame.content
        return path
    }

    override suspend fun read(path: String): ByteArray? = files[path]

    override suspend fun delete(session: CaptureSessionId) {
        deleteFailure?.let { throw it }
        deletedSessions++
        files.keys.filter { it.startsWith("${session.value}/") }.forEach { files.remove(it) }
    }
}

/** Fixed permission probe. */
internal class FakeOsPermissions(
    var capabilities: ComputerUseCapabilities = ComputerUseCapabilities(
        isCaptureAvailable = true,
        isWindowCaptureAvailable = true,
        isInputAvailable = true,
        isDesktopInputAllowed = true,
    ),
) : OsPermissions {
    var probes: Int = 0
        private set
    var opened: List<ComputerUsePermission> = emptyList()
        private set

    /** What [openSettings] reports; `false` simulates a host without the settings page. */
    var isSettingsOpenable: Boolean = true

    override suspend fun probe(): ComputerUseCapabilities {
        probes++
        return capabilities
    }

    override suspend fun openSettings(permission: ComputerUsePermission): Boolean {
        opened = opened + permission
        return isSettingsOpenable
    }
}

/** A window target with a predictable identity. */
internal fun windowTarget(
    id: String = "w1",
    widthPx: Int = 200,
    heightPx: Int = 100,
    isMinimized: Boolean = false,
): WindowTarget = WindowTarget(
    id = WindowId(id),
    application = "Notes",
    title = "Meeting notes",
    bounds = ScreenBounds(30, 40, widthPx, heightPx),
    isMinimized = isMinimized,
)
