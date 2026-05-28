package com.example.djimichi

import android.app.Application
import android.content.Context
import android.os.Build

class DjiMichiApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Carga las librerías nativas del SDK de DJI. Sin esta llamada el
        // classloader no puede encontrar dji.v5.manager.SDKManager → crash.
        com.cySdkyc.clx.Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Evita inicializaciones duplicadas en procesos secundarios (ej. :remote)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val processName = getProcessName()
            if (packageName != processName) return
        }
    }
}
