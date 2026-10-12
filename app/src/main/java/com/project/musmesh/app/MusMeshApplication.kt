package com.project.musmesh.app

import android.app.Application

class MusMeshApplication : Application() {
    val coordinator by lazy { AppCoordinator(this) }
}
