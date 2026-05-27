package com.example.djimichi

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.djimichi.board.BoardCalibrator
import com.example.djimichi.board.DronePoseProvider
import com.example.djimichi.databinding.ActivityMainBinding
import com.example.djimichi.dji.DjiSdkManager
import com.example.djimichi.flight.GpsFlightController
import com.example.djimichi.game.Cell
import com.example.djimichi.game.GameStatus
import com.example.djimichi.game.TicTacToe
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.camera.CameraStreamManager
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.sdk.keyvalue.value.common.ComponentIndexType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Flujo de la app:
 *   onCreate → registrar MSDK
 *   onProductConnected → arrancar video feed, mostrar panel de calibración
 *   Usuario ajusta slider de separación, espera lock GPS
 *   Usuario aprieta "CAPTURAR TABLERO" → guardamos pose home
 *   Auto-despegue a HOVER_ALT
 *   Mostramos overlay del michi sobre el video
 *   Juego: usuario toca casilla → dron juega su jugada → vuela al GPS de esa casilla
 *   Al terminar: el dron aterriza solo
 */
class MainActivity : AppCompatActivity(), DjiSdkManager.Listener {

    private lateinit var binding: ActivityMainBinding
    private val sdk = DjiSdkManager(this)
    private val game = TicTacToe()
    private val calibrator = BoardCalibrator()
    private val poseProvider = DronePoseProvider()
    private val flight = GpsFlightController()
    private val handler = Handler(Looper.getMainLooper())
    private var activeJob: Job? = null

    private val gpsPollRunnable = object : Runnable {
        override fun run() {
            updateGpsStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.spacingSlider.addOnChangeListener { _, value, _ ->
            binding.spacingLabel.text = "%.1f m".format(value)
        }
        binding.calibrateButton.setOnClickListener { onCalibratePressed() }
        binding.abortButton.setOnClickListener { abort() }
        binding.overlay.onCellTapped = ::handleHumanMove

        sdk.register(this)
    }

    // ---------- SDK callbacks ----------

    override fun onRegisterSuccess() = runOnUiThread {
        binding.statusText.text = "MSDK listo. Conecta el control remoto."
    }
    override fun onRegisterFailure(error: IDJIError) = runOnUiThread {
        binding.statusText.text = "Registro falló: ${error.description()}"
    }
    override fun onProductDisconnected() = runOnUiThread {
        binding.statusText.text = "Dron desconectado"
        handler.removeCallbacks(gpsPollRunnable)
    }
    override fun onProductConnected() = runOnUiThread {
        binding.statusText.text = "Mini 4 Pro conectado. Coloca el dron y configura."
        startVideoFeed()
        handler.post(gpsPollRunnable)
    }

    private fun startVideoFeed() {
        CameraStreamManager.getInstance().putCameraStreamSurface(
            ComponentIndexType.LEFT_OR_MAIN,
            binding.cameraSurface.holder.surface,
            binding.cameraSurface.width, binding.cameraSurface.height,
            ICameraStreamManager.ScaleType.CENTER_INSIDE
        )
    }

    // ---------- Calibración ----------

    private fun updateGpsStatus() {
        val km = KeyManager.getInstance()
        val sats = km.getValue(KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount)) ?: 0
        val ready = poseProvider.isGpsReady()
        binding.gpsStatus.text = "GPS: $sats satélites" + if (ready) " — LISTO" else " — esperando lock…"
        binding.gpsStatus.setTextColor(if (ready) 0xFF4CAF50.toInt() else 0xFFAAAAAA.toInt())
        binding.calibrateButton.isEnabled = ready
    }

    private fun onCalibratePressed() {
        val pose = poseProvider.readCurrentPose() ?: run {
            toast("No se pudo leer la pose; espera el lock GPS.")
            return
        }
        val spacing = binding.spacingSlider.value.toDouble()

        AlertDialog.Builder(this)
            .setTitle("Confirmar tablero")
            .setMessage("""
                Pose home capturada:
                  lat: ${"%.7f".format(pose.latitude)}
                  lon: ${"%.7f".format(pose.longitude)}
                  yaw: ${"%.1f".format(pose.yawDegrees)}°
                
                Separación: ${"%.1f".format(spacing)} m
                
                El dron despegará y empezará el juego.
            """.trimIndent())
            .setPositiveButton("DESPEGAR") { _, _ ->
                calibrator.calibrate(pose, spacing)
                startGame()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startGame() {
        binding.calibrationPanel.visibility = View.GONE
        binding.overlay.visibility = View.VISIBLE
        binding.abortButton.visibility = View.VISIBLE
        binding.statusText.text = "Despegando…"

        activeJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                takeoff()
                flight.enableVirtualStick()
            }
            binding.statusText.text = "Tu turno: toca una casilla"
            binding.overlay.enabled = true
        }
    }

    // ---------- Despegue / aterrizaje ----------

    private suspend fun takeoff() {
        val km = KeyManager.getInstance()
        // Llamada explícita usando los nombres de los parámetros del SDK de DJI v5
        km.performAction(
            KeyTools.createKey(FlightControllerKey.KeyStartTakeoff),
            object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<Void> {
                override fun onSuccess(param: Void?) {
                    Log.i(TAG, "Despegue iniciado con éxito")
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "Fallo en despegue: ${error.description()}")
                }
            }
        )
        // Esperar ~6s a que termine el auto-takeoff (sube a ~1.2m por defecto)
        kotlinx.coroutines.delay(6_000)
    }

    // ---------- Lógica de juego ----------

    private fun handleHumanMove(cell: Int) {
        if (!game.playHuman(cell)) return
        binding.overlay.cells = game.state()
        if (game.status() != GameStatus.ONGOING) { endGame(); return }

        binding.overlay.enabled = false
        binding.statusText.text = "Turno del dron…"

        activeJob = lifecycleScope.launch {
            val choice = game.playDrone()
            binding.overlay.cells = game.state()
            if (choice < 0) return@launch

            val targetCell = calibrator.cellGps(choice)
            Log.i(TAG, "Dron vuela a casilla $choice: " +
                    "(${targetCell.latitude}, ${targetCell.longitude})")

            withContext(Dispatchers.Default) {
                flight.flyToCell(targetCell, hoverAltM = HOVER_ALT)
            }

            binding.overlay.enabled = true
            if (game.status() != GameStatus.ONGOING) endGame()
            else binding.statusText.text = "Tu turno"
        }
    }

    private fun endGame() {
        val msg = when (game.status()) {
            GameStatus.HUMAN_WINS -> "¡Ganaste!"
            GameStatus.DRONE_WINS -> "El dron gana"
            GameStatus.DRAW       -> "Empate"
            else                  -> ""
        }
        binding.statusText.text = "$msg — aterrizando"
        binding.overlay.enabled = false
        lifecycleScope.launch { withContext(Dispatchers.Default) { flight.land() } }
    }

    private fun abort() {
        activeJob?.cancel()
        lifecycleScope.launch {
            withContext(Dispatchers.Default) { flight.land() }
            binding.statusText.text = "Abortado — aterrizando"
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        sdk.destroy()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val HOVER_ALT = 2.5    // metros sobre el punto de despegue
    }
}
