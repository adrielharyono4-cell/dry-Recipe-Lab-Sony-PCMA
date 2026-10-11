# dry-Film-lab (Sony PlayMemories Camera App)

**dry-Film-lab** is a high-performance custom camera application tailored for Sony Alpha/Nex or supported camera ecosystem (Sony CX68000 / Gen 2 platform, running on Android API 10 / Gingerbread 2.3.7).

The app is ported and adapted from the open-source **FilmLab** project, optimized specifically for hardware Image Signal Processor (ISP) color transformation without overburdening the system CPU.

---

## 📜 Attribution & Provenance

This project adheres to open-source software ethics by preserving dual attribution and original copyright statements:

* **Original Base Architecture:** *FilmLab* by **Marten Boet**
* **Repository & Base Source:** *Recipe Lab* by **André Domingues (voxivoid)**
* **Ported, Optimized & Modded by:** **Adriel Haryono**

The UI watermark explicitly reads:
`based on FilmLab by Marten Boet | mod: Adriel Haryono`

---

## ⚙️ Key Features & Technical Specifications

* **S-Log Style Preset (Hardware ISP Offloading):**  
  Delivers a flat visual profile optimized for post-production color grading across both still photos (JPEG) and video recording (MP4/MTS). Configured directly via `CameraEx.ParametersModifier`:
  * **Creative Style:** Neutral
  * **Contrast:** -3 (Flattens contrast to preserve highlight and shadow detail)
  * **Saturation:** -2 (Desaturated flat profile for flexible grading)
  * **Sharpness:** -1 (Softens digital edge enhancement for a cinematic look)
  * **Dynamic Range Optimizer (DRO):** Level 5 / Maximum (Lifts shadow details in real-time)

* **Reflective Native Video Recording:**  
  Triggers the native Sony firmware video encoder reflectively via `CameraEx.startMovieRec()` and `CameraEx.stopMovieRec()`. Mapped directly to the physical Sony Movie button scan code (`K_MOVIE` / Code 515).

* **Asynchronous Media Indexing (`AvindexStore`):**  
  Dispatches post-recording media indexing on a low-priority background thread to ensure newly captured `.MP4` or `.JPG` files immediately register in the camera gallery without corrupting the media database.

* **Zero-Allocation Memory Safety:**  
  Eliminates object instantiations (`new`) inside rendering loops or preview callbacks to prevent Dalvik `GC_CONCURRENCY` pauses on legacy Android Gingerbread.

* **Safe File I/O Pipeline:**  
  Still-capture saving features explicit stream cleanup in `finally` blocks, automatic deletion of partial/failed output files, and collision avoidance against existing `DSC_xxxx` filenames.

---

## 🏗️ Software Architecture
┌─────────────────────────────────────────────────────────────┐
│                      User Interface                         │
│   MainActivity / MenuView / PickerView / Status Overlay     │
└──────────────────────────────┬──────────────────────────────┘
                      (Preset Selection)
┌─────────────────────────────────────────────────────────────┐
│                     Recipes & Params                        │
│   Maps "S-Log Style" -> Neutral, Con -3, Sat -2, Sharp -1   │
└──────────────────────────────┬──────────────────────────────┘
                (Apply Preview & Write Parameters)
┌─────────────────────────────────────────────────────────────┐
│                 Sony CameraEx SDK / ISP                     │
│   Hardware-level processing directly to Photo & Video Stream│
└──────────────────────────────┬──────────────────────────────┘
                    (Trigger Video Recording)
┌─────────────────────────────────────────────────────────────┐
│                   AvindexStore Notifier                     │
│   Background thread media registration (MP4/MTS/JPG)        │
└─────────────────────────────────────────────────────────────┘

## 🛠️ Prerequisites & Build Environment

The project compiles cleanly in **Pure Java** without requiring the Android NDK:

* **Operating System:** Windows (Tested on `D:\project\DRY-Recipe-Lab-Sony-PCMA`)
* **Java Development Kit (JDK):** JDK 8 or JDK 11 (Required for legacy Android toolchains)
* **Android SDK:**
  * `targetSdk`: API 10 (Android 2.3.3 / 2.3.7 Gingerbread)
  * `build-tools`: 30.0.3 (Using `d8` compiler and `zipalign`)
  * `android.jar`: API 28 / API 10

---

## 🚀 Building the Application ##

1. **Configure Environment Paths (PowerShell):**
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-8.0.x" # Adjust to your JDK 8/11 directory
   $env:ANDROID_HOME = "D:\android-sdk"
   $env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\build-tools\30.0.3;" + $env:PATH
Execute Build Script (build.cmd):
    Open a terminal in the project root directory and run:
    DOS

    .\build.cmd

    This script handles Java compilation, d8 DEXing, APK packaging, zipalign, and v1 signature verification.

    Output Artifact:
    The compiled APK will be generated in the root directory: D:\project\DRY-Recipe-Lab-Sony-PCMA\dry-Film-lab.apk

##🧪 Running Unit Tests##

Execute the full camera-free test suite on PC without requiring hardware connected:
Bash

# Run all unit tests 
./tools/test.sh

The suite verifies 310 standalone unit tests covering recipe catalog structures, ISP parameter translations, physical key handling, and localization tables (English, Simplified Chinese, Traditional Chinese).
##📱 Deployment to Camera ##

    Connect your Sony NEX-5R to the PC via USB in Mass Storage or Auto mode.

    Install the APK using pmca-cli:
    PowerShell

    pmca-cli.exe install -f dry-Film-lab.apk

    Real-time Logcat Monitoring:
    To inspect live video trigger events and Dalvik memory allocation:
    PowerShell

    pmca-cli.exe logcat -v time DalvikVM:D AndroidRuntime:E CameraEx:V *:S

##⚠️ Hardware Limitations & Important Notes

    S-Log Style vs. True S-Log:

    The "S-Log Style" preset is a Creative Style approximation leveraging Sony ISP hardware parameters. It does not implement a true mathematical S-Log2/S-Log3 logarithmic transfer curve due to video encoding pipeline constraints on the Sony Gen 2 platform.

    NativeBackup Storage Mock:

    In this snapshot, NativeBackup.java acts as an in-memory HashMap mock for unit testing. To persist camera settings permanently across power cycles, the native Sony settings-store interface must be restored before production release.

##📄 Architectural Documentation##

A complete 5-page architectural specification and source-code audit document is available in PDF format: docs/dry-Film-lab_Architecture_and_Spec.pdf
