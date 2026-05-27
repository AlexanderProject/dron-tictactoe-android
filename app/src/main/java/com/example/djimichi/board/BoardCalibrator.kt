package com.example.djimichi.board

import kotlin.math.cos
import kotlin.math.sin

/**
 * Calibración del tablero del michi en coordenadas GPS.
 *
 * Cuando el usuario aprieta "Capturar tablero":
 *  - El dron está parado en el suelo donde queremos el centro (casilla 4).
 *  - La nariz del dron apunta hacia donde queremos que sea "adelante" (+X local).
 *  - Capturamos (lat, lon, yaw) y los guardamos como `home`.
 *
 * Para calcular las casillas:
 *  - Casilla i tiene offset local (dx, dy) en metros desde el centro.
 *  - dx = "adelante del dron"  (hacia donde apunta su nariz)
 *  - dy = "derecha del dron"
 *  - Rotamos (dx, dy) por home_yaw para obtener offset en NORTE/ESTE.
 *  - Aplicamos el offset al home_lat/lon para obtener el GPS objetivo.
 *
 * Convención del michi (vista desde arriba, mirando al frente del dron):
 *
 *       6 | 7 | 8     <- adelante (lejos)
 *      ---+---+---
 *       3 | 4 | 5     <- el dron está aquí en la fila central
 *      ---+---+---
 *       0 | 1 | 2     <- atrás (cerca del piloto)
 *
 * Casillas hacia adelante: índices 6,7,8 (dx > 0)
 * Casillas hacia atrás:    índices 0,1,2 (dx < 0)
 * Casillas a la derecha:   índices 2,5,8 (dy > 0)
 * Casillas a la izquierda: índices 0,3,6 (dy < 0)
 */
data class HomePose(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double,
    val yawDegrees: Double   // 0 = norte, 90 = este (estándar de brújula)
)

data class CellGps(
    val index: Int,
    val latitude: Double,
    val longitude: Double
)

class BoardCalibrator {

    private var home: HomePose? = null
    private var spacingMeters: Double = 1.5

    val isCalibrated: Boolean get() = home != null

    /** Captura el estado actual del dron como pose home. */
    fun calibrate(pose: HomePose, spacing: Double) {
        require(spacing in 0.5..10.0) { "Separación fuera de rango: $spacing" }
        home = pose
        spacingMeters = spacing
    }

    fun reset() { home = null }

    /** Devuelve las coordenadas GPS de las 9 casillas. */
    fun cells(): List<CellGps> {
        val h = home ?: error("Tablero no calibrado: llama a calibrate() primero")
        return (0..8).map { cellGps(it, h, spacingMeters) }
    }

    fun cellGps(index: Int): CellGps {
        require(index in 0..8)
        val h = home ?: error("Tablero no calibrado")
        return cellGps(index, h, spacingMeters)
    }

    fun homePose(): HomePose = home ?: error("Tablero no calibrado")
    fun spacing(): Double = spacingMeters

    // --- Geometría ---

    private companion object {
        /** Radio medio de la Tierra usado para conversión local m → grados. */
        const val EARTH_RADIUS_M = 6_378_137.0

        /**
         * Convierte (dx, dy) en metros locales (dx=norte, dy=este) a delta de
         * lat/lon en grados. Aproximación de tierra plana, válida para
         * distancias < ~1 km (más que suficiente para un tablero de michi).
         */
        fun localMetersToLatLon(
            originLat: Double, originLon: Double,
            northM: Double, eastM: Double
        ): Pair<Double, Double> {
            val dLat = Math.toDegrees(northM / EARTH_RADIUS_M)
            val dLon = Math.toDegrees(eastM / (EARTH_RADIUS_M * cos(Math.toRadians(originLat))))
            return originLat + dLat to originLon + dLon
        }

        /** Offset (dx adelante, dy derecha) en metros respecto al frame del dron. */
        fun bodyOffset(index: Int, spacing: Double): Pair<Double, Double> {
            // fila 0 = atrás (dx negativo), fila 2 = adelante (dx positivo)
            val col = index % 3 - 1       // -1, 0, +1  (izquierda, centro, derecha)
            val row = index / 3 - 1       // -1, 0, +1
            val dxForward = row * spacing     // +1 = adelante, -1 = atrás
            val dyRight = col * spacing       // +1 = derecha, -1 = izquierda
            return dxForward to dyRight
        }

        fun cellGps(index: Int, home: HomePose, spacing: Double): CellGps {
            val (dForward, dRight) = bodyOffset(index, spacing)
            // Rotamos del frame del dron al frame NORTE/ESTE
            // yaw: ángulo de la nariz desde el norte, en sentido horario
            val yawRad = Math.toRadians(home.yawDegrees)
            val north = dForward * cos(yawRad) - dRight * sin(yawRad)
            val east  = dForward * sin(yawRad) + dRight * cos(yawRad)
            val (lat, lon) = localMetersToLatLon(home.latitude, home.longitude, north, east)
            return CellGps(index, lat, lon)
        }
    }
}
