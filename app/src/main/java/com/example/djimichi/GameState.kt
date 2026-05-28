package com.example.djimichi

enum class GameState {
    IDLE,            // sin partida activa
    CALIBRATING,     // capturando tablero GPS (diálogo abierto)
    WAITING_HUMAN,   // turno humano — espera tap en pantalla
    DRONE_MOVING,    // piloto vuela al destino — esperando dwell GPS
    GAME_OVER        // partida terminada
}
