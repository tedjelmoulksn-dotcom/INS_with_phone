# Indoor Navigation with Smartphone Sensors

Android prototype for indoor pedestrian navigation in environments where GNSS is unavailable or unreliable.

The application combines smartphone inertial sensors, step detection, heading estimation and A* path planning to guide a user along a floor plan. It was developed as an engineering project focused on embedded sensing, signal processing and mobile navigation.

## Highlights

- Reads the accelerometer, gyroscope, magnetometer and Android rotation-vector sensors.
- Detects steps from filtered acceleration signals.
- Estimates travelled distance using a configurable step length.
- Includes a 5 m user calibration procedure.
- Computes a route on a floor-plan image with A*.
- Tracks progress along the route with map matching.
- Detects turns and provides visual and vibration feedback.
- Exchanges sensor and navigation data over UDP.
- Includes OpenCV as an Android module for image-processing experiments.

## System overview

```text
Android sensors
      |
      v
Filtering and step detection
      |
      v
Pedestrian dead reckoning
      |
      +----> UDP data exchange
      |
      v
A* route + map matching
      |
      v
On-screen and vibration guidance
```

## Technical approach

### Step detection

The application filters the acceleration signal in the walking-frequency range and validates peaks using amplitude, timing and motion-consistency criteria. The accepted steps update cadence and travelled distance.

### Heading estimation

Heading is derived from Android rotation-vector sensors and smoothed before use. The application monitors magnetic disturbances and can fall back to a rotation vector that does not rely on the magnetometer.

### Path planning and guidance

The user selects a start point and destination on a floor plan. A* computes a walkable route. Progress is then projected onto the route so the interface can display the current position, remaining path and upcoming turns.

### Calibration

A short 5 m walk estimates the user's average step length, cadence and reference acceleration amplitude. These parameters are saved locally and reused during navigation.

## Technology

| Area | Tools |
|---|---|
| Mobile application | Kotlin, Android SDK |
| Sensors | Android SensorManager |
| Navigation | Pedestrian dead reckoning, A*, map matching |
| Signal processing | Band-pass filtering, peak detection, circular heading filtering |
| Communication | UDP |
| Image processing | OpenCV Android |
| Build | Gradle, JDK 17 |

## Requirements

- Android Studio
- JDK 17
- Android SDK 36
- Android device running Android 7.0 or later (API 24+)
- Accelerometer and gyroscope
- Magnetometer recommended for absolute heading

A physical phone is strongly recommended because emulator sensor data is limited.

## Build and run

```bash
git clone https://github.com/tedjelmoulksn-dotcom/INS_with_phone.git
cd INS_with_phone
./gradlew assembleDebug
```

Open the repository in Android Studio, allow Gradle to synchronize, then run the `app` configuration on a connected device.

The debug APK is generated under:

```text
app/build/outputs/apk/debug/
```

## Main files

```text
app/
└── src/main/
    ├── java/com/example/sensortomatlab/
    │   ├── MainActivity.kt
    │   └── ui/theme/PathOverlayView.kt
    ├── res/
    └── AndroidManifest.xml
opencv/
settings.gradle.kts
```

Most of the current prototype logic is grouped in `MainActivity.kt`: sensor acquisition, filtering, calibration, A*, route tracking, user interface and UDP communication.

## Current status

This repository is an experimental engineering prototype, not a production navigation system. Performance depends on the phone sensors, the floor-plan calibration, the magnetic environment and the user's gait.

Known improvement areas include:

- separating navigation, sensor and UI logic into dedicated classes;
- adding repeatable unit and field tests;
- documenting the UDP packet format;
- measuring positioning error on reference trajectories;
- removing generated OpenCV build artifacts from version control.

## Author

Tedj El Moulk Sinacer
