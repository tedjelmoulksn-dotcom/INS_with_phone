# Android Indoor Navigation

An Android pedestrian-guidance prototype using smartphone inertial sensors, step detection and floor-plan routing to guide a user indoors without GPS, Bluetooth beacons or Wi-Fi positioning infrastructure.

**Kotlin · Android Sensor API · Signal Processing · Weighted A* · Inertial Sensing**

![Start and destination selection followed by the calculated route](assets/selection_trajet.jpg)

*Route setup on the building floor plan: select the starting point, select the destination and display the calculated path.*

## Overview

The user selects a starting point and a destination on a stored floor plan. The application computes a route, estimates walking progress from calibrated steps and uses heading and gyroscope measurements to validate turns.

The estimated position advances **along the planned route**. This is a route-following research prototype, with no independent free-space position estimate or detection of departures from the route.

| Item | Details |
|---|---|
| Context | Final-year academic project, Instrumentation and Embedded Systems, Sup Galilée, Université Sorbonne Paris Nord, 2025–2026 |
| Authors | Tedj El Moulk Sinacer and Chaima Jouini |
| Supervisor | Christophe Daussy |
| Test environment | Ground-floor corridors of Sup Galilée |
| Status | Academic project completed; research prototype, not actively maintained |

## Key Features

- Step detection with adaptive band-pass filtering and peak validation.
- Walking calibration over a known 5 m distance.
- Heading estimation using Android rotation vectors, with circular smoothing and magnetic-disturbance monitoring.
- Weighted A* route planning on a floor-plan grid.
- Gyroscope-based turn validation.
- On-screen position and heading, route progress and vibration feedback.
- Pause, resume and restart controls.

## Technical Stack

| Component | Implementation |
|---|---|
| Language and UI | Kotlin; a single activity with classic Android Views |
| Hardware | Android smartphone with accelerometer, gyroscope and magnetometer |
| Sensor interfaces | `TYPE_ACCELEROMETER`, `TYPE_GYROSCOPE`, `TYPE_MAGNETIC_FIELD`, `TYPE_ROTATION_VECTOR`, `TYPE_GAME_ROTATION_VECTOR` |
| Sensor scheduling | `SENSOR_DELAY_GAME` |
| Toolchain | Android Studio, Gradle wrapper 8.13, Android Gradle Plugin 8.13, Kotlin 2.0.21, JDK 17 |
| Android target | `minSdk 24`; `compileSdk / targetSdk 36` |

The repository also declares OpenCV Android 4.12.0, CameraX and PhotoView dependencies from earlier experiments. They are not used by the current navigation implementation, but remain part of the build configuration.

## How It Works

The implementation is in [MainActivity.kt](app/src/main/java/com/example/sensortomatlab/MainActivity.kt).

### 1. Step Detection

The acceleration magnitude is filtered through a 0.6–3 Hz biquad band-pass filter. Filter coefficients are recalculated using the measured sampling frequency.

Local peaks are accepted as steps when their amplitude, width (120–400 ms) and spacing match the user's calibrated walking profile.

### 2. Walking Calibration

![Walking calibration screens](assets/etalonnage_marche.jpg)

*Calibration instructions, step counting and the resulting stride length and cadence.*

Before navigation, the user walks a known distance of 5 m. The application estimates stride length, the median step interval and a reference peak amplitude, then stores the values in application preferences.

Calibration is rejected if the detected count falls outside 5–12 steps or if pauses occur.

### 3. Heading Estimation

Android's rotation vector supplies orientation through platform-provided sensor fusion. Additional processing includes:

- first-order circular smoothing on sine and cosine components;
- angle unwrapping around ±180°;
- rate-of-change limiting;
- magnetic-field monitoring and fallback to the magnetometer-free game rotation vector when disturbances are detected.

At startup, heading is aligned with the first route segment. The application uses Android's orientation fusion; no custom Kalman or Madgwick filter is implemented.

### 4. Route Planning

The JPEG floor plan is converted into a traversable grid using white pixels and doors marked in blue or orange.

Routing uses weighted A* with:

| Parameter | Value |
|---|---|
| Grid spacing | 6 pixels |
| Connectivity | 8 neighbours |
| Heuristic | Euclidean distance, weighted by 1.2 |
| Path simplification | Direct line-of-sight checks |
| Turn detection threshold | Direction change of at least 60° |

The weighted heuristic prioritizes search speed; the resulting path is not guaranteed to be the shortest.

### 5. Progress and Turn Validation

Each accepted step advances the estimated position along the route by the calibrated stride length. The floor-plan scale is fixed at 30 pixels per metre.

Near a turn, progression is locked until a physical rotation is detected through vertical-axis gyroscope integration in the expected direction and heading alignment within 10° of the next segment. The code also includes a maximum lock duration.

![Navigation controls](assets/navigation_controles.jpg)

*Navigation view with pause, resume and restart controls.*

## Repository Structure

| Path | Contents |
|---|---|
| `app/` | Kotlin application, Android resources and building floor plan |
| `assets/` | Screenshots used in this README |
| `docs/` | Project report |
| `Rapport/` | Presentation material retained at its original location |
| `opencv/` | Bundled third-party OpenCV Android SDK |
| `gradle/`, `gradlew`, `gradlew.bat` | Gradle wrapper |
| `build.gradle.kts`, `settings.gradle.kts` | Project build configuration |

## Build and Run

### Requirements

- Android Studio and JDK 17.
- Android SDK 36 for the application.
- Android SDK 34, NDK and CMake for the bundled OpenCV module.
- An Android phone with the required sensors and USB debugging enabled.

### Android Studio

1. Clone the repository:

   ```bash
   git clone https://github.com/tedjelmoulksn-dotcom/INS_with_phone.git
   cd INS_with_phone
   ```

2. Open the project in Android Studio and allow Gradle synchronization to finish.
3. Connect the phone and run the `app` configuration.

### Command Line

From the repository root:

```bash
./gradlew assembleDebug
./gradlew installDebug
```

Build and installation have not been rerun as part of this documentation update. They require verification in a complete Android development environment.

## Usage

The current application UI contains French labels.

1. Select **COMMENCER** (Start), walk normally for 5 m, then select **ARRÊT** (Stop) to calibrate.
2. Select and confirm the starting point, then the destination on the floor plan.
3. Walk while holding the phone steadily in front of you, facing the direction of travel.
4. At each turn, physically rotate with the phone to validate the new direction and resume progression.

The floor plan supports zooming and panning. To adapt the prototype to another building, replace `app/src/main/res/drawable/rdc_galilee.jpg`, preserve the traversable-area colour conventions and adjust `PX_PER_M`.

## Evaluation

The project report describes corridor trials with several users and walking speeds, including straight paths, 90° turns, pauses and resumptions.

Reported observations include:

- stable step detection after calibration, including some incidental movements;
- consistent travelled-distance estimates after individual stride calibration;
- successful turn validation through the progression-lock mechanism;
- small deviations on longer routes or after successive turns.

These are qualitative observations from the project report. No quantitative positioning-accuracy campaign, mean position error or large-scale route statistics are available.

## Limitations and Development Priorities

| Current limitation | Development direction |
|---|---|
| Floor plan and calibration tied to one building and floor | Configurable map scale and multi-floor support |
| Progress constrained to the planned route; deviations are not detected | Evaluate independent dead reckoning and external position corrections |
| Step-count and stride-length errors accumulate | Quantify distance error across users and routes |
| Stable handheld use required | Evaluate alternative phone carrying positions |
| Approximately 3,900 lines in one activity | Separate sensing, signal processing, routing and UI modules |
| Only default generated tests are present | Add tests for step detection, angle handling and route planning |
| Earlier dependencies remain in the build | Review and remove unused dependencies |
| Some UI accents have encoding defects | Repair text encoding and add English UI resources |

Free-space dead-reckoning and path-realignment functions exist in the code but are not called in this version. MATLAB UDP streaming used during development is disabled through empty functions.

Further directions identified in the report include advanced sensor fusion and occasional corrections using beacons or Wi-Fi.

## Documentation

[Project report — PDF, 61 pages, French](docs/rapport_navigation_indoor.pdf)

The report covers background research, signal processing, implementation architecture and field trials.

## Authors and Licensing

Developed jointly by **Tedj El Moulk Sinacer** and **Chaima Jouini**, under the supervision of **Christophe Daussy**.

No licence has been specified for the application code. The bundled OpenCV module retains its original Apache 2.0 licence and the third-party notices in `opencv/etc/licenses/`.
