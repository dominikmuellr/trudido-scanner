# trudido-scanner

An Android library module for document scanning powered by OpenCV and CameraX. Capture a photo, automatically detect the document corners, and let the user fine-tune the crop - all packaged as a reusable AAR.

## Features

- **Document corner detection** - native C++ + OpenCV finds the four corners of a document after capture
- **Interactive crop UI** - draggable corner handles with a magnifying glass for precise adjustments
- **CameraX integration** - modern, lifecycle-aware camera pipeline
- **Multi-ABI support** - `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`

## Requirements

- Android SDK 24+
- NDK 26.3+
- CMake 3.22+

## Building

```bash
./gradlew build
```

The `scanner` module produces an AAR that can be embedded in any Android project.

## Usage

> **Note:** The library is not yet published to Maven. To use it, clone this repository and include the `:scanner` module directly in your project.

In your `settings.gradle.kts`:

```kotlin
include(":scanner")
project(":scanner").projectDir = file("path/to/TrudidoScannerSDK/scanner")
```

In your `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":scanner"))
}
```

Launch the scanner:

```kotlin
startActivity(Intent(this, ScannerActivity::class.java))
```

> **Status:** After capture, `CropActivity` displays the image with detected corners and lets the user adjust them. The confirm step (perspective transform + returning the cropped image to the caller) is not yet implemented.

## Tech Stack

| Component       | Library          |
| --------------- | ---------------- |
| Camera          | CameraX 1.4.1    |
| Computer Vision | OpenCV 4.10.0    |
| Native          | C++17 / JNI      |
| Language        | Kotlin           |
| Min SDK         | 24 (Android 7.0) |

## License

This project is licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE) for details.
