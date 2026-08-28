# Implementation Plan - Add Settings Screen

This plan covers adding a Settings screen to allow future external camera selection, viewing app information, and checking the save file location.

## Proposed Changes

### Resources & UI

#### [NEW] [ic_settings.xml](file:///D:/android app/ShootingScoreApp3/app/src/main/res/drawable/ic_settings.xml)
Create a standard settings (gear) icon.

#### [MODIFY] [activity_main.xml](file:///D:/android app/ShootingScoreApp3/app/src/main/res/layout/activity_main.xml)
Add the settings button to the blue header bar, positioned to the right of the camera toggle.

#### [NEW] [activity_settings.xml](file:///D:/android app/ShootingScoreApp3/app/src/main/res/layout/activity_settings.xml)
Create the layout for the settings screen including:
- **Camera Selection**: A placeholder dropdown/spinner.
- **App Info**: A section showing the app version and name.
- **Save Location**: A section displaying the path where images are stored.

---

### Logic & Navigation

#### [MODIFY] [MainActivity.kt](file:///D:/android app/ShootingScoreApp3/app/src/main/java/com/shootingscore/MainActivity.kt)
Add a click listener to the settings button to launch `SettingsActivity`.

#### [NEW] [SettingsActivity.kt](file:///D:/android app/ShootingScoreApp3/app/src/main/java/com/shootingscore/SettingsActivity.kt)
Implement the settings logic:
- Fetch and display the current app version.
- Display the default image storage path.
- Handle the back button to return to `MainActivity`.

#### [MODIFY] [AndroidManifest.xml](file:///D:/android app/ShootingScoreApp3/app/src/main/AndroidManifest.xml)
Register `SettingsActivity` and set its orientation to landscape to match the app's style.

## Verification Plan

### Automated Tests
- Run `app:assembleDebug` to ensure compilation is successful.

### Manual Verification
1. Open the app and verify the settings icon is visible in the header.
2. Click the settings icon and ensure it navigates to the Settings screen.
3. Verify the placeholder camera option is present.
4. Verify the correct storage path and app info are displayed.
5. Verify the back button returns the user to the main screen.
