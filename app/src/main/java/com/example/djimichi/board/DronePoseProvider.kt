package com.example.djimichi.board

import android.util.Log
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.manager.KeyManager

/**
 * Lee la pose actual del dron del SDK (GPS + heading) para usarla como
 * "home" del tablero de michi.
 *
 * El Mini 4 Pro reporta:
 *  - KeyAircraftLocation3D: (lat, lon, alt) WGS-84
 *  - KeyCompassHeading: heading desde el norte magnético en grados [-180, 180]
 *
 * NOTA: el dron necesita lock de GPS antes de leer una pose válida.
 * Verificar:
 *  - KeyGPSSignalLevel >= 3 (al menos GOOD)
 *  - KeyGPSSatelliteCount >= 10 (típicamente 12-20 outdoor en cielo abierto)
 *
 * Sin esto, la posición puede saltar varios metros entre lecturas.
 */
class DronePoseProvider {

    /**
     * Lee la pose actual sincrónicamente.
     * Devuelve null si no hay datos disponibles (típico antes del lock GPS).
     */
    fun readCurrentPose(): HomePose? {
        val km = KeyManager.getInstance()

        val location = km.getValue(
            KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D)
        ) ?: run {
            Log.w(TAG, "Sin posición GPS disponible")
            return null
        }

        val heading = km.getValue(
            KeyTools.createKey(FlightControllerKey.KeyCompassHeading)
        ) ?: run {
            Log.w(TAG, "Sin heading disponible")
            return null
        }

        return HomePose(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeM = location.altitude,
            yawDegrees = heading
        )
    }

    /** Estado del lock de GPS. true si la pose es confiable. */
    fun isGpsReady(): Boolean {
        val km = KeyManager.getInstance()
        val satellites = km.getValue(
            KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount)
        ) ?: 0
        val signal = km.getValue(
            KeyTools.createKey(FlightControllerKey.KeyGPSSignalLevel)
        )
        Log.d(TAG, "GPS sats=$satellites, signal=$signal")
        return satellites >= 10
    }

    private companion object { const val TAG = "DronePoseProvider" }
}
