package com.example.djimichi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.djimichi.board.BoardCalibrator
import com.example.djimichi.board.DronePoseProvider
import com.example.djimichi.databinding.ActivityMainBinding
import com.example.djimichi.dji.DjiSdkManager
import com.example.djimichi.game.Cell
import com.example.djimichi.game.GameStatus
import com.example.djimichi.game.TicTacToe
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.camera.CameraStreamManager
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.sdk.keyvalue.value.common.ComponentIndexType

/**
 * Flujo de la app (Mini 4 Pro — telemetría read-only, sin Virtual Stick):
 *
 *   onCreate → pedir permisos → registrar MSDK
 *   onProductConnected → video feed + GPS polling (1 Hz)
 *   Usuario coloca el dron en el centro, ajusta spacing, espera GPS lock
 *   "CAPTURAR TABLERO" → BoardCalibrator calcula las 9 celdas GPS
 *   PARTIDA:
 *     • Turno humano: volar a casilla deseada → dwell 3 s → jugada confirmada
 *     • IA calcula su casilla (minimax) → overlay muestra borde amarillo
 *     • Jugador vuela a esa casilla → dwell 3 s → jugada IA confirmada
 *     • Repetir hasta win / draw
 */
class MainActivity : AppCompatActivity(), DjiSdkManager.Listener {

    private lateinit var binding: ActivityMainBinding
    private val sdk        = DjiSdkManager(this)
    private val calibrator = BoardCalibrator()
    private val poseProvider = DronePoseProvider()
    private val handler    = Handler(Looper.getMainLooper())

    // Se recrea en cada partida para resetear el tablero
    private var game = TicTacToe()

    // Estado del juego
    private var gameActive  = false
    private var humanTurn   = true
    private var dwellCell: Int? = null
    private var dwellCount  = 0
    private var aiTargetCell: Int? = null

    private val gpsPollRunnable = object : Runnable {
        override fun run() {
            if (gameActive) gameLoop() else updateGpsStatus()
            handler.postDelayed(this, 1000)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.spacingSlider.addOnChangeListener { _, value, _ ->
            binding.spacingLabel.text = "%.1f m".format(value)
        }
        binding.calibrateButton.setOnClickListener { onCalibratePressed() }
        binding.abortButton.setOnClickListener { abort() }

        requestAppPermissions()
        sdk.register(this)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sdk.destroy()
        super.onDestroy()
    }

    // ── Permisos ───────────────────────────────────────────────────────────────

    private fun requestAppPermissions() {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERM_REQUEST_CODE) return
        val denied = permissions.zip(grantResults.toList())
            .filter { (_, r) -> r != PackageManager.PERMISSION_GRANTED }
            .map { (p, _) -> p.substringAfterLast('.') }
        if (denied.isNotEmpty()) {
            Log.w(TAG, "Permisos denegados: $denied")
            toast("Permisos denegados: ${denied.joinToString()}. La app puede no funcionar.")
        }
    }

    // ── SDK callbacks ──────────────────────────────────────────────────────────

    override fun onRegisterSuccess() = runOnUiThread {
        binding.statusText.text = "MSDK listo. Conecta el control remoto."
    }

    override fun onRegisterFailure(error: IDJIError) = runOnUiThread {
        binding.statusText.text = "Registro falló: ${error.description()}"
    }

    override fun onProductConnected() = runOnUiThread {
        binding.statusText.text = "Mini 4 Pro conectado. Coloca el dron y configura."
        startVideoFeed()
        handler.post(gpsPollRunnable)
    }

    override fun onProductDisconnected() = runOnUiThread {
        binding.statusText.text = "Dron desconectado"
        handler.removeCallbacks(gpsPollRunnable)
    }

    private fun startVideoFeed() {
        CameraStreamManager.getInstance().putCameraStreamSurface(
            ComponentIndexType.LEFT_OR_MAIN,
            binding.cameraSurface.holder.surface,
            binding.cameraSurface.width, binding.cameraSurface.height,
            ICameraStreamManager.ScaleType.CENTER_INSIDE
        )
    }

    // ── Calibración ────────────────────────────────────────────────────────────

    private fun updateGpsStatus() {
        val sats  = KeyManager.getInstance()
            .getValue(KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount)) ?: 0
        val ready = poseProvider.isGpsReady()
        binding.gpsStatus.text = "GPS: $sats satélites" + if (ready) " — LISTO" else " — esperando lock…"
        binding.gpsStatus.setTextColor(if (ready) 0xFF4CAF50.toInt() else 0xFFAAAAAA.toInt())
        binding.calibrateButton.isEnabled = ready
    }

    private fun onCalibratePressed() {
        val pose = poseProvider.readCurrentPose() ?: run {
            toast("No se pudo leer la pose; esperá el lock GPS.")
            return
        }
        val spacing = binding.spacingSlider.value.toDouble()

        AlertDialog.Builder(this)
            .setTitle("Confirmar tablero")
            .setMessage(
                """
                Pose capturada:
                  lat: ${"%.7f".format(pose.latitude)}
                  lon: ${"%.7f".format(pose.longitude)}
                  yaw: ${"%.1f".format(pose.yawDegrees)}°

                Separación: ${"%.1f".format(spacing)} m
                Radio de celda: ${"%.2f".format(spacing * 0.4)} m

                ¿Empezar la partida?
                """.trimIndent()
            )
            .setPositiveButton("EMPEZAR") { _, _ ->
                calibrator.calibrate(pose, spacing)
                startGame()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startGame() {
        game       = TicTacToe()
        gameActive = true
        humanTurn  = true
        dwellCell  = null
        dwellCount = 0
        aiTargetCell = null

        binding.overlay.cells       = game.state()
        binding.overlay.currentCell = null
        binding.overlay.targetCell  = null
        binding.calibrationPanel.visibility = View.GONE
        binding.overlay.visibility          = View.VISIBLE
        binding.abortButton.visibility      = View.VISIBLE
        binding.statusText.text = "Tu turno: volá a una casilla libre"
    }

    // ── Loop de juego (1 Hz vía handler) ──────────────────────────────────────

    private fun gameLoop() {
        val pose = poseProvider.readCurrentPose() ?: run {
            binding.statusText.text = "Sin GPS — esperando señal…"
            binding.overlay.currentCell = null
            return
        }

        val cell = calibrator.nearestCell(pose.latitude, pose.longitude)
        binding.overlay.currentCell = cell

        if (humanTurn) handleHumanTurn(cell) else handleAiConfirmTurn(cell)
    }

    /**
     * Turno del humano: el jugador vuela a la casilla que quiere jugar.
     * Al mantener [DWELL_SECONDS] segundos en una casilla vacía, se confirma.
     */
    private fun handleHumanTurn(cell: Int?) {
        if (cell == null) {
            resetDwell()
            binding.statusText.text = "Volá a una casilla libre"
            return
        }
        if (game.state()[cell] != Cell.EMPTY) {
            resetDwell()
            binding.statusText.text = "Casilla ocupada — elegí otra"
            return
        }

        accumulateDwell(cell)

        val remaining = DWELL_SECONDS - dwellCount
        if (remaining > 0) {
            binding.statusText.text = "Mantené posición… ${remaining}s"
            return
        }

        // ¡Jugada humana confirmada!
        game.playHuman(cell)
        binding.overlay.cells = game.state()
        resetDwell()
        if (game.status() != GameStatus.ONGOING) { endGame(); return }

        // IA responde de inmediato
        val aiMove = game.playDrone()
        binding.overlay.cells = game.state()
        if (game.status() != GameStatus.ONGOING) { endGame(); return }

        aiTargetCell           = aiMove
        binding.overlay.targetCell = aiMove
        humanTurn              = false
        binding.statusText.text = "IA jugó. Volá a la celda amarilla"
    }

    /**
     * El jugador vuela a la casilla que eligió la IA para "marcarla" físicamente.
     * Al mantener [DWELL_SECONDS] segundos sobre esa casilla, el turno pasa al humano.
     */
    private fun handleAiConfirmTurn(cell: Int?) {
        val target = aiTargetCell ?: return

        if (cell != target) {
            resetDwell()
            binding.statusText.text = "Volá a la celda amarilla (IA eligió ${target + 1})"
            return
        }

        accumulateDwell(cell)

        val remaining = DWELL_SECONDS - dwellCount
        if (remaining > 0) {
            binding.statusText.text = "Marcando jugada IA… ${remaining}s"
            return
        }

        // Celda IA marcada físicamente
        binding.overlay.targetCell = null
        aiTargetCell               = null
        resetDwell()
        humanTurn = true
        binding.statusText.text = "Tu turno: volá a una casilla libre"
    }

    private fun accumulateDwell(cell: Int) {
        if (dwellCell != cell) { dwellCell = cell; dwellCount = 1 }
        else dwellCount++
    }

    private fun resetDwell() { dwellCell = null; dwellCount = 0 }

    // ── Fin de partida / abort ─────────────────────────────────────────────────

    private fun endGame() {
        gameActive = false
        val msg = when (game.status()) {
            GameStatus.HUMAN_WINS -> "¡Ganaste!"
            GameStatus.DRONE_WINS -> "La IA gana"
            GameStatus.DRAW       -> "Empate"
            else                  -> ""
        }
        binding.statusText.text = msg
        binding.abortButton.visibility = View.GONE
        Log.i(TAG, "Partida terminada: ${game.status()}")
    }

    private fun abort() {
        gameActive = false
        resetDwell()
        calibrator.reset()
        game = TicTacToe()

        binding.overlay.currentCell = null
        binding.overlay.targetCell  = null
        binding.overlay.cells       = game.state()
        binding.overlay.visibility          = View.GONE
        binding.calibrationPanel.visibility = View.VISIBLE
        binding.abortButton.visibility      = View.GONE
        binding.statusText.text = "Abortado — recalibrá el tablero"
    }

    // ── Utilidades ─────────────────────────────────────────────────────────────

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val TAG              = "MainActivity"
        const val DWELL_SECONDS   = 3      // segundos estable en celda para confirmar jugada
        const val PERM_REQUEST_CODE = 42
    }
}
