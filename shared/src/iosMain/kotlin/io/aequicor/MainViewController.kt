package io.aequicor

import androidx.compose.ui.window.ComposeUIViewController

/** iOS entry point: hosts [App] in a `UIViewController` (called from `iosApp/ContentView.swift`). */
@Suppress("FunctionNaming") // Factory named after the type it returns; the Swift side calls it by this name.
fun MainViewController() = ComposeUIViewController { App() }
