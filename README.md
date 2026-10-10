# Android Indoor Navigation

Android research prototype for indoor guidance using calibrated steps, inertial sensing and weighted A* routing. The app advances an estimated position along a selected floor-plan route and checks turns using heading and gyroscope measurements.

![Project illustration](assets/selection_trajet.jpg)

## Repository guide

| Location | Contents |
|---|---|
| [app/](app/) | Kotlin application, navigation logic and Android resources |
| [opencv/](opencv/) | Bundled OpenCV Android module |
| [docs/](docs/) | Navigation report |
| [Rapport/](Rapport/) | Project presentation |
| [assets/](assets/) | Calibration and navigation screenshots |

## Getting started

Open the project in Android Studio with JDK 17, synchronise Gradle, and run the app on an Android phone with motion sensors. Select a route and complete the 5 m walking calibration before starting navigation.

## Project context

Final-year project by Tedj El Moulk Sinacer and Chaima Jouini, supervised by Christophe Daussy at Sup Galilée. This prototype follows a planned route; it does not independently detect departures from that route.
