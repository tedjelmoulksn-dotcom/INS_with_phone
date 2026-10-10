# Smartphone Indoor Navigation

An Android prototype for indoor route guidance using the phone's inertial sensors. The application combines pedestrian motion estimation with path planning on an indoor map.

## Technical approach

- Filter acceleration to detect steps and estimate travelled distance.
- Calibrate stride length over a **5 m** reference distance.
- Use orientation information and gyroscope measurements to follow heading changes.
- Compute routes with weighted A* and update navigation from the estimated motion.

The step-detection pipeline uses a **0.6–3 Hz** filtering range. Inertial drift, stride variability and phone orientation affect the resulting position estimate.

## Repository guide

| Folder | Contents |
| --- | --- |
| [app](app/) | Kotlin application, sensor processing, navigation and Android views |
| [OpenCV](opencv/) | Bundled OpenCV Android module |

[Reports](docs/) and [presentation files](Rapport/) accompany the implementation. They provide the detailed methodology and experimental context.

## Build

Open the project in Android Studio with **JDK 17**. The application targets Android SDK 36 and supports API 24 or later. Install the SDK and let Gradle resolve dependencies, then build:

```sh
./gradlew assembleDebug
```

Test sensor behaviour on a physical Android device. This remains a navigation prototype; the repository does not establish deployment-grade positioning accuracy.
