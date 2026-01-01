package com.example.sensortomatlab

import android.graphics.*
import android.hardware.*
import android.os.*
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException
import java.net.*
import java.util.*
import java.util.concurrent.*
import kotlin.math.*

class MainActivity : AppCompatActivity(), SensorEventListener {

    // =================== USER PARAMS ===================
    private val PX_PER_M = 20.0f       // calibration px/m
    private val STEP_LEN_M = 0.65f     // longueur de pas moyenne
    private val STEP_LEN_PX = PX_PER_M * STEP_LEN_M

    // Si yawRel part toujours dans le même sens (gauche/droite), mets true
    private val INVERT_YAW = true

    // =================== UI ===================
    private lateinit var imageView: ImageView
    private lateinit var bmpPlan: Bitmap              // base (modifiable pour doors)
    private lateinit var bmpBase: Bitmap              // base immuable pour affichage
    private lateinit var bmpOverlay: Bitmap           // overlay réutilisable (anti-OOM)
    private lateinit var overlayCanvas: Canvas
    private val paintClear = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val paintStart = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN }
    private val paintEnd = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.MAGENTA }
    private val paintFull = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; strokeWidth = 6f }
    private val paintProg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; strokeWidth = 10f }
    private val paintCompassBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 120
        style = Paint.Style.FILL
    }
    private val paintCompassCircle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 220
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintCompassYaw = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; strokeWidth = 4f }
    private val paintCompassSeg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN; strokeWidth = 4f }
    private val paintCompassText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f }

    private lateinit var vibrator: Vibrator

    // =================== MAP / PATH ===================
    private var startPoint: PointF? = null
    private var endPoint: PointF? = null
    @Volatile private var pathResult: List<PointF> = emptyList()

    private lateinit var walkable: BooleanArray
    @Volatile private var mapReady = false
    private val doorMask = HashSet<Int>()

    // distance cumulée sur le path
    @Volatile private var cumDistPx: FloatArray = floatArrayOf()
    @Volatile private var totalDistPx = 0f
    @Volatile private var totalSteps = 0

    // =================== SENSORS ===================
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var rotationVector: Sensor? = null
    private var usingGameRV = false

    // step detection (simple)
    private var lowPass = 11f
    private var lastDyn = 0f
    private var lastPeakTime = 0L
    private val minStepDelay = 350

    private var rawStepCount = 0
    private var sentStepCount = 0
    private var yawOffsetLockedToPath = false

    // =================== NAV STATE ===================
    enum class NavState { IDLE, READY, RUNNING, FINISHED }
    @Volatile private var navState = NavState.IDLE
    @Volatile private var navigationActive = false

    // position estimée sur la carte (côté Android)
    @Volatile private var distNowPx = 0f
    @Volatile private var ratioNow = 0f

    // =================== YAW FILTER ===================
    private val rv = FloatArray(5)
    private val R = FloatArray(9)
    private val Rr = FloatArray(9)
    private val ori = FloatArray(3) // [azimuth, pitch, roll]

    private var yawFiltered = 0f
    private var yawOffset = 0f
    private var yawInit = false
    private var lastYawTime = 0L

    private var yawSinF = 0f
    private var yawCosF = 1f
    private val yawAlpha = 0.08f   // un peu plus lissant que 0.06

    private val maxYawRate = 140f // deg/s (plus strict = moins de saut)
    private val SHOW_COMPASS = true
    private val compassRadiusPx = 40f
    private val forwardTooVerticalThreshold = 0.97f // gardé, mais on va aussi rejeter sur pitch/roll

    // =================== YAW <-> MAP CALIB ===================
    private var heading0Abs = 0f
    private var heading0Ready = false

    private fun absToRelMap(absDeg: Float): Float {
        return if (heading0Ready) normalizeAngle(normalizeAngle(absDeg) - heading0Abs)
        else normalizeAngle(absDeg)
    }

    // =================== TURN / LOCK ===================
    private val TURN_END = 15f
    private val TURN_START = 25f
    private val deadZone = 8f
    private val correctionThreshold = 18f

    private val lookAheadPx = 15f // a tester
    private val minStepsBeforeFirstLock = 3
    private var lastUiTurnMsg = ""
    private var lastUiTurnTime = 0L

    // Anti “validation virage toute seule” : on exige plusieurs ticks dans la zone
    private var lockOkCount = 0
    private val lockOkNeeded = 6 // 6 ticks * 50ms ≈ 300ms (avec yawTick), très efficace

    data class TurnEvent(
        val atDistPx: Float,
        val dir: String,
        val newHeadingAbs: Float
    )

    @Volatile private var turnEvents: List<TurnEvent> = emptyList()
    private var nextTurnIdx = 0
    private var pendingStepsWhileLocked = 0

    private var turnLockActive = false
    private var turnLockDir = ""
    private var turnLockTargetAbs = 0f

    // =================== UDP ===================
    private val matlabIP = "192.168.43.18"
    private val portSend = 30000
    private val portRecv = 31000
    private var yawSmoothPrevDeg: Float? = null
    private var yawAbsContDeg = 0f      // yaw "déroulé" continu
    private var yawOffsetContDeg = 0f   // offset en continu

    private var socketSend: DatagramSocket? = null
    private var socketRecv: DatagramSocket? = null
    private var recvThread: Thread? = null
    @Volatile private var receiverRunning = false
    private var lockErrAtStartAbs = 999f
    private val lockMinErrToRequireTurn = 22f // degrés


    // Un seul thread pour tous les envois UDP (anti-threads en rafale)
    private val udpExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "udp-sender").apply { isDaemon = true }
    }

    // =================== PATH EXEC ===================
    private val pathExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "path-worker").apply { isDaemon = true }
    }

    // =================== DEBUG ===================
    private val TAG = "NAVAPP"
    private val DBG = true
    private val DBG_PERIOD_MS = 300L
    private var lastDbgTime = 0L

    // Timer: envoi yaw en continu même sans marcher (évite timeout Matlab)
    private val yawTickMs = 50L
    private val handler = Handler(Looper.getMainLooper())
    private val yawTick = object : Runnable {
        override fun run() {
            try {
                if (navigationActive && navState == NavState.RUNNING) {
                    processNavigationLogic(trigger = "TICK")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "yawTick error", t)
            } finally {
                handler.postDelayed(this, yawTickMs)
            }
        }
    }
    private fun showTurnHint(msg: String) {
        val now = System.currentTimeMillis()
        if (msg == lastUiTurnMsg && now - lastUiTurnTime < 600) return
        lastUiTurnMsg = msg
        lastUiTurnTime = now
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    // =================== LIFECYCLE ===================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        setContentView(imageView)

        // Charge plan (modifiable pour doors)
        bmpPlan = BitmapFactory.decodeResource(
            resources,
            com.example.sensortomatlab.R.drawable.rdc_galilee
        ).copy(Bitmap.Config.ARGB_8888, true)

        // Base immuable pour affichage (après doors)
        bmpBase = bmpPlan.copy(Bitmap.Config.ARGB_8888, false)

        // Overlay réutilisable
        bmpOverlay = Bitmap.createBitmap(bmpPlan.width, bmpPlan.height, Bitmap.Config.ARGB_8888)
        overlayCanvas = Canvas(bmpOverlay)

        // Buffer walkable
        walkable = BooleanArray(bmpPlan.width * bmpPlan.height)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // IMPORTANT : on préfère ROTATION_VECTOR (avec mag) si dispo -> yaw moins “fantôme”
        val rot = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val game = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        rotationVector = rot ?: game
        usingGameRV = (rotationVector?.type == Sensor.TYPE_GAME_ROTATION_VECTOR)
        Log.i(TAG, "RotationVector=${rotationVector?.name} usingGameRV=$usingGameRV")

        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        try {
            socketSend = DatagramSocket().apply { soTimeout = 0 }
            socketRecv = DatagramSocket(portRecv).apply { soTimeout = 1000 } // timeout pour sortir proprement
        } catch (e: Exception) {
            Log.e(TAG, "UDP socket error", e)
        }

        imageView.post {
            Thread {
                try {
                    detectAndDilateDoors()
                    precomputeWalkable()

                    // Recrée bmpBase après modifications doors
                    bmpBase = bmpPlan.copy(Bitmap.Config.ARGB_8888, false)

                    mapReady = true
                    runOnUiThread {
                        draw() // premier render
                        Toast.makeText(this, "Carte prête", Toast.LENGTH_SHORT).show()
                    }
                    Log.i(TAG, "Map ready: ${bmpPlan.width}x${bmpPlan.height}")
                } catch (t: Throwable) {
                    Log.e(TAG, "Map init error", t)
                    runOnUiThread { Toast.makeText(this, "Erreur init carte", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }

        imageView.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                try {
                    handleClick(e)
                } catch (t: Throwable) {
                    Log.e(TAG, "handleClick crash prevented", t)
                }
                v.performClick()
            }
            true
        }

        startReceiver()
        handler.postDelayed(yawTick, yawTickMs)
    }
    private fun getYawSmoothAbsDegOrNull(): Float? {
        if (rv[0] == 0f && rv[1] == 0f && rv[2] == 0f) return null

        SensorManager.getRotationMatrixFromVector(R, rv)

        @Suppress("DEPRECATION")
        val rot = windowManager.defaultDisplay.rotation
        val (axisX, axisY) = when (rot) {
            Surface.ROTATION_0 -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
            Surface.ROTATION_90 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
            Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
            Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
            else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
        }
        SensorManager.remapCoordinateSystem(R, axisX, axisY, Rr)
        SensorManager.getOrientation(Rr, ori)

        val yawRawDeg0 = Math.toDegrees(ori[0].toDouble()).toFloat()
        val pitchDeg = Math.toDegrees(ori[1].toDouble()).toFloat()

        // même rejet que toi
        if (abs(pitchDeg) > 70f) return null

        var yawRawDeg = normalizeAngle(yawRawDeg0)
        if (INVERT_YAW) yawRawDeg = -yawRawDeg

        // lissage circulaire (identique)
        val yawRawRad = Math.toRadians(yawRawDeg.toDouble()).toFloat()
        val s = sin(yawRawRad)
        val c = cos(yawRawRad)
        yawSinF = (1f - yawAlpha) * yawSinF + yawAlpha * s
        yawCosF = (1f - yawAlpha) * yawCosF + yawAlpha * c

        val yawSmoothRad = atan2(yawSinF, yawCosF)
        return normalizeAngle(Math.toDegrees(yawSmoothRad.toDouble()).toFloat())
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        // IMPORTANT : on préfère ROTATION_VECTOR si dispo
        val rot = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val game = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        rotationVector = rot ?: game
        usingGameRV = (rotationVector?.type == Sensor.TYPE_GAME_ROTATION_VECTOR)

        rotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        Log.i(TAG, "onResume sensors registered usingGameRV=$usingGameRV")
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()

        handler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)

        // stop receiver
        receiverRunning = false
        recvThread?.interrupt()
        recvThread = null

        try { socketRecv?.close() } catch (_: Exception) {}
        try { socketSend?.close() } catch (_: Exception) {}
        socketRecv = null
        socketSend = null

        udpExec.shutdownNow()
        pathExec.shutdownNow()
    }

    // =================== CLICK ===================
    private fun handleClick(e: MotionEvent) {
        if (!mapReady) {
            Toast.makeText(this, "Carte en préparation...", Toast.LENGTH_SHORT).show()
            return
        }

        val p = mapTouchToBitmap(e.x, e.y)

        when {
            startPoint == null -> {
                resetNavigationState(keepPoints = false)
                startPoint = p
                Toast.makeText(this, "Départ", Toast.LENGTH_SHORT).show()
            }
            endPoint == null -> {
                // IMPORTANT : on garde startPoint
                resetNavigationState(keepPoints = true)
                endPoint = p
                Toast.makeText(this, "Arrivée → calcul", Toast.LENGTH_SHORT).show()
                computePathAsync()
            }
            else -> {
                resetNavigationState(keepPoints = false)
                startPoint = null
                endPoint = null
                Toast.makeText(this, "Reset", Toast.LENGTH_SHORT).show()
            }
        }
        draw()
    }

    // =================== RESET ===================
    private fun resetNavigationState(keepPoints: Boolean) {
        navigationActive = false
        navState = NavState.IDLE
        yawOffsetLockedToPath = false
        pendingStepsWhileLocked = 0
        yawSmoothPrevDeg = null
        yawAbsContDeg = 0f
        yawOffsetContDeg = 0f

        // distances/path
        cumDistPx = floatArrayOf()
        totalDistPx = 0f
        totalSteps = 0
        distNowPx = 0f
        ratioNow = 0f
        pathResult = emptyList()

        // steps
        rawStepCount = 0
        sentStepCount = 0

        // yaw
        yawFiltered = 0f
        yawOffset = 0f
        yawInit = false
        lastYawTime = 0L
        yawSinF = 0f
        yawCosF = 1f
        rv.fill(0f)
        ori.fill(0f)

        // map calib
        heading0Abs = 0f
        heading0Ready = false

        // turns
        turnEvents = emptyList()
        nextTurnIdx = 0
        turnLockActive = false
        turnLockDir = ""
        turnLockTargetAbs = 0f

        // lock anti-bruit
        lockOkCount = 0

        if (!keepPoints) {
            startPoint = null
            endPoint = null
        } else {
            endPoint = null
        }

        Log.i(TAG, "RESET done keepPoints=$keepPoints")
        udpSendLine("CTRL,RESET")
    }

    // =================== PATH ===================
    private fun computePathAsync() {
        val s = startPoint ?: return
        val e = endPoint ?: return

        pathExec.execute {
            try {
                val computed = if (lineClear(s, e)) listOf(s, e) else smoothPath(runAStar(s, e))
                if (computed.size < 2) {
                    runOnUiThread { Toast.makeText(this, "Aucun chemin", Toast.LENGTH_SHORT).show() }
                    return@execute
                }

                // heading0Abs (carte)
                val a = computed[0]
                val b = computed[1]
                val dx0 = (b.x - a.x)
                val dy0 = (b.y - a.y)
                val h0 = normalizeAngle(
                    Math.toDegrees(atan2(dx0.toDouble(), (-dy0).toDouble())).toFloat()
                )


                // cumDist
                val cd = buildCumDistLocal(computed)
                val total = cd.last().coerceAtLeast(0f)
                val steps = max(1, round(total / STEP_LEN_PX).toInt())

                val turns = computeTurnEventsByDistance(computed, cd)

                // publish (atomique-ish)
                pathResult = computed
                heading0Abs = h0
                heading0Ready = true
                cumDistPx = cd
                totalDistPx = total
                totalSteps = steps
                turnEvents = turns
                nextTurnIdx = 0
                turnLockActive = false
                lockOkCount = 0

                if (DBG) {
                    Log.i(TAG, "PATH ready N=${computed.size} totalDistPx=%.1f totalSteps=$steps heading0Abs=%.1f"
                        .format(Locale.US, total, h0))
                    turns.forEachIndexed { i, ev ->
                        Log.i(TAG, "TURN[$i] atDist=%.1fpx dir=${ev.dir} newAbs=%.1f newRel=%.1f"
                            .format(Locale.US, ev.atDistPx, ev.newHeadingAbs, absToRelMap(ev.newHeadingAbs)))
                    }
                }

                sendPathToMatlab(computed)
                navigationActive = true
                navState = NavState.READY

                runOnUiThread {
                    Toast.makeText(this, "Chemin prêt (${turns.size} virage(s))", Toast.LENGTH_SHORT).show()
                    draw()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "computePath error", t)
                runOnUiThread { Toast.makeText(this, "Erreur calcul chemin", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun buildCumDistLocal(path: List<PointF>): FloatArray {
        val n = path.size
        val cd = FloatArray(n)
        cd[0] = 0f
        for (i in 1 until n) {
            val dx = path[i].x - path[i - 1].x
            val dy = path[i].y - path[i - 1].y
            cd[i] = cd[i - 1] + hypot(dx, dy)
        }
        return cd
    }

    // =================== SENSORS ===================
    override fun onSensorChanged(e: SensorEvent?) {
        if (e == null) return

        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                System.arraycopy(e.values, 0, rv, 0, min(e.values.size, rv.size))
                if (navigationActive && navState == NavState.RUNNING && !turnLockActive) {
                    processNavigationLogic(trigger = "RV")
                }

                return
            }

            Sensor.TYPE_ACCELEROMETER -> {
                if (!navigationActive) return

                val mag = sqrt(e.values[0].pow(2) + e.values[1].pow(2) + e.values[2].pow(2))
                lowPass = 0.9f * lowPass + 0.1f * mag
                val dyn = mag - lowPass
                val now = System.currentTimeMillis()

                if (dyn > 0.9f && lastDyn < dyn && now - lastPeakTime > minStepDelay) {
                    lastPeakTime = now
                    rawStepCount++

                    if (navState == NavState.READY) {

                        // 1) on lock yawOffset sur la direction du segment courant (seg 0 normalement)
                        if (!yawOffsetLockedToPath) {
                            val path = pathResult
                            val cd = cumDistPx
                            if (path.size >= 2 && cd.size == path.size && totalDistPx > 0f) {

                                val maxSeg = path.size - 2
                                val segIdx = findSegmentIndexByDistance(cd, distNowPx).coerceIn(0, maxSeg)
                                val a = path[segIdx]
                                val b = path[segIdx + 1]

                                // angle carte avec ta convention: 0 = haut
                                val dx = (b.x - a.x)
                                val dy = (b.y - a.y)
                                val segAbs = normalizeAngle(
                                    Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
                                )
                                val segRel = absToRelMap(segAbs)

                                val yawAbsSmooth = getYawSmoothAbsDegOrNull()
                                if (yawAbsSmooth != null) {
                                    // On force yawRel = segRel  => yawOffset = yawAbsSmooth - segRel
                                    // yawAbsSmooth est dans [-180,180], on veut la valeur CONTINUE actuelle
// Donc on utilise yawAbsContDeg (qui est le yaw absolu continu déjà déduit)
                                    yawSmoothPrevDeg = yawAbsSmooth
                                    yawAbsContDeg = yawAbsSmooth
                                    yawOffsetContDeg = yawAbsContDeg - segRel
                                    yawOffset = normalizeAngle(yawAbsSmooth)
                                    yawInit = true
                                    yawFiltered = segRel
                                    lastYawTime = System.currentTimeMillis()
                                    yawOffsetLockedToPath = true

                                    Log.i(
                                        TAG,
                                        "YAW LOCKED TO PATH yawAbs=%.1f segRel=%.1f -> yawOffset=%.1f yawRel=%.1f"
                                            .format(Locale.US, yawAbsSmooth, segRel, yawOffset, yawFiltered)
                                    )
                                } else {
                                    Log.w(TAG, "YAW LOCK FAILED (no yawAbsSmooth yet)")
                                }
                            } else {
                                Log.w(TAG, "YAW LOCK FAILED (path/cd not ready)")
                            }
                        }

                        navState = NavState.RUNNING
                        udpSendLine("CTRL,START")
                        Log.i(TAG, "STATE -> RUNNING")
                    }


                    // Safe update
                    // Si virage locké et pas validé, on ne progresse pas sur la carte.
// On mémorise juste les pas.
                    if (turnLockActive) {
                        pendingStepsWhileLocked++
                        udpSendLine("STEP,HOLD,$pendingStepsWhileLocked")
                        // On continue la logique pour guider le virage (mais distNowPx ne bouge pas)
                        processNavigationLogic(trigger = "STEP_HOLD")
                        // Optionnel: petit feedback
                        // vibrate(40)
                        draw()
                    } else {
                        // Si on vient de sortir d’un lock, on applique les pas accumulés d’un coup
                        if (pendingStepsWhileLocked > 0) {
                            sentStepCount += pendingStepsWhileLocked
                            pendingStepsWhileLocked = 0
                        }

                        sentStepCount++

                        val total = totalDistPx
                        distNowPx = if (total > 0f) min(total, sentStepCount * STEP_LEN_PX) else 0f
                        ratioNow = if (total > 1e-3f) (distNowPx / total).coerceIn(0f, 1f) else 0f

                        udpSendLine("STEP,$sentStepCount")
                        processNavigationLogic(trigger = "STEP")
                        draw()
                    }

                }

                lastDyn = dyn
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // =================== NAV LOGIC ===================
    private fun processNavigationLogic(trigger: String) {
        if (navState != NavState.RUNNING) return

        val path = pathResult
        val cd = cumDistPx
        if (path.size < 2) return
        if (cd.size != path.size) return
        if (totalDistPx <= 0f) return

        val yawRel = getYawFiltered()
        udpSendLine("CTRL,YAW,%.1f".format(Locale.US, yawRel))

        val maxSeg = path.size - 2
        if (maxSeg < 0) return

        val segIdx = findSegmentIndexByDistance(cd, distNowPx).coerceIn(0, maxSeg)
        val a = path[segIdx]
        val b = path[segIdx + 1]

        val dx = (b.x - a.x)
        val dy = (b.y - a.y)
        val segAbs = normalizeAngle(
            Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
        )

        val segRel = absToRelMap(segAbs)
        val errSeg = angleErrorDeg(segRel, yawRel)

        udpSendLine(
            "DBG,YAW,yawRel=%.1f,segRel=%.1f,err=%.1f,dist=%.1f,ratio=%.3f,seg=%d,lock=%b,dir=%s,state=%s"
                .format(
                    Locale.US,
                    yawRel,
                    segRel,
                    errSeg,
                    distNowPx,
                    ratioNow,
                    segIdx,
                    turnLockActive,
                    turnLockDir,
                    navState.name
                )
        )

        val now = System.currentTimeMillis()
        if (DBG && now - lastDbgTime > DBG_PERIOD_MS) {
            lastDbgTime = now
            Log.d(
                TAG,
                "DBG trig=$trigger seg=$segIdx dist=%.1fpx ratio=%.3f step=$sentStepCount yawRel=%.1f segRel=%.1f err=%.1f lock=$turnLockActive($turnLockDir) okCount=$lockOkCount"
                    .format(Locale.US, distNowPx, ratioNow, yawRel, segRel, errSeg, turnLockDir)
            )
        }

        // 1) déclenchement lock virage par distance
        val turns = turnEvents
        if (!turnLockActive && nextTurnIdx < turns.size && sentStepCount >= minStepsBeforeFirstLock) {
            val ev = turns[nextTurnIdx]
            if (distNowPx >= (ev.atDistPx - lookAheadPx)) {
                turnLockActive = true
                turnLockDir = ev.dir
                turnLockTargetAbs = ev.newHeadingAbs
                lockOkCount = 0

                val targetRel = absToRelMap(turnLockTargetAbs)
                lockErrAtStartAbs = abs(angleErrorDeg(targetRel, yawRel))
                lockOkCount = 0
                Log.i(TAG, "LOCK ON dir=$turnLockDir atDist=%.1f targetAbs=%.1f targetRel=%.1f yawRel=%.1f"
                    .format(Locale.US, ev.atDistPx, turnLockTargetAbs, targetRel, yawRel))

                udpSendLine("TURN,$turnLockDir")
                vibrate(250)
            }
        }

        // 2) lock actif
        if (turnLockActive) {
            val targetRel = absToRelMap(turnLockTargetAbs)
            val err = angleErrorDeg(targetRel, yawRel)
// si au moment où on a locké, on était déjà presque bon, on ne valide pas ce lock
            if (lockErrAtStartAbs < lockMinErrToRequireTurn) {
                // on continue d’indiquer le virage mais on ne pourra pas faire OFF tout de suite
                // option 1: on annule le lock carrément
                turnLockActive = false
                udpSendLine("TURN,OK")
                Log.i(TAG, "LOC CANCEL (too close at start) errStart=%.1f".format(Locale.US, lockErrAtStartAbs))
                return
            }
            if (abs(err) <= TURN_END) {
                lockOkCount++
            } else if (abs(err) <= TURN_END + 6f) {
                // petite sortie de zone: on décrémente au lieu de reset
                lockOkCount = max(0, lockOkCount - 1)
            } else {
                lockOkCount = 0
            }

            Log.i(TAG, "LOCKCHK err=%.1f okCount=$lockOkCount need=$lockOkNeeded".format(Locale.US, err))

            if (lockOkCount >= lockOkNeeded) {
                turnLockActive = false
                nextTurnIdx++
                lockOkCount = 0
                // On applique les pas accumulés pendant le virage
                if (pendingStepsWhileLocked > 0) {
                    pendingStepsWhileLocked = 0


                    udpSendLine("STEP,$sentStepCount")
                    draw()
                }

                udpSendLine("TURN,OK")
                Log.i(TAG, "LOCK OFF OK (stable) yawRel=%.1f targetRel=%.1f nextTurnIdx=$nextTurnIdx"
                    .format(Locale.US, yawRel, targetRel))
                vibrate(120)
            } else {
                udpSendLine("TURN,$turnLockDir")
            }
        } else {
            // 3) corrections hors lock
            var diff = errSeg
            if (abs(diff) < deadZone) diff = 0f
            else if (abs(diff) < correctionThreshold) diff *= 0.5f

            if (diff > TURN_START) udpSendLine("TURN,RIGHT")
            else if (diff < -TURN_START) udpSendLine("TURN,LEFT")
            else udpSendLine("TURN,OK")
        }

        // fin
        if (ratioNow >= 0.995f) navCompleted()
    }

    private fun navCompleted() {
        navState = NavState.FINISHED
        navigationActive = false
        udpSendLine("CTRL,STOP")
        udpSendLine("TURN,OK")
        vibrate(700)
        Log.i(TAG, "NAV COMPLETED")
        runOnUiThread { Toast.makeText(this, "🏁 Arrivé", Toast.LENGTH_LONG).show() }
    }

    // =================== YAW FILTER (NEW: getOrientation-based) ===================
    private fun getYawFiltered(): Float {
        if (rv[0] == 0f && rv[1] == 0f && rv[2] == 0f) return yawFiltered

        SensorManager.getRotationMatrixFromVector(R, rv)

        @Suppress("DEPRECATION")
        val rot = windowManager.defaultDisplay.rotation
        val (axisX, axisY) = when (rot) {
            Surface.ROTATION_0 -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
            Surface.ROTATION_90 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
            Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
            Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
            else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
        }
        SensorManager.remapCoordinateSystem(R, axisX, axisY, Rr)

        // Orientation: azimuth(yaw), pitch, roll en radians
        SensorManager.getOrientation(Rr, ori)

        val yawRawDeg0 = Math.toDegrees(ori[0].toDouble()).toFloat()
        val pitchDeg = Math.toDegrees(ori[1].toDouble()).toFloat()
        val rollDeg = Math.toDegrees(ori[2].toDouble()).toFloat()

        // Rejet si téléphone trop "vertical" -> yaw devient instable (marche/poches)
        // Ici on fait simple: si pitch trop proche de +/-90 (ou trop fort), on garde la dernière valeur.
        if (abs(pitchDeg) > 70f) return yawFiltered

        var yawRawDeg = normalizeAngle(yawRawDeg0)
        if (INVERT_YAW) yawRawDeg = -yawRawDeg

        // Filtre circulaire (lissage sans problème -180/180)
        val yawRawRad = Math.toRadians(yawRawDeg.toDouble()).toFloat()
        val s = sin(yawRawRad)
        val c = cos(yawRawRad)
        yawSinF = (1f - yawAlpha) * yawSinF + yawAlpha * s
        yawCosF = (1f - yawAlpha) * yawCosF + yawAlpha * c

        val yawSmoothRad = atan2(yawSinF, yawCosF)
        val yawSmoothDeg = normalizeAngle(Math.toDegrees(yawSmoothRad.toDouble()).toFloat())

        val now = System.currentTimeMillis()

        // ---- UNWRAP (angle continu) ----
        if (yawSmoothPrevDeg == null) {
            yawSmoothPrevDeg = yawSmoothDeg
            yawAbsContDeg = yawSmoothDeg
        } else {
            // delta minimal dans [-180,180]
            val delta = angleErrorDeg(yawSmoothDeg, yawSmoothPrevDeg!!)
            yawAbsContDeg += delta
            yawSmoothPrevDeg = yawSmoothDeg
        }

        if (!yawInit) {
            // offset continu = yaw actuel (donc yawRel=0 au départ, ou remplacé ensuite par ton "lock to path")
            yawOffsetContDeg = yawAbsContDeg
            yawOffset = normalizeAngle(yawSmoothDeg) // juste pour log/compat
            yawInit = true
            yawFiltered = 0f
            lastYawTime = now
            Log.i(TAG, "YAW INIT (cont) yawAbsCont=%.1f smooth=%.1f pitch=%.1f roll=%.1f"
                .format(Locale.US, yawAbsContDeg, yawSmoothDeg, pitchDeg, rollDeg))
            return 0f
        }

// yaw relatif (continu) puis ramené dans [-180,180] pour comparaison/affichage
        val yawRelCont = (yawAbsContDeg - yawOffsetContDeg)
        val yawRelDeg = normalizeAngle(yawRelCont)

// ---- Limitation vitesse sur le RELATIF (évite sauts) ----
        val dt = max(0.001f, (now - lastYawTime) / 1000f)
        val dyaw = angleErrorDeg(yawRelDeg, yawFiltered)   // delta minimal
        val maxDelta = maxYawRate * dt
        yawFiltered = normalizeAngle(yawFiltered + dyaw.coerceIn(-maxDelta, maxDelta))
        lastYawTime = now

        return yawFiltered

    }

    private fun normalizeAngle(a: Float): Float {
        var x = (a + 180f) % 360f - 180f
        if (x < -180f) x += 360f
        return x
    }

    private fun angleErrorDeg(targetDeg: Float, yawDeg: Float): Float {
        return (targetDeg - yawDeg + 540f) % 360f - 180f
    }
    private fun getCurrentSegRelOrNull(): Float? {
        val path = pathResult
        val cd = cumDistPx
        if (path.size < 2) return null
        if (cd.size != path.size) return null
        if (totalDistPx <= 0f) return null

        val maxSeg = path.size - 2
        if (maxSeg < 0) return null

        val segIdx = findSegmentIndexByDistance(cd, distNowPx).coerceIn(0, maxSeg)
        val a = path[segIdx]
        val b = path[segIdx + 1]
        val dx = (b.x - a.x)
        val dy = (b.y - a.y)
        val segAbs = normalizeAngle(
            Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
        )
        return absToRelMap(segAbs)
    }
    private fun angleToUnitVec(angleDeg: Float): PointF {
        val rad = Math.toRadians(angleDeg.toDouble())
        val x = sin(rad).toFloat()
        val y = (-cos(rad)).toFloat()
        return PointF(x, y)
    }
    private fun drawHeadingCompass(c: Canvas) {
        if (!SHOW_COMPASS || !mapReady) return
        val r = compassRadiusPx
        val cx = bmpPlan.width - r - 12f
        val cy = r + 12f

        c.drawCircle(cx, cy, r, paintCompassBg)
        c.drawCircle(cx, cy, r, paintCompassCircle)

        val yawRel = yawFiltered
        val yawVec = angleToUnitVec(yawRel)
        c.drawLine(cx, cy, cx + yawVec.x * r, cy + yawVec.y * r, paintCompassYaw)

        val segRel = getCurrentSegRelOrNull()
        if (segRel != null) {
            val segVec = angleToUnitVec(segRel)
            c.drawLine(cx, cy, cx + segVec.x * r, cy + segVec.y * r, paintCompassSeg)

            val err = angleErrorDeg(segRel, yawRel)
            val tx = cx - r
            var ty = cy + r + 24f
            c.drawText("yaw %.0f".format(Locale.US, yawRel), tx, ty, paintCompassText)
            ty += 24f
            c.drawText("seg %.0f".format(Locale.US, segRel), tx, ty, paintCompassText)
            ty += 24f
            c.drawText("err %.0f".format(Locale.US, err), tx, ty, paintCompassText)
        } else {
            c.drawText("yaw %.0f".format(Locale.US, yawRel), cx - r, cy + r + 24f, paintCompassText)
        }
    }

    // =================== TURN EVENTS ===================
    private fun computeTurnEventsByDistance(path: List<PointF>, cd: FloatArray): List<TurnEvent> {
        if (path.size < 3) return emptyList()

        fun segAngle(i: Int): Float {
            val a = path[i]
            val b = path[i + 1]
            val dx = (b.x - a.x)
            val dy = (b.y - a.y)
            return normalizeAngle(
                Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
            )

        }

        val out = mutableListOf<TurnEvent>()
        for (i in 0 until path.size - 2) {
            val a1 = segAngle(i)
            val a2 = segAngle(i + 1)
            val d = normalizeAngle(a2 - a1)

            if (abs(d) >= 35f) {
                val dir = if (d > 0) "RIGHT" else "LEFT"
                val distAt = cd[i + 1]
                out.add(TurnEvent(distAt, dir, a2))
            }
        }

        // anti-virages collés: min 2 pas
        val filtered = mutableListOf<TurnEvent>()
        var lastDist = -1e9f
        val minGap = 2f * STEP_LEN_PX
        for (ev in out) {
            if (ev.atDistPx - lastDist >= minGap) {
                filtered.add(ev)
                lastDist = ev.atDistPx
            }
        }
        return filtered
    }

    private fun findSegmentIndexByDistance(cd: FloatArray, d: Float): Int {
        if (cd.isEmpty()) return 0
        var lo = 0
        var hi = cd.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cd[mid] <= d) lo = mid + 1 else hi = mid
        }
        return max(0, lo - 1)
    }

    // =================== UDP ===================
    private fun udpSendLine(line: String) {
        val sock = socketSend ?: return
        udpExec.execute {
            try {
                val s = "$line\n"
                val data = s.toByteArray()
                val p = DatagramPacket(data, data.size, InetAddress.getByName(matlabIP), portSend)
                sock.send(p)
            } catch (t: Throwable) {
                Log.e(TAG, "udpSendLine error: $line", t)
            }
        }
    }

    private fun sendPathToMatlab(path: List<PointF>) {
        // construit message une fois (évite concat dans thread)
        val sb = StringBuilder("PATH")
        path.forEach { sb.append(";${it.x},${it.y}") }

        udpSendLine(sb.toString())
        udpSendLine("CTRL,PARAM,PXPERM,%.2f".format(Locale.US, PX_PER_M))
        udpSendLine("CTRL,PARAM,STEPLEN,%.2f".format(Locale.US, STEP_LEN_M))
        udpSendLine("CTRL,PARAM,HEADING0ABS,%.1f".format(Locale.US, heading0Abs))
        udpSendLine("CTRL,PARAM,TOTALSTEPS,$totalSteps")

        Log.i(TAG, "Sent PATH + PARAMS to Matlab")
    }

    private fun startReceiver() {
        val sock = socketRecv ?: return
        receiverRunning = true

        recvThread = Thread {
            val buf = ByteArray(512)
            while (receiverRunning && !Thread.currentThread().isInterrupted) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p) // timeout 1000ms -> permet de checker receiverRunning
                    val data = String(p.data, 0, p.length).trim()
                    Log.d(TAG, "RX: $data")
                } catch (e: SocketTimeoutException) {
                    // normal, on boucle
                } catch (e: SocketException) {
                    // socket fermé
                    break
                } catch (e: IOException) {
                    Log.e(TAG, "Receiver IO error", e)
                } catch (t: Throwable) {
                    Log.e(TAG, "Receiver error", t)
                }
            }
            Log.i(TAG, "Receiver stopped")
        }.apply {
            name = "udp-receiver"
            isDaemon = true
            start()
        }
    }

    // =================== DRAW (ANTI-OOM, stable) ===================
    private fun draw() {
        if (!mapReady) return

        // Clear overlay
        overlayCanvas.drawRect(0f, 0f, bmpOverlay.width.toFloat(), bmpOverlay.height.toFloat(), paintClear)

        // points
        startPoint?.let { overlayCanvas.drawCircle(it.x, it.y, 14f, paintStart) }
        endPoint?.let { overlayCanvas.drawCircle(it.x, it.y, 14f, paintEnd) }

        // path + progress
        val path = pathResult
        val cd = cumDistPx

        if (path.size >= 2) {
            for (i in 0 until path.size - 1) {
                overlayCanvas.drawLine(path[i].x, path[i].y, path[i + 1].x, path[i + 1].y, paintFull)
            }

            if (cd.size == path.size) {
                val maxIdx = path.size - 2
                if (maxIdx >= 0) {
                    val idx = findSegmentIndexByDistance(cd, distNowPx).coerceIn(0, maxIdx)
                    for (i in 0 until idx) {
                        overlayCanvas.drawLine(
                            path[i].x, path[i].y,
                            path[i + 1].x, path[i + 1].y,
                            paintProg
                        )
                    }
                }
            }
        }

        // Compose base + overlay (une seule copie légère)
        drawHeadingCompass(overlayCanvas)
        val composed = Bitmap.createBitmap(bmpBase.width, bmpBase.height, Bitmap.Config.ARGB_8888)
        val cc = Canvas(composed)
        cc.drawBitmap(bmpBase, 0f, 0f, null)
        cc.drawBitmap(bmpOverlay, 0f, 0f, null)

        imageView.setImageBitmap(composed)
    }

    // =================== VIBRATE ===================
    private fun vibrate(duration: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } catch (e: Exception) {
            Log.e(TAG, "vibrate error", e)
        }
    }

    // =================== MAP UTILS ===================
    private fun detectAndDilateDoors() {
        doorMask.clear()
        val w = bmpPlan.width
        val h = bmpPlan.height
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            val hsv = FloatArray(3)
            Color.colorToHSV(bmpPlan.getPixel(x, y), hsv)
            if (hsv[0] in 200f..240f && hsv[1] > 0.3f && hsv[2] > 0.2f) {
                for (dx in -3..3) for (dy in -3..3) {
                    val nx = (x + dx).coerceIn(0, w - 1)
                    val ny = (y + dy).coerceIn(0, h - 1)
                    doorMask.add(ny * w + nx)
                    bmpPlan.setPixel(nx, ny, Color.WHITE)
                }
            }
        }
    }

    private fun precomputeWalkable() {
        val w = bmpPlan.width
        val h = bmpPlan.height
        for (y in 0 until h) for (x in 0 until w) {
            val k = y * w + x
            val c = bmpPlan.getPixel(x, y)
            walkable[k] = doorMask.contains(k) ||
                    (Color.red(c) > 240 && Color.green(c) > 240 && Color.blue(c) > 240)
        }
    }

    private fun isWalkable(x: Int, y: Int): Boolean {
        if (!::walkable.isInitialized) return false
        if (x !in 0 until bmpPlan.width || y !in 0 until bmpPlan.height) return false
        return walkable[y * bmpPlan.width + x]
    }

    private fun runAStar(s: PointF, e: PointF): List<PointF> {
        data class N(val x: Int, val y: Int, val g: Float, val f: Float, val p: N?)
        val step = 6
        val w = bmpPlan.width

        val sx = (s.x.toInt() / step) * step
        val sy = (s.y.toInt() / step) * step
        val ex = (e.x.toInt() / step) * step
        val ey = (e.y.toInt() / step) * step

        val open = PriorityBlockingQueue<N>(128, compareBy { it.f })
        val closed = HashSet<Int>()
        val bestG = HashMap<Int, Float>()

        val dirs = arrayOf(
            intArrayOf(step, 0), intArrayOf(-step, 0),
            intArrayOf(0, step), intArrayOf(0, -step),
            intArrayOf(step, step), intArrayOf(step, -step),
            intArrayOf(-step, step), intArrayOf(-step, -step)
        )

        open.add(N(sx, sy, 0f, 0f, null))
        bestG[sy * w + sx] = 0f
        var found: N? = null

        while (open.isNotEmpty()) {
            val c = open.poll()
            val ck = c.y * w + c.x
            if (!closed.add(ck)) continue

            if (hypot((c.x - ex).toFloat(), (c.y - ey).toFloat()) <= step) {
                found = c
                break
            }

            for (d in dirs) {
                val nx = c.x + d[0]
                val ny = c.y + d[1]
                if (!isWalkable(nx, ny)) continue

                val nk = ny * w + nx
                if (closed.contains(nk)) continue

                val cost = if (d[0] == 0 || d[1] == 0) 1f else 1.414f
                val ng = c.g + cost

                val old = bestG[nk]
                if (old != null && ng >= old) continue
                bestG[nk] = ng

                val hCost = hypot(((ex - nx) / step).toFloat(), ((ey - ny) / step).toFloat())
                open.add(N(nx, ny, ng, ng + 1.2f * hCost, c))
            }
        }

        if (found == null) return emptyList()

        val path = mutableListOf<PointF>()
        var n: N? = found
        while (n != null) {
            path.add(PointF(n.x.toFloat(), n.y.toFloat()))
            n = n.p
        }
        return path.reversed()
    }

    private fun smoothPath(path: List<PointF>): List<PointF> {
        if (path.size < 3) return path
        val out = mutableListOf<PointF>()
        out.add(path.first())
        var i = 0
        while (i < path.size - 1) {
            var j = path.size - 1
            while (j > i + 1 && !lineClear(path[i], path[j])) j--
            out.add(path[j])
            i = j
        }
        return out
    }

    private fun lineClear(a: PointF, b: PointF): Boolean {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val steps = max(abs(dx), abs(dy)).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val x = (a.x + dx * i / steps).toInt()
            val y = (a.y + dy * i / steps).toInt()
            if (!isWalkable(x, y)) return false
        }
        return true
    }

    private fun mapTouchToBitmap(x: Float, y: Float): PointF {
        val ivW = imageView.width.toFloat()
        val ivH = imageView.height.toFloat()
        val bW = bmpPlan.width.toFloat()
        val bH = bmpPlan.height.toFloat()
        val s = min(ivW / bW, ivH / bH).coerceAtLeast(1e-6f)
        val ox = (ivW - bW * s) / 2
        val oy = (ivH - bH * s) / 2
        return PointF(
            ((x - ox) / s).coerceIn(0f, bW - 1),
            ((y - oy) / s).coerceIn(0f, bH - 1)
        )
    }
}
