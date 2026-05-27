package com.example.djimichi.flight

import android.util.Log
import com.example.djimichi.board.CellGps
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.HorizontalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.IVirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickFlightControlParam
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.*

/**
 * Controlador de vuelo a coordenadas GPS.
 *
 * En cada iteración:
 *   1. Lee la posición GPS actual del dron del SDK.
 *   2. Calcula distancia (norte, este) al objetivo en metros usando haversine.
 *   3. Convierte ese error a velocidades del cuerpo del dron (rotando por yaw).
 *   4. Aplica control PI y envía por Virtual Stick.
 *
 * Las ganancias KP=1.5, KI=0.6 son las validadas en simulator/test_headless.py.
 *
 * Loop a 10 Hz; tolerancia de llegada 0.5 m (más alta que el ArUco porque el
 * GPS del Mini 4 Pro tiene ruido ~10-15 cm en buenas condiciones).
 */
class GpsFlightController {

    private val vs: IVirtualStickManager = VirtualStickManager.getInstance()
    private val km = KeyManager.getInstance()

    private val kp = 1.5
    private val ki = 0.6
    private val maxVel = 1.0      // m/s
    private val maxInt = 0.5
    private val tolerance = 0.5   // 50 cm — GPS no llega más fino
    private val hoverTimeMs = 2_000L

    suspend fun enableVirtualStick() {
        vs.setVirtualStickAdvancedModeEnabled(true)
        vs.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = Log.i(TAG, "Virtual stick habilitado")
            override fun onFailure(error: IDJIError) =
                Log.e(TAG, "VS error: ${error.description()}")
        })
        delay(500)
    }

    suspend fun flyToCell(cell: CellGps, hoverAltM: Double) {
        var integralN = 0.0
        var integralE = 0.0
        var integralZ = 0.0
        var hoverStartMs: Long? = null
        var lastTimeMs = System.currentTimeMillis()

        while (coroutineContext.isActive) {
            val now = System.currentTimeMillis()
            val dt = (now - lastTimeMs) / 1000.0
            lastTimeMs = now

            // Lee pose GPS actual
            val location = km.getValue(
                KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D)
            )
            val heading = km.getValue(
                KeyTools.createKey(FlightControllerKey.KeyCompassHeading)
            )

            if (location == null || heading == null) {
                Log.w(TAG, "Sin datos GPS; frenando")
                sendVelocity(0.0, 0.0, 0.0, 0.0)
                delay(100)
                continue
            }

            // Error en metros (norte, este, altura)
            val (errN, errE) = latLonToMeters(
                location.latitude, location.longitude,
                cell.latitude, cell.longitude
            )
            val errZ = hoverAltM - location.altitude

            val dist = hypot(errN, errE)
            if (dist < tolerance && abs(errZ) < 0.3) {
                if (hoverStartMs == null) hoverStartMs = now
                else if (now - hoverStartMs!! > hoverTimeMs) {
                    sendVelocity(0.0, 0.0, 0.0, 0.0)
                    Log.i(TAG, "Llegó a casilla ${cell.index}")
                    return
                }
            } else {
                hoverStartMs = null
            }

            // Acumulador integral con clamp
            integralN += errN * dt
            integralE += errE * dt
            integralZ += errZ * dt
            val intNorm = hypot(integralN, integralE)
            if (intNorm > maxInt) {
                integralN *= maxInt / intNorm
                integralE *= maxInt / intNorm
            }
            integralZ = integralZ.coerceIn(-maxInt, maxInt)

            // PI en frame mundo (norte/este)
            var vN = kp * errN + ki * integralN
            var vE = kp * errE + ki * integralE
            val vNorm = hypot(vN, vE)
            if (vNorm > maxVel) {
                vN *= maxVel / vNorm
                vE *= maxVel / vNorm
            }
            val vZ = (kp * errZ + ki * integralZ).coerceIn(-maxVel, maxVel)

            // Rotamos (norte, este) → (adelante, derecha) del dron
            // heading: ángulo de la nariz desde el norte, sentido horario
            val yawRad = Math.toRadians(heading)
            val vForward = vN * cos(yawRad) + vE * sin(yawRad)
            val vRight   = -vN * sin(yawRad) + vE * cos(yawRad)

            sendVelocity(vForward, vRight, vZ, 0.0)
            delay(100)   // 10 Hz
        }
    }

    private fun sendVelocity(vx: Double, vy: Double, vz: Double, yawRate: Double) {
        val param = VirtualStickFlightControlParam().apply {
            verticalControlMode = VerticalControlMode.VELOCITY
            horizontalControlMode = HorizontalControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            coordinateSystem = FlightCoordinateSystem.BODY
            pitch = vx       // adelante (+x body)
            roll = vy        // derecha (+y body)
            verticalThrottle = vz
            yaw = yawRate
        }
        vs.sendVirtualStickAdvancedParam(param)
    }

    suspend fun land() {
        sendVelocity(0.0, 0.0, 0.0, 0.0)
        vs.disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = Log.i(TAG, "VS deshabilitado")
            override fun onFailure(error: IDJIError) =
                Log.e(TAG, "Disable VS: ${error.description()}")
        })
        // Iniciar auto-landing
        km.performAction(
            KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding),
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = Log.i(TAG, "Auto-land iniciado")
                override fun onFailure(error: IDJIError) =
                    Log.e(TAG, "Land: ${error.description()}")
            }
        )
    }

    private companion object {
        const val TAG = "GpsFlightController"
        const val EARTH_RADIUS_M = 6_378_137.0

        /** Distancia local (norte, este) en metros entre dos coordenadas GPS. */
        fun latLonToMeters(
            lat1: Double, lon1: Double, lat2: Double, lon2: Double
        ): Pair<Double, Double> {
            val dN = Math.toRadians(lat2 - lat1) * EARTH_RADIUS_M
            val dE = Math.toRadians(lon2 - lon1) * EARTH_RADIUS_M *
                     cos(Math.toRadians((lat1 + lat2) / 2.0))
            return dN to dE
        }
    }
}
