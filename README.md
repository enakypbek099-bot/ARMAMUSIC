# Armamus APK

Capacitor project for Samsung Galaxy S24. The player is `www/index.html`.

## Build

1. Create a new GitHub repository and upload everything from this folder
   (including the hidden `.github` folder).
2. Open the Actions tab, pick "Build APK", press "Run workflow".
3. When it finishes, download `armamus-apk` from the run page, unzip it and install `app-debug.apk`.

## Update the player

Replace `www/index.html` in the repository and run the workflow again.

## Phone settings (Samsung)

Settings > Apps > Armamus > Battery > Unrestricted.
Allow notifications for Armamus.

## Known limits

- "Select folder" is not supported inside Android WebView; use "Select files".
