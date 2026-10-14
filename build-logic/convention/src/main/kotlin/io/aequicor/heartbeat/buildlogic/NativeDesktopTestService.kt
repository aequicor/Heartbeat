package io.aequicor.heartbeat.buildlogic

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/** Serializes native test workers that share desktop focus, physical input and screen capture. */
internal abstract class NativeDesktopTestService : BuildService<BuildServiceParameters.None>
