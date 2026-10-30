@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionStage
import kotlin.math.min
import kotlin.math.roundToInt

/** Approved desktop storyboard rendered without JVM, child-label erasure, or a duplicate logo pulse. */
// Authored coordinates and opacity keyframes mirror the approved .pen storyboard.
@Suppress("MagicNumber")
internal class LauncherScene(private val theme: LauncherTheme) {
    fun draw(
        canvas: LauncherCanvas,
        width: Int,
        height: Int,
        status: LauncherSceneStatus,
        frame: LauncherMotionFrame,
        tip: Int,
        isRussian: Boolean,
    ) = with(canvas) {
        fill(0, 0, width, height, theme.background)
        val sidebar = if (width >= COMPACT_WIDTH) SIDEBAR_WIDTH else 0
        fill(0, 0, sidebar, height, theme.sidebar)
        val cx = sidebar + (width - sidebar) / 2
        val cy = height / 2
        if (status.error != null) {
            text("Не удалось запустить Heartbeat", cx - 250, cy - 70, 500, theme.textPrimary, 23)
            text(status.error, cx - 250, cy - 20, 500, theme.textSecondary, multiline = true)
            return@with
        }
        drawWelcome(canvas, cx, cy, frame, isRussian)
        drawBrand(canvas, cx, cy, status, frame)
        if (frame.download > 0) drawDownload(canvas, cx, cy, height, status, frame, tip)
        if (frame.opening > 0) drawOpening(canvas, cx, cy, width - sidebar, sidebar, height, frame)
    }

    private fun drawWelcome(canvas: LauncherCanvas, cx: Int, cy: Int, frame: LauncherMotionFrame, isRussian: Boolean) {
        if (frame.welcome > 0) {
            canvas.text(
                if (isRussian) "Добро пожаловать" else "Welcome",
                cx - 300,
                cy - 77 + ((1 - frame.welcome) * 5).roundToInt(),
                600,
                faded(theme.textPrimary, frame.welcome),
                28,
            )
        }
        if (frame.name > 0) {
            canvas.text("Heartbeat", cx - 300, cy - 77, 600, faded(theme.textPrimary, frame.name), 28)
        }
    }

    private fun drawBrand(
        canvas: LauncherCanvas,
        cx: Int,
        cy: Int,
        status: LauncherSceneStatus,
        frame: LauncherMotionFrame,
    ) {
        val shell = LauncherBrand.shell.map {
            LauncherPoint(
                cx - 60 + it.x * BRAND_SCALE,
                cy - 120 + it.y * BRAND_SCALE,
            )
        }
        if (frame.shell > 0) canvas.logo(shell, cx - 60, cy - 120, 120, frame.shell, theme.background)
        if (frame.pulse <= 0) return
        val points = LauncherBrand.pulse.map {
            LauncherPoint(
                mix(cx - 60 + it.x * BRAND_SCALE, cx - 160 + (it.x - 26) * 320 / 77, frame.spread),
                mix(cy - 120 + it.y * BRAND_SCALE, cy + 12 + (it.y - 69) * .75, frame.spread),
            )
        }
        val drawn = points.take((points.size * frame.pulseDraw).roundToInt().coerceAtLeast(2))
        val progressColor = if (theme.isDark) {
            theme.progressFill
        } else {
            launcherBlend(
                theme.textSecondary,
                theme.progressFill,
                .4,
            )
        }
        val originalInk = launcherBlend(LauncherBrand.WHITE, theme.textPrimary, frame.shell)
        val color = faded(launcherBlend(progressColor, originalInk, frame.spread), frame.pulse)
        val stroke = mix(4.0, 3.0, frame.spread)
        val track = faded(theme.progressTrack, frame.pulse)
        canvas.path(drawn, launcherBlend(color, track, 1 - frame.spread), stroke)
        val fraction = status.fraction
        val left = when {
            fraction != null -> points.first().x.toInt()
            frame.shimmer < 0 -> cx - 40
            else -> cx - 240 + (frame.shimmer * 400).toInt()
        }
        val right = if (fraction != null) {
            left + ((points.last().x - points.first().x) * fraction).toInt()
        } else {
            left + 80
        }
        canvas.clipped(left, cy - 140, right, cy + 60) { canvas.path(drawn, color, stroke) }
    }

    private fun drawDownload(
        canvas: LauncherCanvas,
        cx: Int,
        cy: Int,
        height: Int,
        status: LauncherSceneStatus,
        frame: LauncherMotionFrame,
        tip: Int,
    ) = with(canvas) {
        val stage = when (status.progress.stage) {
            ProvisionStage.MANIFEST -> "Подготавливаем компоненты AI Studio"
            ProvisionStage.DOWNLOAD -> "Скачиваем компоненты AI Studio"
            ProvisionStage.EXTRACT -> "Распаковываем компоненты"
            ProvisionStage.ACTIVATE -> "Подготавливаем AI Studio"
        }
        val secondary = faded(theme.textSecondary, frame.download)
        text(stage, cx - 240, cy - 46, 480, secondary)
        if (status.progress.stage == ProvisionStage.DOWNLOAD) {
            val downloaded = downloadMegabytes(status.progress.downloadedBytes)
            val total = status.fraction?.let { " / ${downloadMegabytes(status.progress.totalBytes)}" }.orEmpty()
            text("$downloaded$total МБ", cx - 160, cy + 52, 240, secondary, 12, platform.windows.DT_LEFT)
            status.fraction?.let {
                text("${(it * 100).toInt()}%", cx + 80, cy + 52, 80, secondary, 12, platform.windows.DT_RIGHT)
            }
            val tipY = min(cy + 260, height - 120)
            val alpha = frame.download * frame.tipAlpha
            val textWidth = TIPS[tip].length * 8
            val left = cx - (textWidth + 26) / 2
            val tipColor = faded(theme.textSecondary, alpha)
            val tipOffset = ((1 - frame.tipAlpha) * 4).roundToInt()
            text(
                TIPS[tip],
                left + 26,
                tipY + tipOffset,
                textWidth + 30,
                tipColor,
                alignment = platform.windows.DT_LEFT,
            )
            val icon = when (tip) {
                0 -> listOf(0 to 5, 7 to 1, 14 to 5, 7 to 9, 0 to 5, 0 to 10, 7 to 14, 14 to 10, 14 to 5)
                1 -> listOf(4 to 3, 0 to 7, 4 to 11, 10 to 3, 14 to 7, 10 to 11)
                else -> listOf(0 to 1, 14 to 1, 14 to 11, 0 to 11, 0 to 1, 7 to 11, 7 to 15, 3 to 15, 11 to 15)
            }
            path(
                icon.map { LauncherPoint((left + it.first).toDouble(), (tipY + tipOffset + 4 + it.second).toDouble()) },
                faded(theme.textSecondary, alpha * .65),
                1,
            )
            for (index in TIPS.indices) {
                fill(
                    cx - 33 + index * 26,
                    tipY + 45,
                    14,
                    2,
                    faded(theme.textSecondary, frame.download * if (index == tip) .45 else .12),
                    1,
                )
            }
        }
    }

    private fun drawOpening(
        canvas: LauncherCanvas,
        cx: Int,
        cy: Int,
        availableWidth: Int,
        sidebar: Int,
        height: Int,
        frame: LauncherMotionFrame,
    ) = with(canvas) {
        val alpha = frame.opening
        if (sidebar > 0) drawSidebar(canvas, height, alpha)
        val width = min(COMPOSER_WIDTH, availableWidth - 48).coerceAtLeast(120)
        val left = cx - width / 2
        val top = cy - 40
        val surface = launcherBlend(theme.textPrimary, theme.background, .045 * alpha)
        val skeleton = launcherBlend(theme.textPrimary, surface, .075 * alpha)
        val highlight = if (theme.isDark) {
            launcherBlend(theme.textPrimary, skeleton, .12 * alpha)
        } else {
            launcherBlend(theme.background, skeleton, .8 * alpha)
        }
        val beam = if (frame.shimmer < 0) null else left - 240 + (frame.shimmer * (width + 480)).toInt()
        fill(left, top, width, COMPOSER_HEIGHT, surface, 12)
        shimmer(left + 16, top + 20, min(208, width - 32), 7, skeleton, highlight, beam)
        shimmer(left + 16, top + 68, 28, 26, skeleton, highlight, beam)
        shimmer(left + width - 174, top + 68, min(158, width / 3), 26, skeleton, highlight, beam)
        text("Загружаем рабочее пространство", cx - 280, cy - 120, 560, faded(theme.textSecondary, alpha))
    }

    private fun drawSidebar(canvas: LauncherCanvas, height: Int, alpha: Double) = with(canvas) {
        val textColor = launcherBlend(theme.textSecondary, theme.sidebar, alpha)
        text("Поиск по сессиям", 44, 53, 190, textColor, alignment = platform.windows.DT_LEFT)
        val lengths = listOf(156, 190, 132, 172)
        lengths.forEachIndexed { index, length ->
            fill(20, 112 + index * 36, length, 8, launcherBlend(theme.textPrimary, theme.sidebar, .04 * alpha), 4)
            fill(20, 126 + index * 36, 78, 5, launcherBlend(theme.textPrimary, theme.sidebar, .025 * alpha), 3)
        }
        text("+", 185, 10, 28, textColor)
        path(
            listOf(
                LauncherPoint(230.0, 16.0),
                LauncherPoint(244.0, 16.0),
                LauncherPoint(244.0, 28.0),
                LauncherPoint(230.0, 28.0),
                LauncherPoint(230.0, 16.0),
            ),
            textColor,
            1,
        )
        path(listOf(LauncherPoint(234.0, 16.0), LauncherPoint(234.0, 28.0)), textColor, 1)
        // Quiet placeholder for the bottom navigation; all startup studio actions remain disabled.
        for (index in 0..3) {
            fill(
                18 + index * 34,
                height - 32,
                14,
                12,
                launcherBlend(theme.textSecondary, theme.sidebar, .2 * alpha),
                3,
            )
        }
    }

    private fun faded(color: UInt, opacity: Double): UInt = launcherBlend(color, theme.background, opacity)
    private fun mix(from: Double, to: Double, fraction: Double): Double = from + (to - from) * fraction

    companion object {
        private const val BRAND_SCALE = 120.0 / 128
        private const val COMPACT_WIDTH = 720
        private const val SIDEBAR_WIDTH = 264
        private const val COMPOSER_WIDTH = 760
        private const val COMPOSER_HEIGHT = 106
        private val TIPS = listOf(
            "Меняйте модели под задачу",
            "Работайте с кодом проекта",
            "Поручайте ИИ действия на компьютере",
        )
    }
}
