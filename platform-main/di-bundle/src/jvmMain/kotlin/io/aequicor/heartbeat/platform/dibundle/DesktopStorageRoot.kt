package io.aequicor.heartbeat.platform.dibundle

import io.aequicor.heartbeat.core.datastore.impl.jvmStorageRootPath

/** Storage root shared by desktop UI and worker, resolved without constructing a graph or opening stores. */
public fun desktopStorageRoot(): String = jvmStorageRootPath()
