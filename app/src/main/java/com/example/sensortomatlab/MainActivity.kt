package com.example.sensortomatlab

import android.graphics.*
import android.hardware.*
import android.os.*
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.net.*
import java.util.*
import java.util.concurrent.*
import kotlin.math.*

class MainActivity : AppCompatActivity(), SensorEventListener {

    // =================== USER PARAMS ===================
    private val PX_PER_M = 14.69f       // calibration px/m
    private val STEP_LEN_M = 0.65f     // longueur de pas moyenne
    private val STEP_LEN_PX = PX_PER_M * STEP_LEN_M

    // Si yawRel part toujours dans le mÃªme sens (gauche/droite), mets true
    private val INVERT_YAW = false

    // =================== UI ===================
    private lateinit var imageView: ImageView
    private lateinit var bmpPlan: Bitmap              // base (modifiable pour doors)
    private lateinit var bmpBase: Bitmap              // base immuable pour affichage
    private lateinit var bmpOverlay: Bitmap           // overlay rÃ©utilisable (anti-OOM)
    private lateinit var overlayCanvas: Canvas
    private val paintClear = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val paintStart = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN }
    private val paintEnd = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.MAGENTA }
    private val paintPathOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 30, 144, 255)
        strokeWidth = 16f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val paintFull = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 180, 190, 200)
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val paintProg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 122, 255)
        strokeWidth = 12f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val paintMarkerOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val paintMarkerShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val paintStartMarker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(52, 199, 89)
        style = Paint.Style.FILL
    }
    private val paintEndMarker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 59, 48)
        style = Paint.Style.FILL
    }
    private val paintUserArrowOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val paintUserArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 122, 255) }
    private val paintUserDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val paintUserRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 122, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintPulse = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 122, 255)
        style = Paint.Style.FILL
    }
    private val paintUiBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 140
        style = Paint.Style.FILL
    }
    private val paintUiText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
    }
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

    // distance cumule sur le path
    @Volatile private var cumDistPx: FloatArray = floatArrayOf()
    @Volatile private var totalDistPx = 0f
    @Volatile private var totalSteps = 0

    // =================== SENSORS ===================
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var rotationVector: Sensor? = null // with magno
    private var gameRotationVector: Sensor? = null // without magno
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private var usingGameRV = false
    private val sendFrameTimestamp = true

    private var yawRawDeg0 = -1000f
    private var lastYawRawDeg = 0f
    private var lastYawSmoothDeg = 0f

    private var lastAccelX = 0f
    private var lastAccelY = 0f
    private var lastAccelZ = 0f
    private var lastAccelXf = 0f
    private var lastAccelYf = 0f
    private var lastAccelZf = 0f
    private var lastAccelMagFilt = 0f

    private var lastMagX = 0f
    private var lastMagY = 0f
    private var lastMagZ = 0f
    private var lastMagNorm = 0f

    private var lastGameQuatX = 0f
    private var lastGameQuatY = 0f
    private var lastGameQuatZ = 0f
    private var lastGameQuatW = 0f

    // step detection (band-pass + peak/zero-cross)
    private val stepBandLowHz = 0.7f
    private val stepBandHighHz = 3.0f
    private val stepThreshK = 1.2f
    private val stepZScoreMin = 2.5f
    private val stepProminenceMin = 0.15f
    private val stepZeroCrossTimeoutMs = 400L
    private val minStepDelay = 150
    private val accelStatsWindow = 120
    private val gyroStatsWindow = 40
    private val minGyroVar = 0.02f

    private var lastAccelTsNs = 0L
    private var accelFs = 50f
    private val accelFilter = BiquadBandpass()
    private val accelFilterX = BiquadBandpass()
    private val accelFilterY = BiquadBandpass()
    private val accelFilterZ = BiquadBandpass()
    private var lastFilt = 0f
    private var lastDeriv = 0f
    private var waitingZeroCross = false
    private var peakTimeMs = 0L
    private var lastStepTimeMs = 0L

    private val accelWin = FloatArray(accelStatsWindow)
    private var accelWinCount = 0
    private var accelWinIdx = 0
    private var accelSum = 0f
    private var accelSumSq = 0f

    private val gyroWin = FloatArray(gyroStatsWindow)
    private var gyroWinCount = 0
    private var gyroWinIdx = 0
    private var gyroSum = 0f
    private var gyroSumSq = 0f
    private var gyroVar = 0f

    private var rawStepCount = 0
    private var sentStepCount = 0
    private var yawOffsetLockedToPath = false

    // =================== NAV STATE ===================
    enum class NavState { IDLE, READY, RUNNING, FINISHED }
    @Volatile private var navState = NavState.IDLE
    @Volatile private var navigationActive = false

    // position estime sur la carte
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
    private val magAnomalyThreshold = 0.2f
    private val magAnomalyCountNeeded = 3
    private val magRecoverCountNeeded = 8
    private val magAbsMin = 25f
    private val magAbsMax = 65f
    private val magDerivThreshold = 6f
    private var magMean = 0f
    private var magAnomalyCount = 0
    private var magRecoverCount = 0
    private var magDisturbed = false
    private var lastMag = 0f
    private var lastMagTimeNs = 0L
    private var magDeriv = 0f
    private var lastYawSource = "ROT"

    private val USE_MAP_MATCHING = true
    private val mapMatchThresholdPx = 1.5f * PX_PER_M
    private val mapMatchLambdaBase = 0.7f
    private var lastProjDistPx = 0f
    private var lastMapMatchLambda = mapMatchLambdaBase
    private val yawVarWindow = 30
    private val yawWindow = FloatArray(yawVarWindow)
    private var yawWindowCount = 0
    private var yawWindowIdx = 0
    private var yawWindowSum = 0f
    private var yawWindowSumSq = 0f
    private var drPosPx = PointF(0f, 0f)
    private var drHasPos = false
    private val forwardTooVerticalThreshold = 0.97f // gardÃ©, mais on va aussi rejeter sur pitch/roll

    // =================== YAW <-> MAP CALIB ===================
    private var heading0Abs = 0f
    private var heading0Ready = false
    private lateinit var bmpComposed : Bitmap
    private lateinit var composedCanvas : Canvas

    // conversion from abs degreee to real degree
    private fun absToRelMap(absDeg: Float): Float {
        return if (heading0Ready) normalizeAngle(normalizeAngle(absDeg) - heading0Abs)
        else normalizeAngle(absDeg)
    }

    //Contexte rapide : cette fonction essaye de recadrer (calibrer) le yaw du téléphone (orientation) pour qu’il corresponde à la direction du chemin (le segment courant du path).
    //En gros : “je prends le segment du trajet où je suis, je calcule son angle, puis je force/ajuste mon yaw pour être aligné dessus”.
    private fun calibrateYawToPath(): Boolean {
        val path = pathResult
        val cd = cumDistPx //tableau/liste des distances cumulées en pixels, aligné sur path ;Typiquement cd[i] = distance depuis le début du path jusqu’au point path[i].
        if (path.size < 2 || cd.size != path.size || totalDistPx <= 0f) return false
    //    path.size < 2 : il faut au moins 2 points pour faire un segment.

      //  cd.size != path.size : si les distances cumulées ne correspondent pas au path → incohérent.

        //totalDistPx <= 0f : distance totale invalide (path vide ou bug).
        // Si une condition est vraie : on ne peut pas calibrer → false.
        val maxSeg = path.size - 2
        val segIdx = findSegmentIndexByDistance(cd, distNowPx).coerceIn(0, maxSeg)
     //   Un path de N points a N-1 segments : segment 0 = (0→1), segment 1 = (1→2), etc.

     //   maxSeg = path.size - 2 : index maximum possible pour segIdx car on accède à segIdx + 1.

      //  findSegmentIndexByDistance(cd, distNowPx) : trouve l’index du segment correspondant à ta position actuelle le long du chemin :

      //  distNowPx = distance courante parcourue sur le path (en px).

       // La fonction doit renvoyer un i tel que cd[i] <= distNowPx < cd[i+1] (probable) .coerceIn(0, maxSeg) : sécurité : force l’index entre 0 et maxSeg (évite crash hors limites).
        val a = path[segIdx]
        val b = path[segIdx + 1]
       // a et b : les deux points qui définissent le segment courant du path.
        val dx = (b.x - a.x)
        val dy = (b.y - a.y)
//        dx : déplacement horizontal

  //      dy : déplacement vertical
        val segAbs = normalizeAngle(
            Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
        )
       /* Ici, ça calcule l’angle “absolu” du segment, en degrés, puis le normalise.

        Subtilité importante : la formule n’est pas le atan2(dy, dx) standard.

        atan2(dx, -dy) : ça veut dire que :

        tu utilises dx comme “composante Y”,

        et -dy comme “composante X”.

        Ça correspond souvent à un repère où :

        l’angle 0° est “vers le haut” (Nord écran),

        et où l’axe Y écran est inversé (car en UI, y augmente vers le bas).
        Le -dy remet “vers le haut” dans le bon sens.

        Ensuite :

        Math.toDegrees(...) convertit radians → degrés.

        normalizeAngle(...) remet l’angle dans une plage standard (souvent [0,360) ou (-180,180] selon l’implémentation).
        Sans ça, tu peux avoir des valeurs négatives ou >360.*/
        val segRel = absToRelMap(segAbs)
       /* Convertit segAbs (angle “absolu” dans un repère global / écran / monde) en segRel (angle “relatif” dans ton repère yaw / UI / boussole interne).

        Typiquement ça sert à passer d’une convention d’angle à une autre (ex: 0° = nord vs 0° = est, sens horaire vs anti-horaire, etc.)*/

        val yawAbsSmooth = getYawSmoothAbsDegOrNull() ?: return false
        /*Récupère le yaw du téléphone lissé/filtré, en degrés, absolu.
        Si pas dispo (null) → impossible de calibrer → false.*/

        yawSmoothPrevDeg = yawAbsSmooth
        yawAbsContDeg = yawAbsSmooth
      /*  yawSmoothPrevDeg : dernier yaw smooth connu (pour calculer des deltas ensuite).

        yawAbsContDeg : yaw “continu” (souvent utilisé pour éviter les sauts à 0/360).

        Ici, on les “reset” au yaw actuel (moment de calibration).*/

        yawOffsetContDeg = yawAbsContDeg - segRel
        yawOffset = normalizeAngle(yawAbsSmooth)
        yawInit = true
        yawFiltered = segRel
/*        Force la valeur filtrée à la direction du chemin.
        Donc immédiatement après calibration, ton yaw affiché/consommé devient exactement l’angle du segment.*/
        lastYawTime = System.currentTimeMillis()
        yawOffsetLockedToPath = true

        Log.i(
            TAG,
            "YAW ALIGN button yawAbs=%.1f segRel=%.1f yawRel=%.1f"
                .format(Locale.US, yawAbsSmooth, segRel, yawFiltered)
        )
        return true
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

    // Anti validation virage toute seule : on exige plusieurs ticks dans la zone
    private var lockOkCount = 0
    private val lockOkNeeded = 6 // 6 ticks * 50ms â‰ˆ 300ms (avec yawTick), trÃ¨s efficace

    data class TurnEvent(
        val atDistPx: Float,
        val dir: String,
        val newHeadingAbs: Float
    )
    data class TurnInfo(
        val dir: String,
        val distM: Float
    )

    @Volatile private var turnEvents: List<TurnEvent> = emptyList()
    private var nextTurnIdx = 0
    private var pendingStepsWhileLocked = 0

    private var turnLockActive = false
    private var turnLockDir = ""
    private var turnLockTargetAbs = 0f
    private val turnGyroVarMin = 0.015f
    private var turnGyroSeen = false

    // =================== UDP ===================
    private val matlabIP = "192.168.43.18"
    private val portSend = 30000
    private val portRecv = 31000
    private var yawSmoothPrevDeg: Float? = null
    private var yawAbsContDeg = 0f      // yaw "dÃ©roulÃ©" continu
    private var yawOffsetContDeg = 0f   // offset en continu

    private var socketSend: DatagramSocket? = null
    private var socketRecv: DatagramSocket? = null
    private var recvThread: Thread? = null
    @Volatile private var receiverRunning = false
    private var lockErrAtStartAbs = 999f
    // =================== ZOOM / PAN ===================
    private val imageMatrixCurrent = Matrix()
    private var matrixReady = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isPanning = false
    private var isScaling = false
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var minScale = 1.0f
    private var maxScale = 4.0f
    private var baseScale = 1.0f
    private lateinit var scaleDetector: ScaleGestureDetector
    private var lastTurnHintStage = 0
    private var lastTurnHintIdx = -1
    private val lockMinErrToRequireTurn = 22f // degrÃ©s


    // Un seul thread pour tous les envois UDP (anti-threads en rafale)
    private val udpExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "udp-sender").apply { isDaemon = true }
    }

    // =================== PATH EXEC ===================
    private val pathExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "path-worker").apply { isDaemon = true }
    }

    // =================== CSV LOG ===================
    private val ENABLE_CSV_LOG = true
    private val csvExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "csv-logger").apply { isDaemon = true }
    }
    @Volatile private var csvWriter: BufferedWriter? = null
    private var csvLineCount = 0

    // =================== DEBUG ===================
    private val TAG = "NAVAPP"
    private val DBG = true
    private val DBG_PERIOD_MS = 300L
    private var lastDbgTime = 0L
    private var lastDrawMs = 0L
    private val minDrawIntervalMs = 33L

    // Timer: envoi yaw en continu mmm sans marcher (vite timeout Matlab)
    private val yawTickMs = 50L
    private val handler = Handler(Looper.getMainLooper())
    private val yawTick = object : Runnable {
        override fun run() {
            try {
                sendFrameToMatlab()
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

        val root = FrameLayout(this)
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            adjustViewBounds = false
        }
        root.addView(
            imageView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val alignButton = Button(this).apply {
            text = "ALIGN"
            setOnClickListener {
                val ok = calibrateYawToPath()
                if (ok) {
                    Toast.makeText(this@MainActivity, "Yaw aligned", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Align failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val margin = (12f * resources.displayMetrics.density).toInt()
        val alignParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = margin
            topMargin = margin
        }
        root.addView(alignButton, alignParams)
        setContentView(root)

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                imageMatrixCurrent.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
                constrainImageMatrix()
                imageView.imageMatrix = imageMatrixCurrent
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        })

        // Charge plan (modifiable pour doors)
        bmpPlan = BitmapFactory.decodeResource(
            resources,
            com.example.sensortomatlab.R.drawable.rdc_galilee
        ).copy(Bitmap.Config.ARGB_8888, true)

        // Base immuable pour affichage (aprÃ¨s doors)
        bmpBase = bmpPlan.copy(Bitmap.Config.ARGB_8888, false)

        // Overlay rÃ©utilisable
        bmpOverlay = Bitmap.createBitmap(bmpPlan.width, bmpPlan.height, Bitmap.Config.ARGB_8888)
        overlayCanvas = Canvas(bmpOverlay)

        // Buffer walkable
        walkable = BooleanArray(bmpPlan.width * bmpPlan.height)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // IMPORTANT : on prÃ©fÃ¨re ROTATION_VECTOR (avec mag) si dispo -> yaw moins â€œfantÃ´meâ€
        val rot = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val game = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        rotationVector = rot
        gameRotationVector = game
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        usingGameRV = false
        Log.i(
            TAG,
            "RotationVector=${rotationVector?.name} GameRV=${gameRotationVector?.name} gyro=${gyroscope?.name} mag=${magnetometer?.name}"
        )

        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        try {
            socketSend = DatagramSocket().apply { soTimeout = 0 }
            socketRecv = DatagramSocket(portRecv).apply { soTimeout = 1000 } // timeout pour sortir proprement
        } catch (e: Exception) {
            Log.e(TAG, "UDP socket error", e)
        }
        udpSendLine("CTRL,PARAM,PXPERM,%.2f" .format(Locale.US,PX_PER_M))
        udpSendLine("CTRL,PARAM,STEPLEN,%.2f" .format(Locale.US,STEP_LEN_M))

        imageView.post {
            Thread {
                try {
                    detectAndDilateDoors()
                    precomputeWalkable()

                    // RecrÃ©e bmpBase aprÃ¨s modifications doors
                    bmpBase = bmpPlan.copy(Bitmap.Config.ARGB_8888, false)
                    bmpComposed =  Bitmap.createBitmap(bmpBase.width, bmpBase.height ,Bitmap.Config.ARGB_8888)
                    composedCanvas = Canvas(bmpComposed)
                    mapReady = true
                    runOnUiThread {
                        draw() // premier render
                        Toast.makeText(this, "Carte prete ", Toast.LENGTH_SHORT).show()
                    }
                    Log.i(TAG, "Map ready: ${bmpPlan.width}x${bmpPlan.height}")
                } catch (t: Throwable) {
                    Log.e(TAG, "Map init error", t)
                    runOnUiThread { Toast.makeText(this, "Erreur init carte", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }

        imageView.setOnTouchListener { v, e ->
            if (mapReady) {
                scaleDetector.onTouchEvent(e)
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = e.x
                    lastTouchY = e.y
                    isPanning = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    isPanning = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isScaling && e.pointerCount == 1) {
                        val dx = e.x - lastTouchX
                        val dy = e.y - lastTouchY
                        if (!isPanning && hypot(dx, dy) > touchSlop) {
                            isPanning = true
                        }
                        if (isPanning) {
                            imageMatrixCurrent.postTranslate(dx, dy)
                            constrainImageMatrix()
                            imageView.imageMatrix = imageMatrixCurrent
                        }
                        lastTouchX = e.x
                        lastTouchY = e.y
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!isPanning && !isScaling) {
                        try {
                            handleClick(e)
                        } catch (t: Throwable) {
                            Log.e(TAG, "handleClick crash prevented", t)
                        }
                        v.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    isPanning = false
                }
            }
            true
        }

        startReceiver()
        handler.postDelayed(yawTick, yawTickMs)
    }
    private fun getYawSmoothAbsDegOrNull(): Float? {
        return if (yawRawDeg0 > -999f) yawRawDeg0 else null
    }


    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        // IMPORTANT : on prÃ©fÃ¨re ROTATION_VECTOR si dispo
        val rot = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val game = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        rotationVector = rot
        gameRotationVector = game

        rotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gameRotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        Log.i(TAG, "onResume sensors registered rot=${rotationVector?.name} game=${gameRotationVector?.name}")
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

        closeCsvLogger()
        csvExec.shutdown()
        udpExec.shutdownNow()
        pathExec.shutdownNow()
    }

    // =================== CLICK ===================
    private fun handleClick(e: MotionEvent) {
        if (!mapReady) {
            Toast.makeText(this, "Carte en preparation...", Toast.LENGTH_SHORT).show()
            return
        }

        val p = mapTouchToBitmap(e.x, e.y)

        when {
            startPoint == null -> {
                resetNavigationState(keepPoints = false)
                startPoint = p
                Toast.makeText(this, "start", Toast.LENGTH_SHORT).show()
            }
            endPoint == null -> {
                // IMPORTANT : on garde startPoint
                resetNavigationState(keepPoints = true)
                endPoint = p
                Toast.makeText(this, "end", Toast.LENGTH_SHORT).show()
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
        closeCsvLogger()

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
        lastAccelTsNs = 0L
        accelFs = 50f
        accelFilter.reset()
        accelFilterX.reset()
        accelFilterY.reset()
        accelFilterZ.reset()
        lastAccelX = 0f
        lastAccelY = 0f
        lastAccelZ = 0f
        lastAccelXf = 0f
        lastAccelYf = 0f
        lastAccelZf = 0f
        lastAccelMagFilt = 0f
        lastFilt = 0f
        lastDeriv = 0f
        waitingZeroCross = false
        peakTimeMs = 0L
        lastStepTimeMs = 0L
        accelWinCount = 0
        accelWinIdx = 0
        accelSum = 0f
        accelSumSq = 0f
        gyroWinCount = 0
        gyroWinIdx = 0
        gyroSum = 0f
        gyroSumSq = 0f
        gyroVar = 0f

        // yaw
        yawFiltered = 0f
        yawOffset = 0f
        yawInit = false
        lastYawRawDeg = 0f
        lastYawSmoothDeg = 0f
        lastYawTime = 0L
        yawSinF = 0f
        yawCosF = 1f
        rv.fill(0f)
        ori.fill(0f)
        magMean = 0f
        magAnomalyCount = 0
        magRecoverCount = 0
        magDisturbed = false
        lastMagX = 0f
        lastMagY = 0f
        lastMagZ = 0f
        lastMagNorm = 0f
        lastMag = 0f
        lastMagTimeNs = 0L
        magDeriv = 0f
        lastYawSource = "ROT"
        lastGameQuatX = 0f
        lastGameQuatY = 0f
        lastGameQuatZ = 0f
        lastGameQuatW = 0f
        yawWindowCount = 0
        yawWindowIdx = 0
        yawWindowSum = 0f
        yawWindowSumSq = 0f
        lastProjDistPx = 0f
        lastMapMatchLambda = mapMatchLambdaBase
        drPosPx = PointF(0f, 0f)
        drHasPos = false

        // map calib
        heading0Abs = 0f
        heading0Ready = false

        // turns
        turnEvents = emptyList()
        nextTurnIdx = 0
        turnLockActive = false
        turnLockDir = ""
        turnLockTargetAbs = 0f
        turnGyroSeen = false
        lastTurnHintIdx = -1
        lastTurnHintStage = 0

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
    private fun snapToWalkable(p: PointF, radius: Int = 20): PointF {
        val x0 = p.x.toInt()
        val y0 = p.y.toInt()
        if (isWalkable(x0, y0)) return p

        // Simple spiral search around the point
        for (r in 1..radius) {
            for (dy in -r..r) {
                val y = (y0 + dy).coerceIn(0, bmpPlan.height - 1)
                val x1 = (x0 - r).coerceIn(0, bmpPlan.width - 1)
                val x2 = (x0 + r).coerceIn(0, bmpPlan.width - 1)
                if (isWalkable(x1, y)) return PointF(x1.toFloat(), y.toFloat())
                if (isWalkable(x2, y)) return PointF(x2.toFloat(), y.toFloat())
            }
            for (dx in -r..r) {
                val x = (x0 + dx).coerceIn(0, bmpPlan.width - 1)
                val y1 = (y0 - r).coerceIn(0, bmpPlan.height - 1)
                val y2 = (y0 + r).coerceIn(0, bmpPlan.height - 1)
                if (isWalkable(x, y1)) return PointF(x.toFloat(), y1.toFloat())
                if (isWalkable(x, y2)) return PointF(x.toFloat(), y2.toFloat())
            }
        }
        // Fallback: return original point
        return p
    }

    private fun computePathAsync() {
        val s0 = startPoint ?: return
        val e0 = endPoint ?: return
        val s = snapToWalkable(s0)
        val e = snapToWalkable(e0)

        pathExec.execute {
            try {
                // 1) A* path on walkable grid
                val raw = runAStar(s, e)
                if (raw.size < 2) {
                    Log.w(TAG, "A* returned empty/too short path")
                    runOnUiThread { Toast.makeText(this, "A* no path", Toast.LENGTH_LONG).show() }
                    return@execute
                }

                // 2) Optional smoothing
                val computed = smoothPath(raw)
                if (computed.size < 2) {
                    Log.w(TAG, "Smoothed path too short")
                    runOnUiThread { Toast.makeText(this, "Path invalid", Toast.LENGTH_LONG).show() }
                    return@execute
                }

                // 3) Initial heading
                val a0 = computed[0]
                val b0 = computed[1]
                val dx0 = (b0.x - a0.x)
                val dy0 = (b0.y - a0.y)
                val h0 = normalizeAngle(
                    Math.toDegrees(atan2(dx0.toDouble(), (-dy0).toDouble())).toFloat()
                )

                // 4) MATLAB logic: keep path length for navigation, estimate straight distance for steps
                val cd = buildCumDistLocal(computed)
                val totalPx = cd.last().coerceAtLeast(0f)
                val start = computed.first()
                val end = computed.last()
                val dx = end.x - start.x
                val dy = end.y - start.y
                val straightDistPx = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val distanceM = straightDistPx / PX_PER_M
                val steps = max(1, round(distanceM / STEP_LEN_M).toInt())

                // 5) Turn events
                val turns = computeTurnEventsByDistance(computed, cd)

                // 6) Publish navigation state
                pathResult = computed
                drPosPx = PointF(s.x, s.y)
                drHasPos = true

                heading0Abs = h0
                heading0Ready = true
                // On initialise yawFiltered des que possible a la direction du premier segment
                val yawAbsSmooth = getYawSmoothAbsDegOrNull()
                if (yawAbsSmooth != null) {
                    yawSmoothPrevDeg = yawAbsSmooth
                    yawAbsContDeg = yawAbsSmooth
                    yawOffsetContDeg = yawAbsContDeg - heading0Abs
                    yawOffset = normalizeAngle(yawAbsSmooth)
                    yawFiltered = heading0Abs
                    yawInit = true
                    lastYawTime = System.currentTimeMillis()
                    yawOffsetLockedToPath = true

                    Log.i(
                        TAG,
                        "YAW PRE-LOCK: yawAbs=%.1f heading0Abs=%.1f yawFiltered=%.1f"
                            .format(Locale.US, yawAbsSmooth, heading0Abs, yawFiltered)
                    )
                } else {
                    Log.w(TAG, "YAW PRE-LOCK skipped (no yawAbsSmooth)")
                }

                cumDistPx = cd
                totalDistPx = totalPx
                totalSteps = steps

                turnEvents = turns
                nextTurnIdx = 0
                turnLockActive = false
                lockOkCount = 0
                pendingStepsWhileLocked = 0

                // 7) Send to Matlab + UI
                sendPathToMatlab(computed)
                navigationActive = true
                navState = NavState.READY

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "A* ready: %.1fpx (%.1fm), steps=$totalSteps".format(
                            Locale.US,
                            straightDistPx,
                            distanceM
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                    draw()
                }

                Log.i(
                    TAG,
                    "A* PATH ready: n=${computed.size} straightPx=%.1f distanceM=%.2f totalSteps=%d turns=%d"
                        .format(Locale.US, straightDistPx, distanceM, totalSteps, turns.size)
                )

            } catch (t: Throwable) {
                Log.e(TAG, "computePath(A*) error", t)
                runOnUiThread { Toast.makeText(this, "Error path", Toast.LENGTH_LONG).show() }
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
            Sensor.TYPE_ROTATION_VECTOR -> {
                if (magDisturbed && gameRotationVector != null) return
                usingGameRV = false
                lastYawSource = "ROT"
                System.arraycopy(e.values, 0, rv, 0, min(e.values.size, rv.size))
                getYawFiltered()
                if (navigationActive && navState == NavState.RUNNING && !turnLockActive) {
                    processNavigationLogic(trigger = "RV")
                }

                if (drHasPos) {
                    requestDraw()
                }

                return
            }

            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                if (!magDisturbed && rotationVector != null) return
                usingGameRV = true
                lastYawSource = "GAME"
                System.arraycopy(e.values, 0, rv, 0, min(e.values.size, rv.size))
                getYawFiltered()
                if (navigationActive && navState == NavState.RUNNING && !turnLockActive) {
                    processNavigationLogic(trigger = "RV")
                }

                lastGameQuatX = e.values[0]
                lastGameQuatY = e.values[1]
                lastGameQuatZ = e.values[2]
                lastGameQuatW = if (e.values.size > 3) e.values[3] else 0f

                if (drHasPos) {
                    requestDraw()
                }

                return
            }

            Sensor.TYPE_GYROSCOPE -> {
                val gx = e.values[0]
                val gy = e.values[1]
                val gz = e.values[2]
                val gmag = sqrt(gx * gx + gy * gy + gz * gz)
                updateGyroStats(gmag)
                return
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                val mx = e.values[0]
                val my = e.values[1]
                val mz = e.values[2]
                val mag = sqrt(mx * mx + my * my + mz * mz)
                updateMagStats(mag, e.timestamp)
                lastMagX = mx
                lastMagY = my
                lastMagZ = mz
                lastMagNorm = mag
                return
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val ax = e.values[0]
                val ay = e.values[1]
                val az = e.values[2]
                val filtMag = updateAccelFilters(e.timestamp, ax, ay, az)
                if (!navigationActive) return

                if (detectStepFromAccel(filtMag, e.timestamp)) {
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
// Donc on utilise yawAbsContDeg (qui est le yaw absolu continu dÃ©jÃ  dÃ©duit)
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
                    // Si virage lockÃ© et pas validÃ©, on ne progresse pas sur la carte.
// On mÃ©morise juste les pas.
                    if (turnLockActive) {
                        pendingStepsWhileLocked++
                        udpSendLine("STEP,HOLD,$pendingStepsWhileLocked")
                        // On continue la logique pour guider le virage (mais distNowPx ne bouge pas)
                        processNavigationLogic(trigger = "STEP_HOLD")
                        // Optionnel: petit feedback
                        // vibrate(40)
                        draw()
                    } else {
                        // Si on vient de sortir dâ€™un lock, on applique les pas accumulÃ©s dâ€™un coup
                        if (pendingStepsWhileLocked > 0) {
                            applyPendingSteps()
                        }

                        sentStepCount++
                        val nowMs = System.currentTimeMillis()
                        udpSendLine("STEP_EVT,$sentStepCount,$nowMs")
                        updateDeadReckoning(getYawFiltered(), 1)
                        updateMapMatching()

                        udpSendLine("STEP,$sentStepCount")
                        processNavigationLogic(trigger = "STEP")
                        draw()
                    }

                }
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
        pushYawSample(yawRel)
        val yawVar = computeYawVariance()

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
            "DBG,YAW,yawRel=%.1f,segRel=%.1f,err=%.1f,dist=%.1f,ratio=%.3f,seg=%d,lock=%b,dir=%s,state=%s,src=%s,mag=%b,gyroVar=%.3f,drx=%.1f,dry=%.1f,proj=%.1f,lambda=%.2f"
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
                    navState.name,
                    lastYawSource,
                    magDisturbed,
                    gyroVar,
                    drPosPx.x,
                    drPosPx.y,
                    lastProjDistPx,
                    lastMapMatchLambda
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
        val turnState = if (turnLockActive) "LOCK_$turnLockDir" else "OK"
        logCsvLine(yawRel, yawVar, gyroVar, drPosPx, distNowPx, segIdx, turnState)
        val nextInfo = getNextTurnInfo()
        if (nextInfo == null) {
            lastTurnHintIdx = -1
            lastTurnHintStage = 0
        } else {
            if (lastTurnHintIdx != nextTurnIdx) {
                lastTurnHintIdx = nextTurnIdx
                lastTurnHintStage = 0
            }
            val dir = dirFr(nextInfo.dir)
            if (nextInfo.distM <= 6f && lastTurnHintStage < 1) {
                showTurnHint("Tournez a $dir dans %.1f m".format(Locale.US, nextInfo.distM))
                vibrate(60)
                lastTurnHintStage = 1
            }
            if (nextInfo.distM <= 1.2f && lastTurnHintStage < 2) {
                showTurnHint("Arrivee virage: $dir")
                vibrate(120)
                lastTurnHintStage = 2
            }
            if (nextInfo.distM <= 0.5f && lastTurnHintStage < 3) {
                showTurnHint("Tournez a $dir maintenant")
                vibrate(220)
                lastTurnHintStage = 3
            }
        }

        // 1) dÃ©clenchement lock virage par distance
        val turns = turnEvents
        if (!turnLockActive && nextTurnIdx < turns.size && sentStepCount >= minStepsBeforeFirstLock) {
            val ev = turns[nextTurnIdx]
            if (distNowPx >= (ev.atDistPx - lookAheadPx)) {
                turnLockActive = true
                turnLockDir = ev.dir
                turnLockTargetAbs = ev.newHeadingAbs
                lockOkCount = 0
                turnGyroSeen = false

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
            if (gyroVar >= turnGyroVarMin) {
                turnGyroSeen = true
            }
// si au moment oÃ¹ on a lockÃ©, on Ã©tait dÃ©jÃ  presque bon, on ne valide pas ce lock
            if (lockErrAtStartAbs < lockMinErrToRequireTurn) {
                // on continue dâ€™indiquer le virage mais on ne pourra pas faire OFF tout de suite
                // option 1: on annule le lock carrÃ©ment
                turnLockActive = false
                turnGyroSeen = false
                udpSendLine("TURN,OK")
                Log.i(TAG, "LOC CANCEL (too close at start) errStart=%.1f".format(Locale.US, lockErrAtStartAbs))
                return
            }
            if (!turnGyroSeen) {
                lockOkCount = 0
            } else if (abs(err) <= TURN_END) {
                lockOkCount++
            } else if (abs(err) <= TURN_END + 6f) {
                // petite sortie de zone: on dÃ©crÃ©mente au lieu de reset
                lockOkCount = max(0, lockOkCount - 1)
            } else {
                lockOkCount = 0
            }

            Log.i(TAG, "LOCKCHK err=%.1f okCount=$lockOkCount need=$lockOkNeeded".format(Locale.US, err))

            if (lockOkCount >= lockOkNeeded) {
                turnLockActive = false
                nextTurnIdx++
                lockOkCount = 0
                turnGyroSeen = false
                // On applique les pas accumulÃ©s pendant le virage
                if (pendingStepsWhileLocked > 0) {
                    applyPendingSteps()


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
        closeCsvLogger()
        vibrate(700)
        Log.i(TAG, "NAV COMPLETED")
        runOnUiThread { Toast.makeText(this, "You made it <3 !", Toast.LENGTH_LONG).show() }
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

        yawRawDeg0 = Math.toDegrees(ori[0].toDouble()).toFloat()
        val pitchDeg = Math.toDegrees(ori[1].toDouble()).toFloat()
        val rollDeg = Math.toDegrees(ori[2].toDouble()).toFloat()

        // Rejet si tÃ©lÃ©phone trop "vertical" -> yaw devient instable (marche/poches)
        // Ici on fait simple: si pitch trop proche de +/-90 (ou trop fort), on garde la derniÃ¨re valeur.
        if (abs(pitchDeg) > 70f) return yawFiltered

        var yawRawDeg = normalizeAngle(yawRawDeg0)
        if (INVERT_YAW) yawRawDeg = -yawRawDeg

        // Filtre circulaire (lissage sans problÃ¨me -180/180)
        val yawRawRad = Math.toRadians(yawRawDeg.toDouble()).toFloat()
        val s = sin(yawRawRad)
        val c = cos(yawRawRad)
        yawSinF = (1f - yawAlpha) * yawSinF + yawAlpha * s
        yawCosF = (1f - yawAlpha) * yawCosF + yawAlpha * c

        val yawSmoothRad = atan2(yawSinF, yawCosF)
        val yawSmoothDeg = normalizeAngle(Math.toDegrees(yawSmoothRad.toDouble()).toFloat())
        lastYawRawDeg = yawRawDeg
        lastYawSmoothDeg = yawSmoothDeg

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
            yawFiltered = 0f
            return yawFiltered
        }

// yaw relatif (continu) puis ramenÃ© dans [-180,180] pour comparaison/affichage
        val yawRelCont = (yawAbsContDeg - yawOffsetContDeg)
        val yawRelDeg = normalizeAngle(yawRelCont)

// ---- Limitation vitesse sur le RELATIF (Ã©vite sauts) ----
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
    private fun updateAccelFilters(tsNs: Long, ax: Float, ay: Float, az: Float): Float {
        val mag = sqrt(ax * ax + ay * ay + az * az)

        if (lastAccelTsNs != 0L) {
            val dt = (tsNs - lastAccelTsNs) * 1e-9f
            if (dt > 0f) {
                val fs = (1f / dt).coerceIn(20f, 100f)
                if (!accelFilter.initialized || abs(fs - accelFs) / accelFs > 0.1f) {
                    accelFs = fs
                    accelFilter.update(accelFs, stepBandLowHz, stepBandHighHz)
                    accelFilterX.update(accelFs, stepBandLowHz, stepBandHighHz)
                    accelFilterY.update(accelFs, stepBandLowHz, stepBandHighHz)
                    accelFilterZ.update(accelFs, stepBandLowHz, stepBandHighHz)
                }
            }
        } else if (!accelFilter.initialized) {
            accelFilter.update(accelFs, stepBandLowHz, stepBandHighHz)
            accelFilterX.update(accelFs, stepBandLowHz, stepBandHighHz)
            accelFilterY.update(accelFs, stepBandLowHz, stepBandHighHz)
            accelFilterZ.update(accelFs, stepBandLowHz, stepBandHighHz)
        }
        lastAccelTsNs = tsNs

        if (!accelFilterX.initialized) {
            accelFilterX.update(accelFs, stepBandLowHz, stepBandHighHz)
            accelFilterY.update(accelFs, stepBandLowHz, stepBandHighHz)
            accelFilterZ.update(accelFs, stepBandLowHz, stepBandHighHz)
        }

        val filtMag = accelFilter.process(mag)
        lastAccelX = ax
        lastAccelY = ay
        lastAccelZ = az
        lastAccelXf = accelFilterX.process(ax)
        lastAccelYf = accelFilterY.process(ay)
        lastAccelZf = accelFilterZ.process(az)
        lastAccelMagFilt = filtMag
        return filtMag
    }

    private fun detectStepFromAccel(filt: Float, tsNs: Long): Boolean {
        val nowMs = tsNs / 1_000_000L
        val absFilt = abs(filt)
        val (meanAbs, stdAbs) = updateAccelStats(absFilt)
        val thresh = meanAbs + stepThreshK * stdAbs

        val deriv = filt - lastFilt
        val lastAbs = abs(lastFilt)
        val z = if (stdAbs > 1e-6f) (lastAbs - meanAbs) / stdAbs else 0f
        val prominenceOk = (lastAbs - meanAbs) >= stepProminenceMin
        val zOk = z >= stepZScoreMin
        var stepDetected = false

        if (accelWinCount < 10) {
            lastDeriv = deriv
            lastFilt = filt
            return false
        }

        if (!waitingZeroCross) {

            val peakOk = abs(lastFilt) > thresh && (prominenceOk || zOk)
            if (lastDeriv > 0f && deriv <= 0f && peakOk && nowMs - lastStepTimeMs > minStepDelay.toLong()) {
                waitingZeroCross = true
                peakTimeMs = nowMs
                // debbug pour les pas perdu
                udpSendLine("DBGSTEP,PEAK,f=%.4f,th=%.4f,mu=%.4f,sd=%.4f,z=%.2f,prom=%b".format(Locale.US,lastFilt,thresh,meanAbs,stdAbs,z, prominenceOk))
            }
        } else {
            val eps=0.01f
            if (abs(filt) <= eps ||(lastFilt>0 && filt <0)||(lastFilt < 0 && filt > 0)) {

                val gyroOk = gyroWinCount == 0 || gyroVar >= minGyroVar
                if (gyroOk && nowMs - lastStepTimeMs > minStepDelay.toLong()) {
                    stepDetected = true
                    lastStepTimeMs = nowMs
                    if (DBG) {
                        Log.w(
                            TAG,
                            "STEPDBG filt=%.5f thresh=%.5f deriv=%.5f".format(Locale.US, filt, thresh, deriv)
                        )
                    }
                }
                waitingZeroCross = false
            } else if (nowMs - peakTimeMs > stepZeroCrossTimeoutMs) {
                udpSendLine("DBGSTEP,TIMEOUT,f=%.4f".format(Locale.US, filt))
                waitingZeroCross = false
            }
        }

        lastDeriv = deriv
        lastFilt = filt
        return stepDetected
    }

    private fun updateAccelStats(valueAbs: Float): Pair<Float, Float> {
        val old = if (accelWinCount < accelStatsWindow) 0f else accelWin[accelWinIdx]
        if (accelWinCount < accelStatsWindow) accelWinCount++
        accelSum += valueAbs - old
        accelSumSq += valueAbs * valueAbs - old * old
        accelWin[accelWinIdx] = valueAbs
        accelWinIdx = (accelWinIdx + 1) % accelStatsWindow

        val mean = if (accelWinCount > 0) accelSum / accelWinCount else 0f
        val varAbs = if (accelWinCount > 0) accelSumSq / accelWinCount - mean * mean else 0f
        val std = sqrt(max(0f, varAbs))
        return mean to std
    }

    private fun updateGyroStats(gmag: Float) {
        val old = if (gyroWinCount < gyroStatsWindow) 0f else gyroWin[gyroWinIdx]
        if (gyroWinCount < gyroStatsWindow) gyroWinCount++
        gyroSum += gmag - old
        gyroSumSq += gmag * gmag - old * old
        gyroWin[gyroWinIdx] = gmag
        gyroWinIdx = (gyroWinIdx + 1) % gyroStatsWindow

        val mean = if (gyroWinCount > 0) gyroSum / gyroWinCount else 0f
        gyroVar = if (gyroWinCount > 0) gyroSumSq / gyroWinCount - mean * mean else 0f
    }

    private fun updateMagStats(mag: Float, tsNs: Long) {
        if (magMean <= 0f) {
            magMean = mag
            lastMag = mag
            lastMagTimeNs = tsNs
            return
        }

        magMean = 0.98f * magMean + 0.02f * mag
        val dev = abs(mag - magMean) / magMean

        val absBad = (mag < magAbsMin || mag > magAbsMax)
        var derivBad = false
        if (lastMagTimeNs > 0L) {
            val dt = (tsNs - lastMagTimeNs) * 1e-9f
            if (dt > 0f) {
                magDeriv = abs(mag - lastMag) / dt
                derivBad = magDeriv > magDerivThreshold
            }
        }
        lastMag = mag
        lastMagTimeNs = tsNs

        val relBad = dev > magAnomalyThreshold
        val anyBad = absBad || derivBad || relBad
        if (anyBad) {
            magAnomalyCount++
            magRecoverCount = 0
        } else {
            magRecoverCount++
            magAnomalyCount = 0
        }
        if (!magDisturbed && magAnomalyCount >= magAnomalyCountNeeded) {
            magDisturbed = true
        }
        if (magDisturbed && magRecoverCount >= magRecoverCountNeeded) {
            magDisturbed = false
        }
    }

    private fun updateDeadReckoning(yawRel: Float, steps: Int) {
        if (!drHasPos) {
            val s = startPoint ?: return
            drPosPx = PointF(s.x, s.y)
            drHasPos = true
        }
        val stepPx = STEP_LEN_PX * steps
        val rad = Math.toRadians(yawRel.toDouble())
        val dx = (stepPx * sin(rad)).toFloat()
        val dy = -(stepPx * cos(rad)).toFloat()
        drPosPx.offset(dx, dy)


    }

    private fun updateMapMatching() {
        val total = totalDistPx
        if (total <= 0f) return
        if (!drHasPos) return

        if (!USE_MAP_MATCHING) {
            distNowPx = min(total, sentStepCount * STEP_LEN_PX)
            ratioNow = (distNowPx / total).coerceIn(0f, 1f)
            return
        }

        val proj = projectOnPath(drPosPx) ?: return
        lastProjDistPx = proj.dist
        val yawVar = computeYawVariance()
        val lambda = computeMapMatchLambda(yawVar)
        lastMapMatchLambda = lambda
        if (proj.dist > mapMatchThresholdPx) {
            drPosPx = PointF(
                lambda * drPosPx.x + (1f - lambda) * proj.point.x,
                lambda * drPosPx.y + (1f - lambda) * proj.point.y
            )
        }
        distNowPx = proj.alongDist.coerceIn(0f, total)
        ratioNow = (distNowPx / total).coerceIn(0f, 1f)
    }

    private fun applyPendingSteps() {
        if (pendingStepsWhileLocked <= 0) return
        sentStepCount += pendingStepsWhileLocked
        updateDeadReckoning(getYawFiltered(), pendingStepsWhileLocked)
        updateMapMatching()
        pendingStepsWhileLocked = 0
    }

    private data class PathProjection(val point: PointF, val dist: Float, val alongDist: Float)

    private fun projectOnPath(pos: PointF): PathProjection? {
        val path = pathResult
        val cd = cumDistPx
        if (path.size < 2 || cd.size != path.size) return null

        var bestDist = Float.MAX_VALUE
        var bestAlong = 0f
        var bestPoint = PointF()

        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val vx = b.x - a.x
            val vy = b.y - a.y
            val len2 = vx * vx + vy * vy
            if (len2 <= 1e-6f) continue

            val t = ((pos.x - a.x) * vx + (pos.y - a.y) * vy) / len2
            val tc = t.coerceIn(0f, 1f)
            val px = a.x + tc * vx
            val py = a.y + tc * vy
            val d = hypot(pos.x - px, pos.y - py)

            if (d < bestDist) {
                bestDist = d
                bestPoint = PointF(px, py)
                bestAlong = cd[i] + tc * sqrt(len2)
            }
        }
        return PathProjection(bestPoint, bestDist, bestAlong)
    }

    private fun pushYawSample(yawRel: Float) {
        val old = if (yawWindowCount < yawVarWindow) 0f else yawWindow[yawWindowIdx]
        if (yawWindowCount < yawVarWindow) yawWindowCount++
        yawWindowSum += yawRel - old
        yawWindowSumSq += yawRel * yawRel - old * old
        yawWindow[yawWindowIdx] = yawRel
        yawWindowIdx = (yawWindowIdx + 1) % yawVarWindow
    }

    private fun computeYawVariance(): Float {
        if (yawWindowCount == 0) return 0f
        val mean = yawWindowSum / yawWindowCount
        return max(0f, yawWindowSumSq / yawWindowCount - mean * mean)
    }

    private fun computeMapMatchLambda(yawVar: Float): Float {
        if (yawWindowCount == 0) return mapMatchLambdaBase
        val yawStd = sqrt(max(0f, yawVar))
        val norm = (yawStd / 45f).coerceIn(0f, 1f)
        return (0.8f - 0.3f * norm).coerceIn(0.6f, 0.8f)
    }

    private class BiquadBandpass {
        private var b0 = 0f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var z1 = 0f
        private var z2 = 0f
        var initialized = false
            private set

        fun update(fs: Float, f1: Float, f2: Float) {
            val f0 = sqrt(f1 * f2)
            val q = (f0 / (f2 - f1)).coerceAtLeast(0.1f)
            val w0 = (2.0 * Math.PI * f0 / fs).toFloat()
            val cosw0 = cos(w0.toDouble()).toFloat()
            val sinw0 = sin(w0.toDouble()).toFloat()
            val alpha = sinw0 / (2f * q)

            var nb0 = sinw0 / 2f / q
            var nb1 = 0f
            var nb2 = -sinw0 / 2f / q
            val na0 = 1f + alpha
            var na1 = -2f * cosw0
            var na2 = 1f - alpha

            nb0 /= na0
            nb1 /= na0
            nb2 /= na0
            na1 /= na0
            na2 /= na0

            b0 = nb0
            b1 = nb1
            b2 = nb2
            a1 = na1
            a2 = na2
            z1 = 0f
            z2 = 0f
            initialized = true
        }

        fun process(x: Float): Float {
            if (!initialized) return x
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        fun reset() {
            z1 = 0f
            z2 = 0f
            initialized = false
        }
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
    private fun angleToTarget(from: PointF, to: PointF): Float {
        val dx = (to.x - from.x)
        val dy = (to.y - from.y)
        return normalizeAngle(
            Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
        )
    }

    private fun drawStartMarker(c: Canvas, p: PointF, angleDeg: Float?) {
        val s = 16f
        val path = Path().apply {
            moveTo(p.x, p.y - s)
            lineTo(p.x - s, p.y + s)
            lineTo(p.x + s, p.y + s)
            close()
        }
        c.save()
        c.translate(2f, 2f)
        if (angleDeg != null) {
            c.rotate(angleDeg, p.x, p.y)
        }
        c.drawPath(path, paintMarkerShadow)
        c.restore()
        c.save()
        if (angleDeg != null) {
            c.rotate(angleDeg, p.x, p.y)
        }
        c.drawPath(path, paintMarkerOutline)
        c.drawPath(path, paintStartMarker)
        c.restore()
    }

    private fun drawEndMarker(c: Canvas, p: PointF) {
        val s = 16f
        c.drawRect(p.x - s + 2f, p.y - s + 2f, p.x + s + 2f, p.y + s + 2f, paintMarkerShadow)
        c.drawRect(p.x - s, p.y - s, p.x + s, p.y + s, paintMarkerOutline)
        c.drawRect(p.x - s, p.y - s, p.x + s, p.y + s, paintEndMarker)
    }

    private fun drawUserArrow(c: Canvas, p: PointF, yawDeg: Float) {
        val size = 18f
        val path = Path().apply {
            moveTo(0f, -size)
            lineTo(-size * 0.7f, size)
            lineTo(0f, size * 0.4f)
            lineTo(size * 0.7f, size)
            close()
        }
        c.save()
        c.translate(p.x, p.y)
        c.rotate(yawDeg)
        c.drawPath(path, paintMarkerShadow)
        c.drawPath(path, paintUserArrowOutline)
        c.drawPath(path, paintUserArrow)
        c.drawCircle(0f, 0f, 5f, paintUserDot)
        c.drawCircle(0f, 0f, 7f, paintUserRing)
        c.restore()

        val phase = (System.currentTimeMillis() % 1500L) / 1500f
        val pulseR = 12f + 10f * phase
        paintPulse.alpha = (120 * (1f - phase)).toInt().coerceIn(0, 120)
        c.drawCircle(p.x, p.y, pulseR, paintPulse)
    }

    private fun drawStatusOverlay(c: Canvas) {
        val lines = mutableListOf<String>()
        lines.add("Etat: ${navState.name}")
        if (navState == NavState.RUNNING) {
            lines.add("Pas: $sentStepCount/$totalSteps")
            lines.add("Yaw: %.0f deg".format(Locale.US, yawFiltered))
            val info = getNextTurnInfo()
            if (info != null) {
                val distM = info.distM.coerceAtLeast(0f)
                lines.add("Prochain: ${dirFr(info.dir)} %.1f m".format(Locale.US, distM))
            }
            if (turnLockActive) {
                lines.add("Virage: $turnLockDir (lock)")
            }
        }
        if (magDisturbed) {
            lines.add("Mag: disturbed")
        }

        val pad = 10f
        val lineH = paintUiText.textSize + 6f
        var maxW = 0f
        for (s in lines) {
            maxW = max(maxW, paintUiText.measureText(s))
        }
        val x = 12f
        val y = 12f
        val rect = RectF(x, y, x + maxW + pad * 2, y + lineH * lines.size + pad * 2)
        c.drawRoundRect(rect, 10f, 10f, paintUiBg)
        var ty = y + pad + paintUiText.textSize
        for (s in lines) {
            c.drawText(s, x + pad, ty, paintUiText)
            ty += lineH
        }
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

        // anti-virages collÃ©s: min 2 pas
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

    private fun getNextTurnInfo(): TurnInfo? {
        val turns = turnEvents
        if (nextTurnIdx >= turns.size) return null
        val distM = (turns[nextTurnIdx].atDistPx - distNowPx) / PX_PER_M
        return TurnInfo(turns[nextTurnIdx].dir, distM)
    }

    private fun dirFr(dir: String): String {
        return if (dir == "RIGHT") "droite" else "gauche"
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

    private fun udpSendBatch(lines: List<String>) {
        if (lines.isEmpty()) return
        val sock = socketSend ?: return
        udpExec.execute {
            try {
                val payload = lines.joinToString(separator = "\n", postfix = "\n")
                val data = payload.toByteArray()
                val p = DatagramPacket(data, data.size, InetAddress.getByName(matlabIP), portSend)
                sock.send(p)
            } catch (t: Throwable) {
                Log.e(TAG, "udpSendBatch error", t)
            }
        }
    }

    private fun sendFrameToMatlab() {
        getYawFiltered()
        val lines = ArrayList<String>(7)
        lines.add(String.format(Locale.US, "CTRL,YAW,%.1f,%.1f", lastYawRawDeg, lastYawSmoothDeg))
        lines.add(
            String.format(
                Locale.US,
                "CTRL,ACC,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f",
                lastAccelX,
                lastAccelY,
                lastAccelZ,
                lastAccelXf,
                lastAccelYf,
                lastAccelZf
            )
        )
        lines.add(
            String.format(
                Locale.US,
                "CTRL,MAG,%.3f,%.3f,%.3f,%.3f,%.3f,%d",
                lastMagX,
                lastMagY,
                lastMagZ,
                lastMagNorm,
                magDeriv,
                if (magDisturbed) 1 else 0
            )
        )
        lines.add(
            String.format(
                Locale.US,
                "CTRL,ROT,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",
                R[0],
                R[1],
                R[2],
                R[3],
                R[4],
                R[5],
                R[6],
                R[7],
                R[8]
            )
        )
        lines.add(
            String.format(
                Locale.US,
                "CTRL,GAME,%.6f,%.6f,%.6f,%.6f",
                lastGameQuatX,
                lastGameQuatY,
                lastGameQuatZ,
                lastGameQuatW
            )
        )
        lines.add("CTRL,STEP,$sentStepCount")
        if (sendFrameTimestamp) {
            val nowMs = System.currentTimeMillis()
            lines.add("CTRL,TIME,$nowMs")
        }
        udpSendBatch(lines)
    }


    private fun sendPathToMatlab(path: List<PointF>) {
        // construit message une fois (Ã©vite concat dans thread)
        val sb = StringBuilder("PATH")
        path.forEach { sb.append(";${it.x},${it.y}") }

        udpSendLine(sb.toString())
        udpSendLine("CTRL,PARAM,PXPERM,%.2f".format(Locale.US, PX_PER_M))
        udpSendLine("CTRL,PARAM,STEPLEN,%.2f".format(Locale.US, STEP_LEN_M))
        udpSendLine("CTRL,PARAM,HEADING0ABS,%.1f".format(Locale.US, heading0Abs))
        udpSendLine("CTRL,PARAM,TOTALSTEPS,$totalSteps")

        Log.i(TAG, "Sent PATH + PARAMS to Matlab")
    }

    private fun logCsvLine(
        yawRel: Float,
        yawVar: Float,
        gyroVar: Float,
        drPos: PointF,
        distPx: Float,
        segIdx: Int,
        turnState: String
    ) {
        if (!ENABLE_CSV_LOG) return
        try {
            csvExec.execute {
                try {
                    if (csvWriter == null) {
                        val file = File(filesDir, "nav_${System.currentTimeMillis()}.csv")
                        csvWriter = BufferedWriter(FileWriter(file, false))
                        csvWriter?.write("timestampMs,yawRel,yawVar,gyroVar,drX,drY,distPx,segIdx,turnState\n")
                        csvWriter?.flush()
                        csvLineCount = 0
                        Log.i(TAG, "CSV log: ${file.absolutePath}")
                    }
                    val ts = System.currentTimeMillis()
                    val line = String.format(
                        Locale.US,
                        "%d,%.1f,%.3f,%.3f,%.2f,%.2f,%.2f,%d,%s\n",
                        ts,
                        yawRel,
                        yawVar,
                        gyroVar,
                        drPos.x,
                        drPos.y,
                        distPx,
                        segIdx,
                        turnState
                    )
                    csvWriter?.write(line)
                    csvLineCount++
                    if (csvLineCount % 20 == 0) {
                        csvWriter?.flush()
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "csv log error", t)
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun closeCsvLogger() {
        if (!ENABLE_CSV_LOG) return
        try {
            csvExec.execute {
                try {
                    csvWriter?.flush()
                    csvWriter?.close()
                } catch (_: Exception) {
                } finally {
                    csvWriter = null
                    csvLineCount = 0
                }
            }
        } catch (_: RejectedExecutionException) {
            try {
                csvWriter?.close()
            } catch (_: Exception) {
            } finally {
                csvWriter = null
                csvLineCount = 0
            }
        }
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
                    // socket fermÃ©
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

    private fun initImageMatrixIfNeeded() {
        if (matrixReady || !mapReady) return
        val ivW = imageView.width.toFloat()
        val ivH = imageView.height.toFloat()
        val bW = bmpPlan.width.toFloat()
        val bH = bmpPlan.height.toFloat()
        if (ivW <= 0f || ivH <= 0f || bW <= 0f || bH <= 0f) return

        baseScale = min(ivW / bW, ivH / bH).coerceAtLeast(1e-6f)
        minScale = baseScale
        maxScale = baseScale * 4f

        imageMatrixCurrent.reset()
        imageMatrixCurrent.postScale(baseScale, baseScale)
        val tx = (ivW - bW * baseScale) / 2f
        val ty = (ivH - bH * baseScale) / 2f
        imageMatrixCurrent.postTranslate(tx, ty)
        imageView.imageMatrix = imageMatrixCurrent
        matrixReady = true
    }

    private fun constrainImageMatrix() {
        if (!matrixReady || !mapReady) return
        val values = FloatArray(9)
        imageMatrixCurrent.getValues(values)
        var scale = values[Matrix.MSCALE_X]
        val viewW = imageView.width.toFloat()
        val viewH = imageView.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        if (scale < minScale) {
            val factor = minScale / scale
            imageMatrixCurrent.postScale(factor, factor, viewW / 2f, viewH / 2f)
            scale = minScale
        } else if (scale > maxScale) {
            val factor = maxScale / scale
            imageMatrixCurrent.postScale(factor, factor, viewW / 2f, viewH / 2f)
            scale = maxScale
        }

        imageMatrixCurrent.getValues(values)
        val scaledW = bmpPlan.width * scale
        val scaledH = bmpPlan.height * scale
        val minTx = if (scaledW <= viewW) (viewW - scaledW) / 2f else viewW - scaledW
        val maxTx = if (scaledW <= viewW) (viewW - scaledW) / 2f else 0f
        val minTy = if (scaledH <= viewH) (viewH - scaledH) / 2f else viewH - scaledH
        val maxTy = if (scaledH <= viewH) (viewH - scaledH) / 2f else 0f
        val tx = values[Matrix.MTRANS_X].coerceIn(minTx, maxTx)
        val ty = values[Matrix.MTRANS_Y].coerceIn(minTy, maxTy)
        val dx = tx - values[Matrix.MTRANS_X]
        val dy = ty - values[Matrix.MTRANS_Y]
        if (dx != 0f || dy != 0f) {
            imageMatrixCurrent.postTranslate(dx, dy)
        }
    }

    // =================== DRAW (ANTI-OOM, stable) ===================
    private fun draw() {
        if (!mapReady) return
        initImageMatrixIfNeeded()

        // Clear overlay
        overlayCanvas.drawRect(0f, 0f, bmpOverlay.width.toFloat(), bmpOverlay.height.toFloat(), paintClear)

        // points
        val end = endPoint
        startPoint?.let { sp ->
            val angleToEnd = end?.let { angleToTarget(sp, it) }
            drawStartMarker(overlayCanvas, sp, angleToEnd)
        }
        end?.let { drawEndMarker(overlayCanvas, it) }

        // path + progress
        val path = pathResult
        val cd = cumDistPx

        if (path.size >= 2) {
            for (i in 0 until path.size - 1) {
                overlayCanvas.drawLine(path[i].x, path[i].y, path[i + 1].x, path[i + 1].y, paintPathOutline)
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
                            paintPathOutline
                        )
                        val grad = LinearGradient(
                            path[i].x, path[i].y,
                            path[i + 1].x, path[i + 1].y,
                            Color.rgb(0, 122, 255),
                            Color.rgb(48, 209, 197),
                            Shader.TileMode.CLAMP
                        )
                        paintProg.shader = grad
                        overlayCanvas.drawLine(
                            path[i].x, path[i].y,
                            path[i + 1].x, path[i + 1].y,
                            paintProg
                        )
                        paintProg.shader = null
                    }
                }
            }
        }

        // Compose base + overlay (une seule copie lÃ©gÃ¨re)
        if (drHasPos) {
            drawUserArrow(overlayCanvas, drPosPx, yawFiltered)
        }
    composedCanvas.drawBitmap(bmpBase,0f,0f,null)
        composedCanvas.drawBitmap(bmpOverlay,0f,0f, null )
        imageView.setImageBitmap(bmpComposed)
        imageView.imageMatrix = imageMatrixCurrent
    }

    private fun requestDraw() {
        val now = System.currentTimeMillis()
        if (now - lastDrawMs < minDrawIntervalMs) return
        lastDrawMs = now
        imageView.post { draw() }
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
        val bW = bmpPlan.width.toFloat()
        val bH = bmpPlan.height.toFloat()
        if (!matrixReady) {
            val ivW = imageView.width.toFloat()
            val ivH = imageView.height.toFloat()
            val s = min(ivW / bW, ivH / bH).coerceAtLeast(1e-6f)
            val ox = (ivW - bW * s) / 2
            val oy = (ivH - bH * s) / 2
            return PointF(
                ((x - ox) / s).coerceIn(0f, bW - 1),
                ((y - oy) / s).coerceIn(0f, bH - 1)
            )
        }

        val inv = Matrix()
        imageMatrixCurrent.invert(inv)
        val pts = floatArrayOf(x, y)
        inv.mapPoints(pts)
        return PointF(
            pts[0].coerceIn(0f, bW - 1),
            pts[1].coerceIn(0f, bH - 1)
        )
    }
}
