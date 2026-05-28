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
import com.example.djimichi.game.GameStatus
import com.example.djimichi.game.TicTacToe
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.camera.CameraStreamManager
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.sdk.keyvalue.value.common.ComponentIndexType

class MainActivity : AppCompatActivity(), DjiSdkManager.Listener {

    private lateinit var binding: ActivityMainBinding
    private val sdk          = DjiSdkManager(this)
    private val calibrator   = BoardCalibrator()
    private val poseProvider = DronePoseProvider()
    private val handler      = Handler(Looper.getMainLooper())

    private var game      = TicTacToe()
    private var gameState = GameState.IDLE

    // Celda GPS objetivo del dron (índice 0-8) durante DRONE_MOVING
    private var targetCell: Int? = null
    private var dwellCell:  Int? = null
    private var dwellCount        = 0

    // ── GPS poll (1 Hz, arranca al conectar el dron) ───────────────────────────

    private val gpsPollRunnable = object : Runnable {
        override fun run() {
            when (gameState) {
                GameState.IDLE,
                GameState.GAME_OVER  -> updateGpsStatus()
                GameState.WAITING_HUMAN -> updateCurrentDroneCell()
                GameState.DRONE_MOVING  -> droneMovingLoop()
                GameState.CALIBRATING   -> {}   // diálogo abierto, nada que hacer
            }
            handler.postDelayed(this, Constants.GPS_POLL_INTERVAL_MS)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "onCreate: UI inflada")

        binding.spacingSlider.addOnChangeListener { _, value, _ ->
            binding.spacingLabel.text = "%.1f m".format(value)
        }
        binding.calibrateButton.setOnClickListener { onCalibratePressed() }
        binding.abortButton.setOnClickListener { abort() }

        if (allPermissionsGranted()) {
            Log.d(TAG, "Permisos ya otorgados — iniciando SDK")
            sdk.register(this)
        } else {
            Log.d(TAG, "Solicitando permisos")
            requestAppPermissions()
        }
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

    private fun allPermissionsGranted(): Boolean = buildList {
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
    }.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

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
            toast("Permisos denegados: ${denied.joinToString()}. Puede que la app no funcione.")
        }
        Log.d(TAG, "Iniciando SDK post-permisos")
        sdk.register(this)
    }

    // ── SDK callbacks ──────────────────────────────────────────────────────────

    override fun onRegisterSuccess() = runOnUiThread {
        Log.i(TAG, "onRegisterSuccess")
        binding.statusText.text = "MSDK listo. Conectá el control remoto."
    }

    override fun onRegisterFailure(error: IDJIError) = runOnUiThread {
        Log.e(TAG, "onRegisterFailure: ${error.description()}")
        binding.statusText.text = "Registro falló: ${error.description()}"
    }

    override fun onProductConnected() = runOnUiThread {
        Log.i(TAG, "onProductConnected")
        binding.statusText.text = "Mini 4 Pro conectado. Coloca el dron y configura."
        startVideoFeed()
        handler.post(gpsPollRunnable)
    }

    override fun onProductDisconnected() = runOnUiThread {
        Log.i(TAG, "onProductDisconnected")
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
        gameState = GameState.CALIBRATING

        AlertDialog.Builder(this)
            .setTitle("Confirmar tablero")
            .setMessage("""
                Pose capturada:
                  lat: ${"%.7f".format(pose.latitude)}
                  lon: ${"%.7f".format(pose.longitude)}
                  yaw: ${"%.1f".format(pose.yawDegrees)}°

                Separación: ${"%.1f".format(spacing)} m
                Radio de celda: ${"%.2f".format(spacing * Constants.CELL_RADIUS_FACTOR)} m

                ¿Empezar la partida?
            """.trimIndent())
            .setPositiveButton("EMPEZAR") { _, _ ->
                calibrator.calibrate(pose, spacing)
                startGame()
            }
            .setNegativeButton("Cancelar") { _, _ ->
                gameState = GameState.IDLE
            }
            .show()
    }

    private fun startGame() {
        game      = TicTacToe()
        gameState = GameState.WAITING_HUMAN
        targetCell  = null
        dwellCell   = null
        dwellCount  = 0

        binding.overlay.cells = game.state()
        binding.overlay.clearDroneTarget()
        binding.overlay.setCurrentDroneCell(null)
        binding.overlay.inputEnabled = true
        binding.overlay.onCellTapped = ::handleHumanTap

        binding.calibrationPanel.visibility = View.GONE
        binding.overlay.visibility          = View.VISIBLE
        binding.abortButton.visibility      = View.VISIBLE
        binding.statusText.text = "Tu turno — tocá una celda"
    }

    // ── Turno humano (tap en overlay) ──────────────────────────────────────────

    private fun handleHumanTap(cellIndex: Int) {
        if (gameState != GameState.WAITING_HUMAN) return
        if (!game.playHuman(cellIndex)) return          // celda ocupada o estado inválido

        binding.overlay.inputEnabled = false
        binding.overlay.cells = game.state()

        if (game.status() != GameStatus.ONGOING) { endGame(); return }

        // IA calcula su jugada de inmediato
        val aiMove = game.playDrone()
        binding.overlay.cells = game.state()

        if (game.status() != GameStatus.ONGOING) { endGame(); return }

        // Transición a DRONE_MOVING: piloto debe volar a aiMove
        targetCell = aiMove
        dwellCell  = null
        dwellCount = 0
        gameState  = GameState.DRONE_MOVING
        binding.overlay.setDroneTarget(aiMove)
        binding.statusText.text = "Volá a la celda amarilla ↑"
        Log.i(TAG, "IA eligió casilla $aiMove — esperando dwell GPS")
    }

    // ── Turno del dron: GPS dwell 1 Hz ────────────────────────────────────────

    private fun droneMovingLoop() {
        val pose = poseProvider.readCurrentPose() ?: run {
            binding.statusText.text = "Sin GPS — esperando señal…"
            binding.overlay.setCurrentDroneCell(null)
            return
        }

        val cell   = calibrator.nearestCell(pose.latitude, pose.longitude)
        val target = targetCell ?: return
        binding.overlay.setCurrentDroneCell(cell)

        if (cell == target) {
            // Dron está en la celda correcta — acumular dwell
            if (dwellCell != cell) { dwellCell = cell; dwellCount = 0 }
            dwellCount++
            binding.overlay.setDwellProgress(dwellCount, Constants.DWELL_SECONDS)

            val remaining = Constants.DWELL_SECONDS - dwellCount
            binding.statusText.text = if (remaining > 0)
                "Mantené posición… ${remaining}s"
            else
                "¡Confirmado!"

            if (dwellCount >= Constants.DWELL_SECONDS) {
                confirmDroneMove()
            }
        } else {
            // Dron se movió o aún no llegó — reset dwell
            if (dwellCount > 0) {
                dwellCount = 0
                dwellCell  = null
                binding.overlay.setDwellProgress(0, Constants.DWELL_SECONDS)
            }
            binding.statusText.text = "Volá a la celda amarilla"
        }
    }

    private fun confirmDroneMove() {
        Log.i(TAG, "Jugada IA confirmada por GPS en celda $targetCell")
        binding.overlay.clearDroneTarget()
        targetCell = null
        dwellCell  = null
        dwellCount = 0

        if (game.status() != GameStatus.ONGOING) {
            endGame()
        } else {
            gameState = GameState.WAITING_HUMAN
            binding.overlay.inputEnabled = true
            binding.statusText.text = "Tu turno — tocá una celda"
        }
    }

    /** Muestra la posición del dron (borde verde) durante el turno humano. */
    private fun updateCurrentDroneCell() {
        val pose = poseProvider.readCurrentPose() ?: return
        if (calibrator.isCalibrated) {
            binding.overlay.setCurrentDroneCell(
                calibrator.nearestCell(pose.latitude, pose.longitude)
            )
        }
    }

    // ── Fin de partida / abort ─────────────────────────────────────────────────

    private fun endGame() {
        gameState = GameState.GAME_OVER
        val msg = when (game.status()) {
            GameStatus.HUMAN_WINS -> "¡Ganaste!"
            GameStatus.DRONE_WINS -> "¡Ganó el dron!"
            GameStatus.DRAW       -> "Empate"
            else                  -> ""
        }
        binding.statusText.text = msg
        binding.overlay.inputEnabled = false
        binding.overlay.clearDroneTarget()
        binding.abortButton.visibility = View.GONE
        Log.i(TAG, "Partida terminada: ${game.status()}")
    }

    private fun abort() {
        gameState  = GameState.IDLE
        targetCell = null
        dwellCell  = null
        dwellCount = 0
        game       = TicTacToe()
        calibrator.reset()

        binding.overlay.clearDroneTarget()
        binding.overlay.setCurrentDroneCell(null)
        binding.overlay.cells        = game.state()
        binding.overlay.inputEnabled = false
        binding.overlay.onCellTapped = null
        binding.overlay.visibility          = View.GONE
        binding.calibrationPanel.visibility = View.VISIBLE
        binding.abortButton.visibility      = View.GONE
        binding.statusText.text = "Abortado — recalibrá el tablero"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val TAG               = "MainActivity"
        const val PERM_REQUEST_CODE = 42
    }
}
