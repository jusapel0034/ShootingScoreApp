# Shooting Score App

Android app for scoring shooting targets using your phone camera.
Optimized for standard 10-ring targets (scores 6-10).

## FIXED VERSION - No OpenCV needed!
This version uses pure Android image processing. Works out of the box.

## How to Run

### 1. Extract ZIP
- Right-click the ZIP file → Extract All
- Remember where you extracted it

### 2. Open in Android Studio
- Open Android Studio
- Click **File → Open**
- Select the **ShootingScoreApp** folder (NOT the ZIP!)
- Click **OK**
- **WAIT** for "Gradle sync" to finish (may take 2-5 minutes on first run)

### 3. Prepare your phone
- On your Android phone: **Settings → About Phone**
- Tap **Build Number** 7 times → "You are now a developer!"
- Go back → **Settings → Developer Options**
- Turn ON **USB Debugging**
- Connect phone to PC with USB cable

### 4. Run the app
- At the top of Android Studio, select your phone from the dropdown
- Click the **green ▶ Play button** (or press Shift+F10)
- App installs and opens on your phone!

## How to make APK file

1. Top menu: **Build → Generate Signed Bundle / APK**
2. Select **APK** → Next
3. Click **Create new...** (Key store path)
   - Fill in anything simple:
   - Password: `123456` (remember this!)
   - Alias: `key0`
   - Click **OK**
4. Click **Next**
5. Select **release** → Check **V1 (Jar Signature)**
6. Click **Finish**

APK location: `ShootingScoreApp/app/release/app-release.apk`

## Using the app

- **CAPTURE**: Take photo of target after shooting → auto-scores
- **SIMULATE**: Add a virtual shot (for testing without ammo)
- **RESET**: Clear all scores and start new session
- **BACK**: Return to camera view after seeing results

## Tips for best results

- Use **good even lighting** on the target
- Hold camera **straight** (not at an angle)
- Fill the camera frame with the target
- Clean your camera lens!
- If detection is poor, adjust `holeThreshold` in ImageProcessor.kt
