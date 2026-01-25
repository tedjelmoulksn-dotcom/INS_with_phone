package com.example.sensortomatlab

import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.*
import android.os.*
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

    companion object {
        // Constantes rapides / tolerantes pour lock gyro
        private const val GYRO_RATE_MIN = 0.10f        // rad/s ~ 5.7deg/s
        private const val GYRO_HOLD_MS = 140L          // 140ms bon sens => OK
        private const val GYRO_TURN_DEG_MIN = 18f      // ~18deg suffit pour dire "il a tourne"
        private const val TURN_MIN_LOCK_MS = 120L      // evite release instantane par bruit
        private const val TURN_MAX_LOCK_MS = 2000L     // securite
    }

    // =================== RECORDING / APP STATE ===================
    // Pas detectes seulement quand on "enregistre" (calib) ou quand la nav est RUNNING.
    @Volatile private var stepDetectionEnabled = false
    private val showToasts = false

    // =================== USER PARAMS ===================
    private val PX_PER_M = 30f       // calibration px/m 31.06
    private val STEP_LEN_M = 0.65f     // longueur de pas moyenne
    // STEP_LEN_PX supprimé : on utilise toujours stepLenPxNow() (basé sur l'étalonnage user)
    private fun stepLenPxNow(): Float = PX_PER_M * stepLenMUser
    private fun stepLenMdynNow(): Float {
        val ratioT = (stepRefPeriodMsUser / stepPeriodMs).toDouble()
        val cadenceFactor = ratioT.pow(0.20)

        val ampRatio = (lastStepAmpAbs / stepAmpRefUser).toDouble().coerceIn(0.6, 1.6)
        val ampFactor = ampRatio.pow(0.25)

        val stepLen = stepRefLenMUser * cadenceFactor * ampFactor
        return stepLen.toFloat().coerceIn(stepLenMinM, stepLenMaxM)
    }
    private fun stepLenPxDynNow(): Float = PX_PER_M * stepLenMdynNow()
    private fun stepLenPxNav(): Float = PX_PER_M * stepLenNavM
    private fun cadenceSpmNow(): Float = (60000f / stepPeriodMs).coerceIn(40f, 260f)
    private fun cadenceSpmInstant(): Float =
        if (dtStepMs > 0) (60000f / dtStepMs.toFloat()).coerceIn(40f, 260f) else cadenceSpmNow()

    private fun showToast(message: String, length: Int = Toast.LENGTH_SHORT) {
        if (!showToasts) return
        runOnUiThread { Toast.makeText(this, message, length).show() }
    }

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

    private lateinit var calibButton: Button
    private lateinit var calibStopButton: Button
    private lateinit var calibRestartButton: Button
    private lateinit var navControls: LinearLayout
    private lateinit var navPanelScaleDetector: ScaleGestureDetector
    private var navPanelScaling = false
    private var navPanelDragging = false
    private var navPanelDownMs = 0L
    private var navPanelLastRawX = 0f
    private var navPanelLastRawY = 0f
    private var navPanelScale = 1f
    private val navPanelScaleMin = 0.6f
    private val navPanelScaleMax = 2.2f
    private val navPanelStrokeIdle = Color.argb(60, 255, 255, 255)
    private val navPanelStrokeActive = Color.argb(200, 255, 255, 255)
    private val navPanelBgIdle = Color.argb(180, 20, 24, 30)
    private val navPanelBgActive = Color.argb(220, 235, 240, 245)
    private lateinit var navPanelBg: GradientDrawable
    private lateinit var pickStartButton: Button
    private lateinit var pickEndButton: Button
    private lateinit var confirmButton: Button
    private lateinit var restartButton: Button
    private lateinit var pauseResumeButton: Button
    private lateinit var navHintText: TextView
    private lateinit var introPanel: FrameLayout
    private lateinit var introTitle: TextView
    private lateinit var introBody: TextView
    private lateinit var introStats: TextView
    private var introVisible = true

    private enum class SelectMode { NONE, PICK_START, PICK_END }
    private var selectMode = SelectMode.NONE
    private var startConfirmed = false
    private var endConfirmed = false

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
    // Marche lente: 0.7 Hz coupe parfois trop -> on descend à 0.5 Hz
    private val stepBandLowHz = 0.6f
    private val stepBandHighHz = 3.0f
    // Un peu moins agressif pour ne pas rater les pas lents
    private val stepThreshK = 0.18f          // un peu moins sensible
    private val stepProminenceMin = 0.055f   // petits pas
    private val minStepDelay = 360           // evite double comptage (marche normale)
    private val accelStatsWindow = 120
    private val gyroStatsWindow = 40
    private val minGyroVar = 0.02f
    // Si tu veux garder une sécurité gyro: on ne rejette que si mouvement très violent
    private val gyroVarReject = 0.90f // a 0.60 cetait pas mal aussi

    // Track du pic courant (pour valider même sans vrai zero-cross)
    private var peakAbsHold = 0f
    private var peakSignHold = 1
    private var prevFilt = 0f

    // =================== STEP TIMING (NEW) ===================
    // --- STEP state (important: update only on accepted step) ---
    private var stepPeriodMs = 620f
    private var lastAcceptedStepMs = 0L
    private var dtStepMs = 0L
    private val stepPeriodAlpha = 0.15f
    private val stepPeriodMinMs = 250f
    private val stepPeriodMaxMs = 2600f
    // Marche lente: minDelay en fraction un peu plus bas (sinon cercle vicieux si T dérive)
    private val minDelayFrac = 0.22f
    // Marche lente: la “retombée/validation” peut être plus longue
    private val zeroCrossFrac = 1.30f
    private val stepLenAlpha = 0.40f
    private val stepRefPeriodMs = 550f
    private val stepRefLenM = STEP_LEN_M
    private val stepLenMinM = 0.35f
    private val stepLenMaxM = 0.95f
    private var lastStepAmpAbs = 0.12f
    private val calibStepAmps = ArrayList<Float>(64)
    private var stepAmpRefUser = 0.12f

    // =================== CALIBRATION (NEW) ===================
    private var calibActive = false
    private var calibWaitingStart = true
    private var calibStepCount = 0
    private val calibStepTimes = ArrayList<Long>(64)
    private var calibLastPeakMs = 0L
    private var calibPeakArmed = true
    private val calibStepThreshK = 0.75f
    private val calibStepZMin = 0.75f
    private val calibThreshMin = 0.5f         // post-filtrage
    private val calibMinStd = 0.03f
    private val calibMinPeakIntervalMs =10L
    private val calibMaxRefPeriodMs = 1600f
    // --- CALIB SIMPLE PEAK LOGIC (NEW) ---
    private var calibPerturbations = 0
    private val calibAmpThresh = 1.2f          // amplitude fixe (post-filtrage)
    private val calibAmpMax =5f             // amplitude max pour un pas
    private val calibCandidateMinAmp = 1.2f    // candidats seulement au-dessus de 1.2
    private val calibPertMinAmp = 1.7f         // perturbations comptées seulement >= 1.7
    private val calibMinDtMs = 450L            // bruit si < 0.45 s
    private val calibMaxDtMs = 3200L           // cadence lente
    private val calibRearmRatio = 0.50f        // rearm quand on redescend sous 50% du seuil
    private var calibLastDbgMs = 0L
    private var calibPosLobeStartMs = 0L
    private var calibHasPeakCandidate = false
    private var calibPeakCandidateAmp = 0f
    private var calibPeakCandidateTimeMs = 0L
    private var calibPeakCandidateDtMs = 0L
    private var calibLastAnyPeakMs = 0L
    private val calibPeakWidthRejectMs = 120L  // bruit si pic < 0.12 s
    private val calibPeakWidthAcceptMs = 120L  // pas si pic >= 0.12 s
    private val calibPeakWidthMaxMs = 400L     // bruit si pic trop large
    // Nav: detection de pas identique a l'etalonnage (pics)
    private var navPeakArmed = true
    private var navPosLobeStartMs = 0L
    private var navHasPeakCandidate = false
    private var navPeakCandidateAmp = 0f
    private var navPeakCandidateTimeMs = 0L
    private var navPeakCandidateDtMs = 0L
    private var navLastAnyPeakMs = 0L
    private var stepRefPeriodMsUser = 600f
    private var stepRefLenMUser = STEP_LEN_M
    private var stepLenMUser = STEP_LEN_M
    private var stepLenNavM = STEP_LEN_M
    private val PREFS_NAME = "calibration_prefs"
    private val PREF_STEP_LEN_M = "pref_step_len_m"
    private val PREF_STEP_REF_PERIOD_MS = "pref_step_ref_period_ms"
    private val PREF_STEP_AMP_REF = "pref_step_amp_ref"
    private val STEP_LEN_M_DEFAULT = STEP_LEN_M

    // =================== CALIBRATION DISTANCE ===================
    private val CALIB_DISTANCE_M = 5.0f
    // =================== MAP PRELOAD ===================
    @Volatile private var mapPreloadStarted = false

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
    private var navPaused = false
    @Volatile private var navigationActive = false

    // position estime sur la carte
    @Volatile private var distNowPx = 0f
    @Volatile private var ratioNow = 0f
    @Volatile private var distAlongPx = 0f

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

      //  cd.size != path.size : si les distances cumulées ne correspondent pas au path ? incohérent.

        //totalDistPx <= 0f : distance totale invalide (path vide ou bug).
        // Si une condition est vraie : on ne peut pas calibrer ? false.
        val maxSeg = path.size - 2
        val segIdx = findSegmentIndexByDistance(cd, distNowPx).coerceIn(0, maxSeg)
     //   Un path de N points a N-1 segments : segment 0 = (0?1), segment 1 = (1?2), etc.

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

        Math.toDegrees(...) convertit radians ? degrés.

        normalizeAngle(...) remet l’angle dans une plage standard (souvent [0,360) ou (-180,180] selon l’implémentation).
        Sans ça, tu peux avoir des valeurs négatives ou >360.*/
        val segRel = absToRelMap(segAbs)
       /* Convertit segAbs (angle “absolu” dans un repère global / écran / monde) en segRel (angle “relatif” dans ton repère yaw / UI / boussole interne).

        Typiquement ça sert à passer d’une convention d’angle à une autre (ex: 0° = nord vs 0° = est, sens horaire vs anti-horaire, etc.)*/

        val yawAbsSmooth = getYawSmoothAbsDegOrNull() ?: return false
        /*Récupère le yaw du téléphone lissé/filtré, en degrés, absolu.
        Si pas dispo (null) ? impossible de calibrer ? false.*/

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

    // Lock seulement tres proche du point de virage (pas a 1m avant)
    private val turnLockLeadPx = 6f   // ~0.20 m si PX_PER_M=30

    // Validation: on exige alignement yaw sur la nouvelle direction
    private val turnYawAcceptDeg = TURN_END  // 15 deg

    // Gyro integration pendant lock (robuste)
    private var turnGyroAngleDeg = 0f
    private var turnLockStartMs = 0L
    private var lockMaxYawErrDeg = 0f
    private var turnSawLargeErr = false

    // Minimum de "vrai virage" avant d'autoriser validation
    private val minTurnAngleFromGyroDeg = 25f
    private val lockTimeoutMs = 7000L  // anti-blocage dur

    private val lookAheadPx = 30f // ~1.0 m avant le virage
    private val minStepsBeforeFirstLock = 3
    private var lastUiTurnMsg = ""
    private var lastUiTurnTime = 0L

    // Anti validation virage toute seule : on exige plusieurs ticks dans la zone
    private var lockOkCount = 0
    private val lockOkNeeded = 6 // 6 ticks * 50ms â‰ˆ 300ms (avec yawTick), trÃ¨s efficace
    private var lockGyroOkCount = 0
    private val lockGyroOkNeeded = 6

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

    // --- TURN LOCK state ---
    private var turnLockActive = false
    private var turnDirSign = 0              // +1 RIGHT, -1 LEFT
    private var turnGyroZLp = 0f             // gyro filtre
    private var turnGyroHoldMs = 0L          // duree gyro bon sens
    private var turnGyroAccumRad = 0f        // integrale gyro (rotation)
    // --- TURN LOCK timing: use SENSOR TIMESTAMP ONLY ---
    private var turnStartTsNs = 0L
    private var turnLastTsNs = 0L

    // --- debug throttle ---
    private var turnDbgLastMs = 0L
    private var turnVibeDone = false
    private var turnLockDir = ""
    private var turnLockTargetAbs = 0f
    private var turnLockTargetRel = 0f
    private var turnLockSuppressed = false
    private var turnLockSuppressUntilDistPx = 0f
    private val turnGyroVarMin = 0.015f
    private var turnGyroSeen = false
    private var turnGyroPhase = 0
    private var turnGyroStableCount = 0
    private val gyroZStableThreshold = 0.08f
    private val gyroZStableNeeded = 6

    // =================== TURN BY GYRO Z ONLY ===================
    private var lastGyroZ = 0f
    private var gyroZFiltered = 0f
    private val gyroZAlpha = 0.25f
    private val gyroZTurnThreshold = 0.35f

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
    @Volatile private var udpMuted = false
    private var lastUdpErrorMs = 0L
    private val udpErrorCooldownMs = 5000L
    // =================== ZOOM / PAN ===================
    private val imageMatrixCurrent = Matrix()
    private var matrixReady = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var hasFocus = false
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
        showToast(msg)
    }

    // =================== LIFECYCLE ===================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---- Charge l'étalonnage utilisateur (si existant) ----
        run {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            stepLenMUser = prefs.getFloat(PREF_STEP_LEN_M, STEP_LEN_M_DEFAULT)
            stepRefLenMUser = stepLenMUser
            stepRefPeriodMsUser = prefs.getFloat(PREF_STEP_REF_PERIOD_MS, stepRefPeriodMsUser)
            stepAmpRefUser = prefs.getFloat(PREF_STEP_AMP_REF, stepAmpRefUser)
            stepPeriodMs = stepRefPeriodMsUser
            Log.i(
                TAG,
                "Loaded stepLenMUser=%.3f stepRefPeriodMsUser=%.0f"
                    .format(Locale.US, stepLenMUser, stepRefPeriodMsUser)
            )
        }

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

        // -------- Intro Panel (instructions + étalonnage) --------
        introPanel = FrameLayout(this).apply { setBackgroundColor(Color.rgb(12, 14, 18)) }
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18f * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        introTitle = TextView(this).apply {
            text = "Étalonnage de marche (5 m)"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        introBody = TextView(this).apply {
            setTextColor(Color.argb(230, 220, 230, 245))
            textSize = 15.5f
            gravity = Gravity.START
            text =
                "Objectif : estimer votre longueur de pas et votre cadence à partir de l’accéléromètre.\n\n" +
                "Consignes :\n" +
                "• Allez à la porte d’entrée.\n" +
                "• Appuyez sur « COMMENCER ».\n" +
                "• Marchez normalement jusqu’au poteau à côté des panneaux d’affichage (5 m).\n" +
                "• Arrivé(e) au poteau, appuyez sur « ARRÊT ».\n\n" +
                "Nous détectons vos pas et calculons automatiquement la longueur de pas."
        }
        introStats = TextView(this).apply {
            setTextColor(Color.argb(230, 190, 240, 200))
            textSize = 15.5f
            gravity = Gravity.START
            text = "Prêt(e) quand vous l’êtes."
        }

        val margin = (12f * resources.displayMetrics.density).toInt()

        navControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            val pad = (4f * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            navPanelBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * resources.displayMetrics.density
                setColor(navPanelBgIdle)
                setStroke(
                    (1f * resources.displayMetrics.density).toInt(),
                    navPanelStrokeIdle
                )
            }
            background = navPanelBg
        }
        navPanelScaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                navPanelScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scale = (navPanelScale * detector.scaleFactor)
                    .coerceIn(navPanelScaleMin, navPanelScaleMax)
                navPanelScale = scale
                navControls.scaleX = scale
                navControls.scaleY = scale
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                navPanelScaling = false
            }
        })

        fun handleNavPanelTouch(event: MotionEvent, forwardClicks: Boolean): Boolean {
            navPanelScaleDetector.onTouchEvent(event)
            navControls.parent?.requestDisallowInterceptTouchEvent(true)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    navPanelDownMs = System.currentTimeMillis()
                    navPanelLastRawX = event.rawX
                    navPanelLastRawY = event.rawY
                    navPanelDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!navPanelScaleDetector.isInProgress) {
                        val dx = event.rawX - navPanelLastRawX
                        val dy = event.rawY - navPanelLastRawY
                        val heldMs = System.currentTimeMillis() - navPanelDownMs
                        if (!navPanelDragging && (heldMs > 200 || hypot(dx.toDouble(), dy.toDouble()).toFloat() > touchSlop)) {
                            navPanelDragging = true
                            navPanelBg.setStroke(
                                (1f * resources.displayMetrics.density).toInt(),
                                navPanelStrokeActive
                            )
                            navPanelBg.setColor(navPanelBgActive)
                        }
                        if (navPanelDragging) {
                            navControls.translationX = navControls.translationX + dx
                            navControls.translationY = navControls.translationY + dy
                        }
                        navPanelLastRawX = event.rawX
                        navPanelLastRawY = event.rawY
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    navPanelBg.setStroke(
                        (1f * resources.displayMetrics.density).toInt(),
                        navPanelStrokeIdle
                    )
                    navPanelBg.setColor(navPanelBgIdle)
                    if (!navPanelDragging && !navPanelScaleDetector.isInProgress && forwardClicks) {
                        val target = findClickableChildAt(navControls, event.rawX, event.rawY)
                        if (target != null) {
                            target.performClick()
                        } else {
                            navControls.performClick()
                        }
                    }
                    navPanelDragging = false
                }
            }

            return if (forwardClicks) true else (navPanelDragging || navPanelScaleDetector.isInProgress)
        }

        val navChildTouchListener = View.OnTouchListener { _, event ->
            handleNavPanelTouch(event, false)
        }

        navControls.setOnTouchListener { _, event ->
            handleNavPanelTouch(event, true)
        }
        navHintText = TextView(this).apply {
            setTextColor(Color.argb(230, 220, 230, 245))
            textSize = 12f
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            text = "Choose the start, confirm, then choose the destination."
        }
        val btnTextSizeSp = 12f
        pickStartButton = Button(this).apply {
            text = "Choose start"
            textSize = btnTextSizeSp
            isAllCaps = false
            isLongClickable = false
            setOnClickListener {
                resetNavigationForSelection()
                startPoint = null
                endPoint = null
                startConfirmed = false
                endConfirmed = false
                selectMode = SelectMode.PICK_START
                showToast("Tap a start point")
                updateNavUi()
                draw()
            }
        }
        confirmButton = Button(this).apply {
            text = "Confirm"
            textSize = btnTextSizeSp
            isAllCaps = false
            isLongClickable = false
            setOnClickListener {
                if (!startConfirmed) {
                    if (startPoint == null) {
                        showToast("Pick the start first")
                        return@setOnClickListener
                    }
                    startConfirmed = true
                    selectMode = SelectMode.NONE
                    showToast("Start confirmed. Now pick the destination.")
                    updateNavUi()
                    draw()
                    return@setOnClickListener
                }
                if (startConfirmed && !endConfirmed) {
                    if (endPoint == null) {
                        showToast("Pick the destination first")
                        return@setOnClickListener
                    }
                    resetNavigationForSelection()
                    endConfirmed = true
                    selectMode = SelectMode.NONE
                    computePathAsync()
                    stepDetectionEnabled = true
                    showToast("Destination confirmed. You can walk.")
                    updateNavUi()
                    return@setOnClickListener
                }
            }
        }
        pickEndButton = Button(this).apply {
            text = "Choose destination"
            textSize = btnTextSizeSp
            isAllCaps = false
            isLongClickable = false
            setOnClickListener {
                if (!startConfirmed) {
                    showToast("Confirm the start first")
                    return@setOnClickListener
                }
                endConfirmed = false
                selectMode = SelectMode.PICK_END
                showToast("Tap a destination point")
                updateNavUi()
            }
        }
        restartButton = Button(this).apply {
            text = "Restart"
            textSize = btnTextSizeSp
            isAllCaps = false
            isLongClickable = false
            setOnClickListener {
                resetNavigationForSelection()
                reinitYawFromCurrentIfAvailable()
                startPoint = null
                endPoint = null
                startConfirmed = false
                endConfirmed = false
                selectMode = SelectMode.PICK_START
                showToast("Reset: choose a new start")
                updateNavUi()
                draw()
            }
        }
        pauseResumeButton = Button(this).apply {
            text = "Pause"
            textSize = btnTextSizeSp
            isAllCaps = false
            isLongClickable = false
            setOnClickListener {
                if (navState != NavState.RUNNING) {
                    showToast("Navigation pas active")
                    return@setOnClickListener
                }
                navPaused = !navPaused
                stepDetectionEnabled = !navPaused
                text = if (navPaused) "Reprendre" else "Pause"
                showToast(if (navPaused) "Navigation en pause" else "Navigation reprise")
                updateNavUi()
            }
        }

        val hintLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (4f * resources.displayMetrics.density).toInt()
        }
        navControls.addView(navHintText, hintLp)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val btnLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            rightMargin = (4f * resources.displayMetrics.density).toInt()
        }
        row1.setOnTouchListener(navChildTouchListener)
        row2.setOnTouchListener(navChildTouchListener)
        navHintText.setOnTouchListener(navChildTouchListener)
        pickStartButton.setOnTouchListener(navChildTouchListener)
        confirmButton.setOnTouchListener(navChildTouchListener)
        pickEndButton.setOnTouchListener(navChildTouchListener)
        restartButton.setOnTouchListener(navChildTouchListener)
        pauseResumeButton.setOnTouchListener(navChildTouchListener)
        row1.addView(pickStartButton, btnLp)
        row1.addView(confirmButton, btnLp)

        row2.addView(pickEndButton, btnLp)
        row2.addView(pauseResumeButton, btnLp)
        row2.addView(restartButton, btnLp)

        navControls.addView(row1)
        navControls.addView(row2)

        navControls.minimumWidth = (280f * resources.displayMetrics.density).toInt()

        val navParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = margin
            bottomMargin = margin
            leftMargin = margin
        }
        root.addView(navControls, navParams)
        updateNavUi()

        calibButton = Button(this).apply {
            text = "START ÉTALONNAGE"
            setOnClickListener {
                startCalibrationIfReady()
            }
        }
        calibButton.text = "COMMENCER"
        calibStopButton = Button(this).apply {
            text = "FIN ÉTALONNAGE"
            setOnClickListener {
                if (!calibActive) return@setOnClickListener
                if (calibStepCount < 2) {
                    resetCalibrationToReady("Étalonnage arrêté.")
                    return@setOnClickListener
                }
                finishCalibrationWithDistance()
            }
        }
        calibStopButton.text = "ARRÊT"
        calibRestartButton = Button(this).apply {
            text = "RECOMMENCER"
            setOnClickListener {
                resetCalibrationToReady("Étalonnage remis à zéro.\nAppuyez sur COMMENCER.")
            }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val btnPad = (10f * resources.displayMetrics.density).toInt()
        btnRow.addView(
            calibButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = btnPad
            }
        )
        btnRow.addView(
            calibStopButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = btnPad
                rightMargin = btnPad
            }
        )
        btnRow.addView(
            calibRestartButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = btnPad
            }
        )

        val card = FrameLayout(this).apply {
            val pad = (16f * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * resources.displayMetrics.density
                setColor(Color.argb(220, 20, 24, 30))
                setStroke(
                    (1f * resources.displayMetrics.density).toInt(),
                    Color.argb(60, 255, 255, 255)
                )
            }
        }
        val cardCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun spacer(dp: Float): View = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (dp * resources.displayMetrics.density).toInt()
            )
        }
        cardCol.addView(introTitle)
        cardCol.addView(spacer(10f))
        cardCol.addView(introBody)
        cardCol.addView(spacer(14f))
        cardCol.addView(btnRow)
        cardCol.addView(spacer(12f))
        cardCol.addView(introStats)
        card.addView(cardCol)

        column.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        scroll.addView(column)
        introPanel.addView(scroll)
        root.addView(
            introPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        imageView.visibility = View.GONE
        navControls.visibility = View.GONE
        calibStopButton.visibility = View.GONE
        introVisible = true

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
        val mapOpts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        bmpPlan = BitmapFactory.decodeResource(
            resources,
            com.example.sensortomatlab.R.drawable.rdc_galilee,
            mapOpts
        )

        // Base immuable pour affichage (aprÃ¨s doors)

        // Overlay rÃ©utilisable

        // Buffer walkable

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
        udpSendLine("CTRL,PARAM,STEPLEN,%.3f".format(Locale.US, stepLenMUser))
        udpSendLine("CTRL,PARAM,STEPREFPERIODMS,%.0f".format(Locale.US, stepRefPeriodMsUser))

        // Préparation carte dès le lancement, mais on n'affiche rien tant que l’étalonnage n’est pas fini.
                // Pr?paration carte d?s le lancement (pr?charg?e en arri?re-plan)
        // MAIS affichage uniquement quand l'utilisateur termine l'?talonnage.
        if (!mapPreloadStarted) {
            mapPreloadStarted = true
            root.post {
                Thread {
                    try {
                        detectAndDilateDoors()
                        walkable = BooleanArray(bmpPlan.width * bmpPlan.height)
                        precomputeWalkable()

                        // Pr?pare la base et le canvas compos? apr?s modifications doors
                        bmpBase = bmpPlan
                        bmpOverlay = Bitmap.createBitmap(bmpBase.width, bmpBase.height, Bitmap.Config.ARGB_8888)
                        overlayCanvas = Canvas(bmpOverlay)
                        mapReady = true
                        runOnUiThread {
                            // On ne montre pas tant que l'intro est visible,
                            // mais si l'intro est déjà fermée => afficher immédiatement.
                            if (!introVisible) {
                                imageView.visibility = View.VISIBLE
                                navControls.visibility = View.VISIBLE
                                updateNavUi()
                                requestDraw()
                            }
                        }
                        Log.i(TAG, "Map ready: ${bmpPlan.width}x${bmpPlan.height}")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Map init error", t)
                        showToast("Erreur init carte", Toast.LENGTH_LONG)
                    }
                }.start()
            }
        }

        imageView.setOnTouchListener { v, e ->
            if (mapReady) {
                scaleDetector.onTouchEvent(e)
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = e.x
                    lastTouchY = e.y
                    hasFocus = false
                    isPanning = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    var fx = 0f
                    var fy = 0f
                    for (i in 0 until e.pointerCount) {
                        fx += e.getX(i)
                        fy += e.getY(i)
                    }
                    lastFocusX = fx / e.pointerCount
                    lastFocusY = fy / e.pointerCount
                    hasFocus = true
                    isPanning = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2) {
                        var fx = 0f
                        var fy = 0f
                        for (i in 0 until e.pointerCount) {
                            fx += e.getX(i)
                            fy += e.getY(i)
                        }
                        val focusX = fx / e.pointerCount
                        val focusY = fy / e.pointerCount
                        if (!hasFocus) {
                            lastFocusX = focusX
                            lastFocusY = focusY
                            hasFocus = true
                        }
                        val dx = focusX - lastFocusX
                        val dy = focusY - lastFocusY
                        if (dx != 0f || dy != 0f) {
                            imageMatrixCurrent.postTranslate(dx, dy)
                            constrainImageMatrix()
                            imageView.imageMatrix = imageMatrixCurrent
                            isPanning = true
                        }
                        lastFocusX = focusX
                        lastFocusY = focusY
                    } else if (!isScaling && e.pointerCount == 1) {
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
                    hasFocus = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    isPanning = false
                    hasFocus = false
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

    override fun onStop() {
        super.onStop()
        // des qu'on quitte l'app => reset total demande
        resetAppToInitialUI()
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
            showToast("Carte en preparation...")
            return
        }

        val p = mapTouchToBitmap(e.x, e.y)

        when (selectMode) {
            SelectMode.PICK_START -> {
                startPoint = p
                showToast("Depart choisi, validez")
                updateNavUi()
            }
            SelectMode.PICK_END -> {
                endPoint = p
                showToast("Arrivee choisie, validez")
                updateNavUi()
            }
            SelectMode.NONE -> {
                // Pas de selection active
            }
        }
        draw()
    }

    private fun findClickableChildAt(root: ViewGroup, rawX: Float, rawY: Float): View? {
        val loc = IntArray(2)
        for (i in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            child.getLocationOnScreen(loc)
            val rect = Rect(loc[0], loc[1], loc[0] + child.width, loc[1] + child.height)
            if (rect.contains(rawX.toInt(), rawY.toInt())) {
                if (child is ViewGroup) {
                    val hit = findClickableChildAt(child, rawX, rawY)
                    if (hit != null) return hit
                }
                if (child.isClickable) return child
            }
        }
        return null
    }

    private fun setButtonEnabled(button: Button, enabled: Boolean) {
        if (::restartButton.isInitialized && button === restartButton) {
            button.isEnabled = true
            button.alpha = 1f
            return
        }
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.45f
    }

    private fun updateNavUi() {
        val canPickStart = !startConfirmed
        val canPickEnd = startConfirmed && !endConfirmed
        val canConfirmStart = (startPoint != null) && !startConfirmed
        val canConfirmEnd = (endPoint != null) && startConfirmed && !endConfirmed

        setButtonEnabled(pickStartButton, canPickStart)
        setButtonEnabled(confirmButton, canConfirmStart || canConfirmEnd)
        setButtonEnabled(pickEndButton, canPickEnd)
        setButtonEnabled(restartButton, true)
        setButtonEnabled(pauseResumeButton, navState == NavState.RUNNING)
        pauseResumeButton.visibility = View.VISIBLE
        restartButton.visibility = View.VISIBLE

        val det = if (stepDetectionEnabled) "ON" else "OFF"
        val hint = when {
            !startConfirmed && selectMode == SelectMode.PICK_START -> "Touchez la carte pour placer le depart"
            !startConfirmed -> "1) Choisir le depart puis valider"
            startConfirmed && !endConfirmed && selectMode == SelectMode.PICK_END -> "Touchez la carte pour placer l'arrivee"
            startConfirmed && !endConfirmed -> "3) Choisir l'arrivee puis valider"
            else -> "Pret: vous pouvez marcher"
        }
        pauseResumeButton.text = if (navPaused) "Reprendre" else "Pause"
        navHintText.text = "$hint\nDetection pas: $det"
    }

    private fun resetNavigationForSelection() {
        navigationActive = false
        navState = NavState.IDLE
        navPaused = false
        stepDetectionEnabled = false
        yawOffsetLockedToPath = false
        pendingStepsWhileLocked = 0
        closeCsvLogger()

        // distances/path
        cumDistPx = floatArrayOf()
        totalDistPx = 0f
        totalSteps = 0
        distNowPx = 0f
        ratioNow = 0f
        distAlongPx = 0f
        pathResult = emptyList()

        // steps (no sensor reset)
        rawStepCount = 0
        sentStepCount = 0

        // map calib
        heading0Abs = 0f
        heading0Ready = false
        drPosPx = PointF(0f, 0f)
        drHasPos = false

        // turns
        turnEvents = emptyList()
        nextTurnIdx = 0
        turnLockActive = false
        turnDirSign = 0
        turnGyroZLp = 0f
        turnGyroHoldMs = 0L
        turnGyroAccumRad = 0f
        turnStartTsNs = 0L
        turnLastTsNs = 0L
        turnVibeDone = false
        turnLockDir = ""
        turnLockTargetAbs = 0f
        turnLockTargetRel = 0f
        turnLockSuppressed = false
        turnLockSuppressUntilDistPx = 0f
        turnGyroSeen = false
        turnGyroAngleDeg = 0f
        turnDbgLastMs = 0L
        turnLockStartMs = 0L
        lockMaxYawErrDeg = 0f
        turnSawLargeErr = false
        lastTurnHintIdx = -1
        lastTurnHintStage = 0
        lockOkCount = 0

        resetNavStepDetectionState()
    }

    // =================== RESET ===================
    private fun resetNavigationState(keepPoints: Boolean) {
        navigationActive = false
        navState = NavState.IDLE
        navPaused = false
        startConfirmed = false
        endConfirmed = false
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
        distAlongPx = 0f
        pathResult = emptyList()

        // steps
        rawStepCount = 0
        sentStepCount = 0
        stepDetectionEnabled = false
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
        prevFilt = 0f
        lastDeriv = 0f
        waitingZeroCross = false
        peakTimeMs = 0L
        lastStepTimeMs = 0L
        lastAcceptedStepMs = 0L
        dtStepMs = 0L
        // IMPORTANT : repartir sur la référence USER (issue de l'étalonnage)
        stepPeriodMs = stepRefPeriodMsUser
        stepLenNavM = stepLenMUser.coerceIn(stepLenMinM, stepLenMaxM)
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
        turnDirSign = 0
        turnGyroZLp = 0f
        turnGyroHoldMs = 0L
        turnGyroAccumRad = 0f
        turnStartTsNs = 0L
        turnLastTsNs = 0L
        turnVibeDone = false
        turnLockDir = ""
        turnLockTargetAbs = 0f
        turnLockTargetRel = 0f
        turnLockSuppressed = false
        turnLockSuppressUntilDistPx = 0f
        turnGyroSeen = false
        turnDbgLastMs = 0L
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

    private fun reinitYawFromCurrentIfAvailable() {
        val yawAbsSmooth = getYawSmoothAbsDegOrNull() ?: return
        yawSmoothPrevDeg = yawAbsSmooth
        yawAbsContDeg = yawAbsSmooth
        yawOffsetContDeg = 0f
        yawOffset = 0f
        yawFiltered = normalizeAngle(yawAbsSmooth)
        yawInit = true
        lastYawTime = System.currentTimeMillis()
    }

    // Reset TOTAL quand on quitte l'app (home / app switch)
    private fun resetAppToInitialUI() {
        resetNavigationState(keepPoints = false)

        // reset calibration UI/state
        calibActive = false
        calibWaitingStart = true
        calibStepCount = 0
        calibStepTimes.clear()
        calibLastPeakMs = 0L
        calibPeakArmed = true

        introVisible = true
        runOnUiThread {
            introPanel.visibility = View.VISIBLE
            imageView.visibility = View.GONE
            navControls.visibility = View.GONE
            updateNavUi()
        }
        resetCalibrationToReady("Pret(e) quand vous l'etes.")
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
                    showToast("A* no path", Toast.LENGTH_LONG)
                    return@execute
                }

                // 2) Optional smoothing
                val computed = smoothPath(raw)
                if (computed.size < 2) {
                    Log.w(TAG, "Smoothed path too short")
                    showToast("Path invalid", Toast.LENGTH_LONG)
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

                // 4) MATLAB logic: use path length for steps (coherent with distNowPx)
                val cd = buildCumDistLocal(computed)
                val totalPx = cd.last().coerceAtLeast(0f)
                val start = computed.first()
                val end = computed.last()
                val dx = end.x - start.x
                val dy = end.y - start.y
                val straightDistPx = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val distanceM = totalPx / PX_PER_M
                stepLenNavM = stepLenMUser.coerceIn(stepLenMinM, stepLenMaxM)
                val steps = max(1, ceil(distanceM / stepLenNavM.toDouble()).toInt())

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
                sentStepCount = 0
                distAlongPx = 0f
                distNowPx = 0f
                ratioNow = 0f

                turnEvents = turns
                nextTurnIdx = 0
                turnLockActive = false
                turnDirSign = 0
                turnGyroZLp = 0f
                turnGyroHoldMs = 0L
                turnGyroAccumRad = 0f
                turnStartTsNs = 0L
                turnLastTsNs = 0L
                turnVibeDone = false
                lockOkCount = 0
                pendingStepsWhileLocked = 0
                turnLockSuppressed = false
                turnLockSuppressUntilDistPx = 0f

                // 7) Send to Matlab + UI
                sendPathToMatlab(computed)
                navigationActive = true
                navState = NavState.READY
                stepDetectionEnabled = true

                runOnUiThread {
                    showToast(
                        "A* ready: %.1fpx (%.1fm), steps=$totalSteps".format(
                            Locale.US,
                            totalPx,
                            distanceM
                        )
                    )
                    draw()
                }

                Log.i(
                    TAG,
                    "A* PATH ready: n=${computed.size} totalPx=%.1f straightPx=%.1f distanceM=%.2f totalSteps=%d turns=%d"
                        .format(Locale.US, totalPx, straightDistPx, distanceM, totalSteps, turns.size)
                )

            } catch (t: Throwable) {
                Log.e(TAG, "computePath(A*) error", t)
                showToast("Error path", Toast.LENGTH_LONG)
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
                val gz = e.values[2]
                gyroZFiltered = (1f - gyroZAlpha) * gyroZFiltered + gyroZAlpha * gz
                lastGyroZ = gyroZFiltered

                if (turnLockActive) {
                    val ts = e.timestamp // ns

                    if (turnStartTsNs == 0L) {
                        turnStartTsNs = ts
                        turnLastTsNs = ts
                        Log.i(TAG, "TURN LOCK gyro stream started tsNs=$ts")
                        return
                    }

                    val dtNs = ts - turnLastTsNs
                    turnLastTsNs = ts
                    if (dtNs <= 0L) return

                    updateDuringTurnLockTs(ts, dtNs, gz)
                } else {
                    turnStartTsNs = 0L
                    turnLastTsNs = 0L
                }

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
                if (turnLockActive) return

                val ax = e.values[0]
                val ay = e.values[1]
                val az = e.values[2]
                val filtMag = updateAccelFilters(e.timestamp, ax, ay, az)
                if (calibActive) {
                    if (!stepDetectionEnabled) return

                    val nowMs = e.timestamp / 1_000_000L
                    val absCurr = abs(filtMag)
                    if (nowMs - calibLastDbgMs >= 200L) {
                        calibLastDbgMs = nowMs
                        Log.i(
                            TAG,
                            "CALIB DBG absCurr=%.3f absLast=%.3f armed=%b thresh=%.2f"
                                .format(Locale.US, absCurr, abs(lastFilt), calibPeakArmed, calibAmpThresh)
                        )
                    }

                    // IMPORTANT: on utilise un pic = maximum local sur |signal|
                    val absLast = abs(lastFilt)
                    val absPrev = abs(prevFilt)
                    val isLocalMax = (absLast >= absPrev && absLast >= absCurr)

                    if (isLocalMax) {
                        val dt = if (calibLastAnyPeakMs == 0L) 0L else (nowMs - calibLastAnyPeakMs)
                        val okPolarity = lastFilt > 0f
                        val okCandidateAmp = absLast >= calibCandidateMinAmp

                        if (calibPeakArmed && okPolarity && okCandidateAmp) {
                            calibHasPeakCandidate = true
                            calibPeakCandidateAmp = absLast
                            calibPeakCandidateTimeMs = nowMs
                            calibPeakCandidateDtMs = dt
                            calibPeakArmed = false
                            calibLastAnyPeakMs = nowMs
                        }
                    }

                    // Fenetre du pic: uniquement partie positive du signal
                    if (filtMag > 0f) {
                        if (calibPosLobeStartMs == 0L) {
                            calibPosLobeStartMs = nowMs
                        }
                    } else if (calibPosLobeStartMs != 0L) {
                        val widthMs = nowMs - calibPosLobeStartMs
                        if (calibHasPeakCandidate) {
                            val widthOk = widthMs in calibPeakWidthAcceptMs..calibPeakWidthMaxMs
                            val widthReject = widthMs < calibPeakWidthRejectMs || widthMs > calibPeakWidthMaxMs
                            val okAmp =
                                calibPeakCandidateAmp in calibAmpThresh..calibAmpMax
                            val okDt = if (calibPeakCandidateDtMs == 0L) true
                            else (calibPeakCandidateDtMs in calibMinDtMs..calibMaxDtMs)

                            if (widthOk && !widthReject && okAmp && okDt) {
                                calibStepCount++
                                calibStepTimes.add(calibPeakCandidateTimeMs)
                                calibStepAmps.add(calibPeakCandidateAmp)

                                calibLastPeakMs = calibPeakCandidateTimeMs

                                runOnUiThread {
                                    introStats.text =
                                        "Enregistrement en cours…\n" +
                                            "Pas détectés : $calibStepCount\n" +
                                            "Perturbations : $calibPerturbations"
                                }
                                Log.i(
                                    TAG,
                                    "CALIB STEP step=%d amp=%.3f dt=%dms width=%dms"
                                        .format(
                                            Locale.US,
                                            calibStepCount,
                                            calibPeakCandidateAmp,
                                            calibPeakCandidateDtMs,
                                            widthMs
                                        )
                                )

                                if (DBG) {
                                    udpSendLine(
                                        "DBG_CALIB2,ACCEPT,amp=%.3f,dt=%d,width=%d,steps=%d,pert=%d"
                                            .format(
                                                Locale.US,
                                                calibPeakCandidateAmp,
                                                calibPeakCandidateDtMs,
                                                widthMs,
                                                calibStepCount,
                                                calibPerturbations
                                            )
                                    )
                                }
                            } else {
                                val countPert = calibPeakCandidateAmp >= calibPertMinAmp
                                if (countPert) {
                                    calibPerturbations++
                                }
                                Log.i(
                                    TAG,
                                    "CALIB PERT pert=%d amp=%.3f dt=%dms width=%dms okAmp=%b okDt=%b counted=%b"
                                        .format(
                                            Locale.US,
                                            calibPerturbations,
                                            calibPeakCandidateAmp,
                                            calibPeakCandidateDtMs,
                                            widthMs,
                                            okAmp,
                                            okDt,
                                            countPert
                                        )
                                )
                                runOnUiThread {
                                    introStats.text =
                                        "Enregistrement en cours…\n" +
                                            "Pas détectés : $calibStepCount\n" +
                                            "Perturbations : $calibPerturbations"
                                }
                                if (DBG) {
                                    udpSendLine(
                                        "DBG_CALIB2,REJECT,amp=%.3f,dt=%d,width=%d,okAmp=%b,okDt=%b,pert=%d"
                                            .format(
                                                Locale.US,
                                                calibPeakCandidateAmp,
                                                calibPeakCandidateDtMs,
                                                widthMs,
                                                okAmp,
                                                okDt,
                                                calibPerturbations
                                            )
                                    )
                                }
                            }
                        }

                        calibPosLobeStartMs = 0L
                        calibHasPeakCandidate = false
                        calibPeakCandidateAmp = 0f
                        calibPeakCandidateTimeMs = 0L
                        calibPeakCandidateDtMs = 0L
                        calibPeakArmed = true
                    }

                    // IMPORTANT: mise à jour mémoire (sinon localMax ne marche pas)
                    prevFilt = lastFilt
                    lastFilt = filtMag

                    return
                }
                // navigation: pas detectes seulement si navigation active (READY ou RUNNING)
                if (!navigationActive) return
                if (navPaused) return
                if (!stepDetectionEnabled) return

                if (detectStepFromPeakNav(filtMag, e.timestamp)) {
                    val nowMs = e.timestamp / 1_000_000L

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

                        navPaused = false
                        resetNavStepDetectionState()
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
                        val stepPx = stepLenPxNav()
                        distAlongPx = min(totalDistPx, distAlongPx + stepPx)
                        udpSendLine(
                            "CTRL,STEPINFO,STEPLENM,%.3f,CAD,%.0f"
                                .format(Locale.US, stepLenNavM, cadenceSpmNow())
                        )
                        val nowMs = System.currentTimeMillis()
                        udpSendLine("STEP_EVT,$sentStepCount,$nowMs")
                        updatePositionFromAlongDistance()
                        Log.i(
                            TAG,
                            "STEP stepPx=%.2f stepLenM=%.3f T=%.0fms cad=%.0fspm distAlongPx=%.1f"
                                .format(
                                    Locale.US,
                                    stepPx,
                                    stepLenMdynNow(),
                                    stepPeriodMs,
                                    cadenceSpmNow(),
                                    distAlongPx
                                )
                        )

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
        val yawVar = 0f

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

        udpSendLine(
            "DBG,YAW,yawRel=%.1f,segRel=%.1f,dist=%.1f,ratio=%.3f,seg=%d,lock=%b,dir=%s,state=%s,src=%s,mag=%b,drx=%.1f,dry=%.1f"
                .format(
                    Locale.US,
                    yawRel,
                    segRel,
                    distNowPx,
                    ratioNow,
                    segIdx,
                    turnLockActive,
                    turnLockDir,
                    navState.name,
                    lastYawSource,
                    magDisturbed,
                    drPosPx.x,
                    drPosPx.y
                )
        )

        val now = System.currentTimeMillis()
        if (DBG && now - lastDbgTime > DBG_PERIOD_MS) {
            lastDbgTime = now
            Log.d(
                TAG,
                "DBG trig=$trigger seg=$segIdx dist=%.1fpx ratio=%.3f step=$sentStepCount yawRel=%.1f segRel=%.1f lock=$turnLockActive($turnLockDir)"
                    .format(Locale.US, distNowPx, ratioNow, yawRel, segRel, turnLockDir)
            )
        }
        val turnState = if (turnLockActive) "LOCK_$turnLockDir" else "OK"
        logCsvLine(yawRel, yawVar, 0f, drPosPx, distNowPx, segIdx, turnState)
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
            if (nextInfo.distM <= 1.0f && lastTurnHintStage < 1) {
                showTurnHint("Tournez a $dir dans %.1f m".format(Locale.US, nextInfo.distM))
                vibrate(120)
                lastTurnHintStage = 1
            }
        }

        // 1) declenchement lock virage par distance
        val turns = turnEvents
        if (!turnLockActive && nextTurnIdx < turns.size) {
            val ev = turns[nextTurnIdx]

            // 1) vibration 1m avant = deja geree plus haut via nextInfo.distM <= 1.0f

            // 2) lock SEULEMENT quand on arrive au point du virage (~20 cm avant)
            if (turnLockSuppressed && distNowPx >= turnLockSuppressUntilDistPx) {
                turnLockSuppressed = false
            }
            if (turnLockSuppressed) return
            if (distNowPx >= ev.atDistPx - turnLockLeadPx) {
                turnLockDir = ev.dir
                pendingStepsWhileLocked = 0

                // Cible du nouveau segment (relatif)
                turnLockTargetRel = absToRelMap(ev.newHeadingAbs)

                // init lock state
                lockOkCount = 0
                lockGyroOkCount = 0
                turnGyroPhase = 0
                turnGyroStableCount = 0
                lockMaxYawErrDeg = 0f
                turnSawLargeErr = false

                startTurnLock(dirRight = ev.dir == "RIGHT")
                udpSendLine("TURN,${ev.dir}")
            }
        }

        // 2) validation par gyro Z
        if (turnLockActive) {
            return
        } else {
            udpSendLine("TURN,OK")
        }

        // fin
        if (ratioNow >= 0.995f) navCompleted()
    }

    private fun startTurnLock(dirRight: Boolean) {
        turnLockActive = true
        turnDirSign = if (dirRight) +1 else -1

        turnGyroZLp = 0f
        turnGyroHoldMs = 0L
        turnGyroAccumRad = 0f
        turnStartTsNs = 0L
        turnLastTsNs = 0L

        turnGyroPhase = 0 // 0: waiting opposite lobe, 1: waiting correct lobe
        turnGyroStableCount = 0

        turnVibeDone = false

        if (!turnVibeDone) {
            vibrateOnceShort()
            turnVibeDone = true
        }

        Log.i(
            TAG,
            "TURN LOCK ON dir=${if (dirRight) "RIGHT" else "LEFT"} sign=$turnDirSign " +
                "needDeg=$GYRO_TURN_DEG_MIN rateMin=$GYRO_RATE_MIN holdMs=$GYRO_HOLD_MS"
        )
    }

    private fun updateDuringTurnLockTs(tsNs: Long, dtNs: Long, gyroZRaw: Float) {
        if (!turnLockActive) return

        // LP filter
        turnGyroZLp = 0.88f * turnGyroZLp + 0.12f * gyroZRaw

        val dtSec = dtNs.toFloat() * 1e-9f
        val ageMs = (tsNs - turnStartTsNs) / 1_000_000L

        // gyro in expected direction (dir-normalized)
        val gyroDir = turnGyroZLp * turnDirSign

        // phase detector:
        // For RIGHT (dirSign=+1): we often see a negative pre-lobe => gyroDir < 0
        // For LEFT  (dirSign=-1): symmetric.
        val preLobeSeen = gyroDir < -0.12f
        if (turnGyroPhase == 0 && preLobeSeen) {
            turnGyroPhase = 1
            Log.i(
                TAG,
                "TURN LOCK phase1: pre-lobe seen gyroDir=%.3f gzLp=%.3f"
                    .format(Locale.US, gyroDir, turnGyroZLp)
            )
        }

        // accept condition is based on POSITIVE gyroDir (correct direction)
        val gyroOk = gyroDir > GYRO_RATE_MIN

        if (gyroOk) {
            turnGyroHoldMs += (dtNs / 1_000_000L)
            // integrate ONLY correct direction so opposite lobe doesn't cancel
            turnGyroAccumRad += gyroDir * dtSec
        } else {
            // soft decay (keeps responsiveness but avoids noise)
            turnGyroHoldMs = (turnGyroHoldMs * 0.6f).toLong()
            turnGyroAccumRad *= 0.90f
        }

        val turnedDeg = (turnGyroAccumRad * 57.29578f) // already positive-ish because gyroDir integrated
        val needDeg = GYRO_TURN_DEG_MIN
        val remainDeg = (needDeg - turnedDeg).coerceAtLeast(0f)

        val accept =
            (ageMs >= TURN_MIN_LOCK_MS) &&
                (turnGyroPhase >= 1 || ageMs >= 260L) && // phase optional: after 260ms we don't require pre-lobe
                (turnGyroHoldMs >= GYRO_HOLD_MS) &&
                (turnedDeg >= needDeg)

        val timeout = ageMs > TURN_MAX_LOCK_MS

        // logs throttled
        val nowWall = System.currentTimeMillis()
        if (nowWall - turnDbgLastMs > 150L) {
            turnDbgLastMs = nowWall
            Log.i(
                TAG,
                "TURN LOCK dbg age=${ageMs}ms phase=$turnGyroPhase " +
                    "gzRaw=%.3f gzLp=%.3f dir=%+.0f gyroDir=%.3f ok=%b hold=${turnGyroHoldMs}ms " +
                    "turned=%.1fdeg need=%.1fdeg rem=%.1fdeg"
                    .format(
                        Locale.US,
                        gyroZRaw,
                        turnGyroZLp,
                        turnDirSign.toFloat(),
                        gyroDir,
                        gyroOk,
                        turnedDeg,
                        needDeg,
                        remainDeg
                    )
            )
            // utile aussi via UDP si tu veux
            udpSendLine(
                "DBG_TURN,age=$ageMs,phase=$turnGyroPhase,gzRaw=%.3f,gzLp=%.3f,gyroDir=%.3f,hold=%d,turned=%.1f,need=%.1f"
                    .format(
                        Locale.US,
                        gyroZRaw,
                        turnGyroZLp,
                        gyroDir,
                        turnGyroHoldMs,
                        turnedDeg,
                        needDeg
                    )
            )
        }

        if (accept || timeout) {
            Log.i(
                TAG,
                "TURN VALIDATED accept=$accept timeout=$timeout age=${ageMs}ms phase=$turnGyroPhase " +
                    "hold=${turnGyroHoldMs}ms turned=%.1fdeg gzLp=%.3f"
                    .format(Locale.US, turnedDeg, turnGyroZLp)
            )

            turnLockActive = false
            advanceToNextSegment()
        }
    }

    private fun advanceToNextSegment() {
        validateTurn()
    }

    private fun validateTurn() {
        turnGyroAngleDeg = 0f
        turnDirSign = 0
        turnGyroZLp = 0f
        turnGyroHoldMs = 0L
        turnGyroAccumRad = 0f
        turnStartTsNs = 0L
        turnLastTsNs = 0L
        turnVibeDone = false
        lockOkCount = 0
        lockGyroOkCount = 0
        turnSawLargeErr = false
        lockMaxYawErrDeg = 0f
        turnLockStartMs = 0L
        turnLockActive = false
        nextTurnIdx++

        if (pendingStepsWhileLocked > 0) {
            applyPendingSteps()
            udpSendLine("STEP,$sentStepCount")
            draw()
        }

        udpSendLine("TURN,OK")
        vibrate(120)

        Log.i(
            TAG,
            "TURN VALIDATED by gyroZ = %.3f"
                .format(Locale.US, lastGyroZ)
        )
    }

    private fun updateTurnLockByGyro(): Boolean {
        // 1) securite timeout (evite blocage infini)
        val nowMs = System.currentTimeMillis()
        if (turnLockStartMs != 0L && nowMs - turnLockStartMs > lockTimeoutMs) {
            // si on est aligne, on valide; sinon on relache (fail-safe) pour ne pas rester bloque
            val yawRel = getYawFiltered()
            val err = abs(angleErrorDeg(turnLockTargetRel, yawRel))
            if (err < turnYawAcceptDeg) {
                validateTurn()
                return true
            } else {
                Log.w(TAG, "TURN LOCK TIMEOUT -> release (err=%.1f)".format(Locale.US, err))
                turnLockActive = false
                pendingStepsWhileLocked = 0
                turnLockSuppressed = true
                turnLockSuppressUntilDistPx = distNowPx + max(stepLenPxNav(), turnLockLeadPx)
                udpSendLine("TURN,OK")
                vibrate(80)
                return true
            }
        }

        // 2) condition d'alignement yaw (OBLIGATOIRE pour valider)
        val yawRel = getYawFiltered()
        val errSigned = angleErrorDeg(turnLockTargetRel, yawRel)
        val errAbs = abs(errSigned)

        lockMaxYawErrDeg = max(lockMaxYawErrDeg, errAbs)
        if (lockMaxYawErrDeg >= lockMinErrToRequireTurn) turnSawLargeErr = true

        val stableGyro = abs(lastGyroZ) < gyroZStableThreshold

        // 3) "vrai virage" : soit angle gyro accumule, soit yaw a vraiment decroche
        val turnedEnoughByGyro = abs(turnGyroAngleDeg) >= minTurnAngleFromGyroDeg
        val turnedEnough = turnedEnoughByGyro || turnSawLargeErr

        // ---- NOUVEAU: validation "gyro-only" (yaw peut etre gele si telephone vertical) ----
        // On exige: gyro stable + rotation integree suffisante + signe coherent avec LEFT/RIGHT
        val dirOk = when (turnLockDir) {
            "LEFT" -> turnGyroAngleDeg <= -minTurnAngleFromGyroDeg
            "RIGHT" -> turnGyroAngleDeg >= minTurnAngleFromGyroDeg
            else -> turnedEnoughByGyro
        }
        if (stableGyro && turnedEnoughByGyro && dirOk) {
            lockGyroOkCount++
            if (lockGyroOkCount >= lockGyroOkNeeded) {
                // Recalage yaw sur la cible pour repartir propre apres validation
                val yawAbsSmooth = getYawSmoothAbsDegOrNull()
                if (yawAbsSmooth != null) {
                    yawSmoothPrevDeg = yawAbsSmooth
                    yawAbsContDeg = yawAbsSmooth
                    yawOffsetContDeg = yawAbsContDeg - turnLockTargetRel
                    yawFiltered = turnLockTargetRel
                    yawInit = true
                    lastYawTime = System.currentTimeMillis()
                    yawOffsetLockedToPath = true
                }
                validateTurn()
                return true
            }
        } else {
            lockGyroOkCount = 0
        }

        // 4) validation = alignement yaw proche + gyro stable quelques ticks + turnedEnough
        if (errAbs < turnYawAcceptDeg && stableGyro && turnedEnough) {
            lockOkCount++
            if (lockOkCount >= lockOkNeeded) {
                validateTurn()
                return true
            }
        } else {
            lockOkCount = 0
        }

        // debug UDP
        udpSendLine(
            "TURN,LOCK,dir=$turnLockDir,err=%.1f,gz=%.3f,gyroDeg=%.1f,ok=%d,seen=%b"
                .format(Locale.US, errSigned, lastGyroZ, turnGyroAngleDeg, lockOkCount, turnedEnough)
        )

        return false
    }

    private fun navCompleted() {
        navState = NavState.FINISHED
        navigationActive = false
        udpSendLine("CTRL,STOP")
        udpSendLine("TURN,OK")
        closeCsvLogger()
        vibrate(700)
        Log.i(TAG, "NAV COMPLETED")
        showToast("You made it <3 !", Toast.LENGTH_LONG)
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
        // Hors virage locke: on rejette les poses trop verticales.
        // Pendant un lock de virage: on laisse passer (sinon yaw reste fige => jamais de delock).
        if (!turnLockActive && abs(pitchDeg) > 70f) return yawFiltered

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


    private fun resetNavStepDetectionState() {
        navPeakArmed = true
        navPosLobeStartMs = 0L
        navHasPeakCandidate = false
        navPeakCandidateAmp = 0f
        navPeakCandidateTimeMs = 0L
        navPeakCandidateDtMs = 0L
        navLastAnyPeakMs = 0L
        prevFilt = 0f
        lastFilt = 0f
    }

    private fun onStepAccepted(nowMs: Long) {
        if (turnLockActive) return

        if (lastAcceptedStepMs != 0L) {
            val dt = (nowMs - lastAcceptedStepMs).coerceIn(250L, 2000L)
            dtStepMs = dt
            val dtF = dt.toFloat()
            stepPeriodMs = 0.85f * stepPeriodMs + 0.15f * dtF
            Log.i("NAVAPP", "STEP PERIOD dtStepMs=$dt stepPeriodMs=${stepPeriodMs.toInt()}")
        }
        lastAcceptedStepMs = nowMs
    }

    private fun detectStepFromAccel(filt: Float, tsNs: Long): Boolean {
        if (turnLockActive) return false

        val nowMs = tsNs / 1_000_000L

        // Refractory dynamique (cadence)
        val dynMinDelayMs = max(minStepDelay.toFloat(), minDelayFrac * stepPeriodMs).toLong()

        val absCurr = abs(filt)
        val absLast = abs(lastFilt)
        val absPrev = abs(prevFilt)

        if (accelWinCount < 10) {
            prevFilt = lastFilt
            lastFilt = filt
            peakAbsHold = max(peakAbsHold, absCurr)
            return false
        }

        // --- Stats robustes (et IMPORTANT: on "gele" un peu pendant les pics) ---
        // Si on est clairement sur un gros pic, on evite d'alimenter la moyenne avec ca.
        val feed = min(absCurr, max(0.25f, 0.70f * peakAbsHold.coerceAtLeast(absCurr)))
        val (meanAbs, stdAbs) = updateAccelStatsRobust(feed)

        // Seuil adaptatif leger + plancher (petits pas)
        val threshFloor = 0.09f
        val thresh = max(threshFloor, meanAbs + stepThreshK * stdAbs)

        // Prominence legere (si bruit faible)
        val prominenceOk = (absLast - meanAbs) >= stepProminenceMin

        // Detection de pic LOCAL sur |signal| (pas besoin de zero-cross)
        val localMaxAbs = (absLast >= absPrev && absLast >= absCurr)

        val sinceLast = nowMs - lastAcceptedStepMs
        val passThresh = (absLast > thresh || prominenceOk)
        val accept = localMaxAbs && sinceLast > dynMinDelayMs && passThresh
        if (DBG && localMaxAbs && !accept) {
            udpSendLine(
                "DBGSTEP,REJECT,abs=%.4f,th=%.4f,mu=%.4f,sd=%.4f,delay=%d,min=%d,prom=%b,pass=%b"
                    .format(
                        Locale.US,
                        absLast,
                        thresh,
                        meanAbs,
                        stdAbs,
                        sinceLast,
                        dynMinDelayMs,
                        prominenceOk,
                        passThresh
                    )
            )
        }
        if (accept) {

            // update periode
            onStepAccepted(nowMs)
            lastStepTimeMs = nowMs

            // memorise amplitude pour limiter la stats update plus haut
            peakAbsHold = absLast

            lastStepAmpAbs = absLast.coerceAtLeast(0.05f)
            if (DBG) {
                udpSendLine(
                    "DBGSTEP,STEP,abs=%.4f,th=%.4f,mu=%.4f,sd=%.4f,T=%.0f,dt=%d,min=%d"
                        .format(Locale.US, absLast, thresh, meanAbs, stdAbs, stepPeriodMs, sinceLast, dynMinDelayMs)
                )
            }
            return true
        }

        // update memoire
        prevFilt = lastFilt
        lastFilt = filt
        return false
    }

    private fun detectStepFromPeakNav(filt: Float, tsNs: Long): Boolean {
        if (turnLockActive) return false

        val nowMs = tsNs / 1_000_000L
        val absCurr = abs(filt)
        val absLast = abs(lastFilt)
        val absPrev = abs(prevFilt)

        val ampMin = (stepAmpRefUser * 0.6f).coerceAtLeast(0.5f)
        val ampMax = (stepAmpRefUser * 1.6f).coerceAtLeast(ampMin + 0.01f)
        val minDt = max(200L, (stepRefPeriodMsUser * 0.55f).toLong())
        val maxDt = min(3000L, (stepRefPeriodMsUser * 1.70f).toLong())

        val isLocalMax = (absLast >= absPrev && absLast >= absCurr)
        if (isLocalMax) {
            val dt = if (navLastAnyPeakMs == 0L) 0L else (nowMs - navLastAnyPeakMs)
            val okPolarity = lastFilt > 0f
            val okCandidateAmp = absLast >= ampMin

            if (navPeakArmed && okPolarity && okCandidateAmp) {
                navHasPeakCandidate = true
                navPeakCandidateAmp = absLast
                navPeakCandidateTimeMs = nowMs
                navPeakCandidateDtMs = dt
                navPeakArmed = false
                navLastAnyPeakMs = nowMs
            }
        }

        if (filt > 0f) {
            if (navPosLobeStartMs == 0L) {
                navPosLobeStartMs = nowMs
            }
        } else if (navPosLobeStartMs != 0L) {
            val widthMs = nowMs - navPosLobeStartMs
            if (navHasPeakCandidate) {
                val widthOk = widthMs in calibPeakWidthAcceptMs..calibPeakWidthMaxMs
                val widthReject = widthMs < calibPeakWidthRejectMs || widthMs > calibPeakWidthMaxMs
                val okAmp = navPeakCandidateAmp in ampMin..ampMax
                val okDt = if (navPeakCandidateDtMs == 0L) true
                else (navPeakCandidateDtMs in minDt..maxDt)

                if (widthOk && !widthReject && okAmp && okDt) {
                    onStepAccepted(nowMs)
                    lastStepTimeMs = nowMs
                    lastStepAmpAbs = navPeakCandidateAmp.coerceAtLeast(0.05f)
                    peakAbsHold = navPeakCandidateAmp
                    Log.i(
                        TAG,
                        "NAV STEP amp=%.3f dt=%dms width=%dms refT=%.0fms ampRef=%.3f"
                            .format(
                                Locale.US,
                                navPeakCandidateAmp,
                                navPeakCandidateDtMs,
                                widthMs,
                                stepRefPeriodMsUser,
                                stepAmpRefUser
                            )
                    )
                    prevFilt = lastFilt
                    lastFilt = filt
                    return true
                }
            }

            navPosLobeStartMs = 0L
            navHasPeakCandidate = false
            navPeakCandidateAmp = 0f
            navPeakCandidateTimeMs = 0L
            navPeakCandidateDtMs = 0L
            navPeakArmed = true
        }

        prevFilt = lastFilt
        lastFilt = filt
        return false
    }

    private fun startCalibrationIfReady() {
        if (!calibWaitingStart) return

        calibWaitingStart = false
        calibActive = true
        stepDetectionEnabled = true   // pas actifs UNIQUEMENT apres COMMENCER
        calibStepCount = 0
        calibStepTimes.clear()
        calibStepAmps.clear()
        calibLastPeakMs = 0L
        calibPeakArmed = true
        calibPerturbations = 0
        calibPosLobeStartMs = 0L
        calibHasPeakCandidate = false
        calibPeakCandidateAmp = 0f
        calibPeakCandidateTimeMs = 0L
        calibPeakCandidateDtMs = 0L
        calibLastAnyPeakMs = 0L
        calibStopButton.visibility = View.VISIBLE

        // IMPORTANT: reset stats pour seuil adaptatif etalonnage (evite pollution)
        accelWinCount = 0
        accelWinIdx = 0
        accelSum = 0f
        accelSumSq = 0f

        introStats.text =
            "Enregistrement en cours...\n" +
            "Marchez normalement. Pas detectes : 0"

        Log.i(TAG, "CALIB START")
    }

    private fun resetCalibrationToReady(message: String = "Pret(e) quand vous l'etes.") {
        calibActive = false
        calibWaitingStart = true
        stepDetectionEnabled = false
        calibStepCount = 0
        calibStepTimes.clear()
        calibStepAmps.clear()
        calibLastPeakMs = 0L
        calibPeakArmed = true
        calibPerturbations = 0
        calibPosLobeStartMs = 0L
        calibHasPeakCandidate = false
        calibPeakCandidateAmp = 0f
        calibPeakCandidateTimeMs = 0L
        calibPeakCandidateDtMs = 0L
        calibLastAnyPeakMs = 0L

        // reset stats pour seuil adaptatif
        accelWinCount = 0
        accelWinIdx = 0
        accelSum = 0f
        accelSumSq = 0f

        runOnUiThread {
            calibStopButton.visibility = View.GONE
            calibButton.visibility = View.VISIBLE
            calibButton.text = "COMMENCER"
            calibButton.setOnClickListener { startCalibrationIfReady() }
            introStats.text = message
        }
    }

    private fun finishCalibrationWithDistance() {
        calibActive = false
        calibWaitingStart = false
        stepDetectionEnabled = false  // stop pas des qu'on finit l'etalonnage

        if (calibStepCount <= 0) return

        if (calibStepCount !in 5..12) {
            showToast(
                "Étalonnage invalide ($calibStepCount pas détectés).\nRefaites la marche de 5 m.",
                Toast.LENGTH_LONG
            )
            Log.w(TAG, "CALIB REJECT steps=$calibStepCount")
            calibWaitingStart = true
            calibButton.visibility = View.VISIBLE
            calibStopButton.visibility = View.GONE
            runOnUiThread {
                introStats.text =
                    "Résultat incohérent (pas détectés : $calibStepCount).\n" +
                    "Astuce : marchez normalement, téléphone stable, puis réessayez."
            }
            return
        }

        val intervals = ArrayList<Float>(max(0, calibStepTimes.size - 1))
        for (i in 1 until calibStepTimes.size) {
            intervals.add((calibStepTimes[i] - calibStepTimes[i - 1]).toFloat())
        }
        val dtRefRaw = if (intervals.isNotEmpty()) {
            val sorted = intervals.sorted()
            sorted[sorted.size / 2].coerceIn(stepPeriodMinMs, stepPeriodMaxMs)
        } else {
            stepRefPeriodMs
        }
        val minOk = dtRefRaw * 0.55f
        val maxOk = dtRefRaw * 1.70f
        val intervalsOk = intervals.filter { it in minOk..maxOk }
        val dtRef = if (intervalsOk.isNotEmpty()) {
            val sorted = intervalsOk.sorted()
            sorted[sorted.size / 2].coerceIn(stepPeriodMinMs, stepPeriodMaxMs)
        } else {
            dtRefRaw
        }
        if (dtRef > calibMaxRefPeriodMs) {
            showToast(
                "Etalonnage invalide (pauses ou marche trop lente).\nRefaites les 5 m d'un seul trait.",
                Toast.LENGTH_LONG
            )
            Log.w(TAG, "CALIB REJECT dtRef=%.0fms".format(Locale.US, dtRef))
            calibWaitingStart = true
            calibButton.visibility = View.VISIBLE
            calibStopButton.visibility = View.GONE
            runOnUiThread {
                introStats.text =
                    "Resultat incoherent (pauses detectees).\n" +
                    "Astuce : marchez d'un seul trait, sans vous arreter."
            }
            return
        }

        // amplitude ref = mediane (robuste)
        val amps = calibStepAmps.sorted()
        val ampRef = if (amps.isNotEmpty()) {
            amps[amps.size / 2].coerceAtLeast(0.05f)
        } else {
            0.12f
        }
        stepAmpRefUser = ampRef

        var weightSum = 0f
        for (i in 0 until calibStepTimes.size) {
            val dt = if (i == 0) dtRef else (calibStepTimes[i] - calibStepTimes[i - 1]).toFloat()
            val dtUse = if (dt in minOk..maxOk) dt else dtRef
            val dtClamped = dtUse.coerceIn(stepPeriodMinMs, stepPeriodMaxMs)
            val ratioT = (dtRef / dtClamped).coerceIn(0.6f, 1.6f)
            val cadFactor = ratioT.toDouble().pow(0.20).toFloat()
            val ampRatio = (calibStepAmps[i] / ampRef).coerceIn(0.6f, 1.6f)
            val ampFactor = ampRatio.toDouble().pow(0.25).toFloat()
            weightSum += (cadFactor * ampFactor)
        }
        val stepLenUser = if (weightSum > 0f) {
            (CALIB_DISTANCE_M / weightSum)
        } else {
            (CALIB_DISTANCE_M / calibStepCount.toFloat())
        }.coerceIn(stepLenMinM, stepLenMaxM)

        stepLenMUser = stepLenUser
        stepRefLenMUser = stepLenUser
        stepRefPeriodMsUser = dtRef
        stepPeriodMs = dtRef

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putFloat(PREF_STEP_LEN_M, stepLenMUser)
            .putFloat(PREF_STEP_REF_PERIOD_MS, stepRefPeriodMsUser)
            .putFloat(PREF_STEP_AMP_REF, stepAmpRefUser)
            .apply()

        udpSendLine("CTRL,PARAM,STEPLEN,%.3f".format(Locale.US, stepLenMUser))
        udpSendLine("CTRL,PARAM,STEPREFPERIODMS,%.0f".format(Locale.US, stepRefPeriodMsUser))

        val cadenceSpm = (60000f / stepRefPeriodMsUser).coerceIn(40f, 220f)
        val vibe = when {
            cadenceSpm < 95f -> "Mode balade "
            cadenceSpm < 125f -> "Marche efficace "
            cadenceSpm < 155f -> "Rythme tonique"
            else -> "Turbo marche "
        }
        runOnUiThread {
            introStats.text =
                "Mesures terminées ?\n\n" +
                "• Pas détectés : $calibStepCount\n" +
                "• Longueur de pas : %.2f m\n".format(Locale.US, stepLenMUser) +
                "• Cadence : %.0f pas/min\n".format(Locale.US, cadenceSpm) +
                "• Qualité : $vibe\n\n" +
                "Vous pouvez commencer la navigation."
        }

        calibStopButton.visibility = View.GONE
        calibButton.visibility = View.VISIBLE
        calibButton.text = "COMMENCER NAVIGATION"
        calibButton.setOnClickListener {
            introVisible = false
            introPanel.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            navControls.visibility = View.VISIBLE
            updateNavUi()
            // On n'active pas les pas ici: ils s'activeront quand la nav passe RUNNING
            stepDetectionEnabled = true
            if (mapReady) {
                requestDraw()
            } else {
                showToast("Préparation de la carte…")
            }
        }

        Log.i(
            TAG,
            "CALIB DONE dist=5m steps=$calibStepCount stepLen=%.3f T=%.0fms"
                .format(Locale.US, stepLenMUser, stepRefPeriodMsUser)
        )
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

    private fun updateAccelStatsRobust(valueAbs: Float): Pair<Float, Float> {
        // Stats before update
        val meanOld = if (accelWinCount > 0) accelSum / accelWinCount else 0f
        val varOld = if (accelWinCount > 0) accelSumSq / accelWinCount - meanOld * meanOld else 0f
        val stdOld = sqrt(max(0f, varOld))

        // Clipping: avoid peaks pulling mean/std up too much
        val cap = if (accelWinCount < 20) {
            // Early window: avoid cap too low
            max(0.30f, meanOld + 4.0f * stdOld)
        } else {
            max(0.30f, meanOld + 2.5f * stdOld)
        }

        val clipped = valueAbs.coerceAtMost(cap)

        // Update window with clipped value
        val old = if (accelWinCount < accelStatsWindow) 0f else accelWin[accelWinIdx]
        if (accelWinCount < accelStatsWindow) accelWinCount++
        accelSum += clipped - old
        accelSumSq += clipped * clipped - old * old
        accelWin[accelWinIdx] = clipped
        accelWinIdx = (accelWinIdx + 1) % accelStatsWindow

        val mean = accelSum / accelWinCount
        val varAbs = accelSumSq / accelWinCount - mean * mean
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
        val ratio = (stepRefPeriodMsUser / stepPeriodMs).toDouble()
        val stepLenMdyn = (stepRefLenMUser * ratio.pow(stepLenAlpha.toDouble())).toFloat()
            .coerceIn(stepLenMinM, stepLenMaxM)
        val stepPx = (PX_PER_M * stepLenMdyn) * steps
        val rad = Math.toRadians(yawRel.toDouble())
        val dx = (stepPx * sin(rad)).toFloat()
        val dy = -(stepPx * cos(rad)).toFloat()
        drPosPx.offset(dx, dy)


    }

    private fun updatePositionFromAlongDistance() {
        val path = pathResult
        val cd = cumDistPx
        if (path.size < 2 || cd.size != path.size) return
        val total = totalDistPx
        if (total <= 0f) return

        distNowPx = distAlongPx.coerceIn(0f, total)
        updateRatioNow()

        val maxSeg = path.size - 2
        val segIdx = findSegmentIndexByDistance(cd, distNowPx).coerceIn(0, maxSeg)
        val a = path[segIdx]
        val b = path[segIdx + 1]

        val segStart = cd[segIdx]
        val segEnd = cd[segIdx + 1]
        val segLen = max(1e-6f, segEnd - segStart)
        val t = ((distNowPx - segStart) / segLen).coerceIn(0f, 1f)

        val px = a.x + t * (b.x - a.x)
        val py = a.y + t * (b.y - a.y)

        drPosPx = PointF(px, py)
        drHasPos = true
    }

    private fun updateRatioNow() {
        ratioNow = when {
            totalSteps > 0 -> (sentStepCount.toFloat() / totalSteps).coerceIn(0f, 1f)
            totalDistPx > 0f -> (distNowPx / totalDistPx).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    private fun applyPendingSteps() {
        if (pendingStepsWhileLocked <= 0) return
        sentStepCount += pendingStepsWhileLocked
        val stepPx = stepLenPxNav()
        distAlongPx = min(totalDistPx, distAlongPx + stepPx * pendingStepsWhileLocked)
        updatePositionFromAlongDistance()
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
            lines.add("Cadence: %.0f spm".format(Locale.US, cadenceSpmNow()))
            lines.add("Pas: %.2f m".format(Locale.US, stepLenNavM))
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
    private fun drawProgressOverlay(c: Canvas) {
        if (!navigationActive) return
        if (navState == NavState.IDLE) return

        val distTotalM = totalDistPx / PX_PER_M
        val distNowM = distNowPx / PX_PER_M

        val stepsDone = sentStepCount
        val stepsTotal = totalSteps
        val stepsLeft = (stepsTotal - stepsDone).coerceAtLeast(0)

        val lines = listOf(
            "Distance : %.1f m".format(Locale.US, distTotalM),
            "Pas totaux : $stepsTotal",
            "Effectues : $stepsDone",
            "Restants : $stepsLeft"
        )

        val pad = 12f
        val lineH = paintUiText.textSize + 6f

        var maxW = 0f
        for (s in lines) {
            maxW = max(maxW, paintUiText.measureText(s))
        }

        val x = 12f
        val y = bmpPlan.height - (lines.size * lineH + pad * 2) - 12f

        val rect = RectF(
            x,
            y,
            x + maxW + pad * 2,
            y + lines.size * lineH + pad * 2
        )

        c.drawRoundRect(rect, 14f, 14f, paintUiBg)

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
        val minGap = 2f * (PX_PER_M * stepRefLenMUser)
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
            } catch (_: IOException) {
                // Reseau down / unreachable / pas de route : on ignore (pas de spam log)
            } catch (_: SecurityException) {
                // Cas rare: permission reseau / policy : on ignore aussi pour eviter le spam
            } catch (t: Throwable) {
                // Vrais bugs (ex: crash inattendu) -> on garde un log
                Log.e(TAG, "udpSendLine unexpected error", t)
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
            } catch (_: IOException) {
                // Reseau down / unreachable : on ignore (pas de spam log)
            } catch (_: SecurityException) {
                // Pareil
            } catch (t: Throwable) {
                Log.e(TAG, "udpSendBatch unexpected error", t)
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
        udpSendLine("CTRL,PARAM,STEPLEN,%.3f".format(Locale.US, stepLenMUser))
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
        overlayCanvas.drawBitmap(bmpBase, 0f, 0f, null)

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
        drawStatusOverlay(overlayCanvas)
        drawHeadingCompass(overlayCanvas)
        drawProgressOverlay(overlayCanvas)
        imageView.setImageBitmap(bmpOverlay)
        imageView.imageMatrix = imageMatrixCurrent
    }

    private fun requestDraw() {
        val now = System.currentTimeMillis()
        if (now - lastDrawMs < minDrawIntervalMs) return
        lastDrawMs = now
        imageView.post { draw() }
    }

    // =================== VIBRATE ===================
    private fun vibrateOnceShort() {
        vibrate(120)
    }

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

        // BLUE thresholds
        val BLUE_H_MIN = 200f
        val BLUE_H_MAX = 240f

        // ORANGE thresholds (#EE771D)
        val ORANGE_H_MIN = 12f
        val ORANGE_H_MAX = 55f

        // Same S/V thresholds as blue (slightly tolerant)
        val S_MIN = 0.25f
        val V_MIN = 0.18f

        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            val hsv = FloatArray(3)
            Color.colorToHSV(bmpPlan.getPixel(x, y), hsv)

            val isBlue =
                (hsv[0] in BLUE_H_MIN..BLUE_H_MAX) &&
                (hsv[1] > S_MIN) &&
                (hsv[2] > V_MIN)

            val isOrange =
                (hsv[0] in ORANGE_H_MIN..ORANGE_H_MAX) &&
                (hsv[1] > S_MIN) &&
                (hsv[2] > V_MIN)

            if (isBlue || isOrange) {
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



