package com.example.djimichi.board

import kotlin.math.cos
import kotlin.math.hypot
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

    /**
     * Devuelve el índice de la casilla (0-8) más cercana a [lat]/[lon] si
     * está dentro del radio de tolerancia (spacing * 0.4), o null si el
     * dron está entre casillas o fuera del tablero.
     *
     * Radio de tolerancia elegido empíricamente: el 40 % del spacing deja
     * un margen "neutral" entre casillas para evitar activaciones dobles.
     * Con spacing = 1.5 m el radio es 0.6 m, holgado para el GPS del Mini 4 Pro
     * (~10–15 cm de ruido en campo abierto).
     */
    fun nearestCell(lat: Double, lon: Double): Int? {
        val h = home ?: return null
        val radiusM = spacingMeters * 0.4
        var best: Int? = null
        var bestDist = Double.MAX_VALUE
        for (i in 0..8) {
            val cell = cellGps(i, h, spacingMeters)
            val (dN, dE) = latLonToMeters(lat, lon, cell.latitude, cell.longitude)
            val dist = hypot(dN, dE)
            if (dist < radiusM && dist < bestDist) {
                best = i
                bestDist = dist
            }
        }
        return best
    }

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

        /** Distancia en metros (norte, este) entre dos coordenadas GPS. */
        fun latLonToMeters(
            lat1: Double, lon1: Double,
            lat2: Double, lon2: Double
        ): Pair<Double, Double> {
            val dN = Math.toRadians(lat2 - lat1) * EARTH_RADIUS_M
            val dE = Math.toRadians(lon2 - lon1) * EARTH_RADIUS_M *
                     cos(Math.toRadians((lat1 + lat2) / 2.0))
            return dN to dE
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
