<p align="center">
  <img src="https://reareye-gh.uwu.hk/favicon-32.png" alt="REAREye logo" width="160" />
</p>

<h1 align="center">REAREye</h1>

<p align="center">
  A rear-screen enhancement module for Xiaomi 17 Pro / 17 Pro Max.<br>
  Built on LSPosed / Xposed, it provides finer-grained controls for the system framework, Subscreen Center, Theme Manager, and System UI.
</p>

<p align="center">
  <a href="./README.md">中文</a> · <a href="https://github.com/killerprojecte/REAREye">Project Home</a>
</p>

<p align="center">
  <a href="https://github.com/killerprojecte/REAREye/releases"><img src="https://img.shields.io/github/v/release/killerprojecte/REAREye?display_name=tag" alt="GitHub release"></a>
  <a href="https://github.com/killerprojecte/REAREye/blob/main/LICENSE"><img src="https://img.shields.io/github/license/killerprojecte/REAREye" alt="GitHub license"></a>
  <a href="https://github.com/killerprojecte/REAREye/issues"><img src="https://img.shields.io/github/issues/killerprojecte/REAREye" alt="GitHub issues"></a>
  <a href="https://developer.android.com/"><img src="https://img.shields.io/badge/Android-16%2B-3DDC84?logo=android&logoColor=white" alt="Android"></a>
  <a href="https://github.com/LSPosed/LSPosed"><img src="https://img.shields.io/badge/Framework-LSPosed%20%2F%20Xposed-5C6BC0" alt="Framework"></a>
</p>

## Feature Overview

- Allow selected apps to launch on the rear screen, with activity whitelist control.
- Manage background whitelist and process locking to improve rear-screen app residency.
- Customize the music controls whitelist for broader media app compatibility.
- Enhance lyric display with original text, translations, romanization, multiple providers, and
  lyric handling strategies.
- Manage rear widget templates, notification routes, resident cards, per-card template variables,
  and extra display options.
- Install, update, and uninstall widgets and wallpapers through Rear Store.
- Manage rear wallpapers, timed rotation, drag sorting, imports, metadata, previews, and template
  config.
- Remove Theme Manager restrictions, including video wallpaper duration, frame rate, mute, and
  template count limits.
- Remove CN-region Google Play Services restrictions.
- Provide Quick Settings tiles for moving apps between the main and rear screens, plus shortcuts for
  force-stopping target apps.

## Main Features

### Module Settings

- Theme style
    - Supports Miuix and Monet styles.
    - Supports system, light, and dark modes.
- Navigation bar style
    - Supports standard, floating, and floating liquid-glass styles.
- Search bar style
    - Supports default and Miuix styles.
- Store API
    - Supports Aliyun, Cloudflare, and custom API domains.
    - For custom domains, enter only the host name without `https://` or a path.
- WebView hardware acceleration
    - Enabled by default. Disable it if Markdown previews crash on your device.
- Favorite configs
    - Long-press config nodes to save frequently used options.
- Hide launcher entry
    - Optionally hide the REAREye launcher icon.
- More debug output
    - Helps diagnose module behavior and compatibility issues.

### System Framework Enhancements

- Rear-screen activity whitelist
    - Choose which apps may open on the rear screen.
    - Maintain a separate allowlist.
- Allow all activities on the rear screen
    - Useful when testing unsupported apps.
- Background whitelist
    - Helps selected apps stay alive while used on the rear screen.
- Locked app processes
    - Keeps selected apps alive more aggressively.
- Prevent rear screen from returning to Home after locking
    - Preserves the current rear-screen state after the screen is locked.
- Disable rear screen cover
    - Prevents the rear screen from being locked when the main screen enters fullscreen mode.

### Subscreen Center Enhancements

#### Rear Store

- Browse, search, and sort rear widgets and wallpapers.
- View widget details, README files, release notes, downloads, and repository metadata.
- Supports widget-mode and wallpaper-mode installation.
- Shows install progress, update availability, and min / max module version compatibility.
- Uninstalls store widgets and cleans their templates, cards, notification routes, or wallpaper
  records.
- Warns about install conflicts and supports uninstalling existing widgets or forcing overwrite.

#### Rear Personalization Management

- Widget Template Manager
    - Manages mappings from widgets to template files.
    - Imports template files and applies runtime mapping immediately.
    - Shows source and locked state for store-managed widgets.
- Notification Route Manager
    - Maps system notifications to rear-screen widgets by package name and notification rule.
    - Supports exact matches and regex rules.
    - Syncs card state in real time when notifications are posted or removed.
- Card Manager
    - Manages resident card title, target package, widget name, priority, and enabled state.
    - Can disable resident display while keeping the card config.
    - Supports per-card template variable config.
- Card Template Config
    - Supports text, colors, font size, alignment, images, dates, toggles, dropdowns, intents,
      background resources, animation variables, and string variables.
    - Supports light / dark previews, image previews, custom image imports, and external editor
      links.
- Widget Extra Settings Manager
    - Adds widgets that need extra display options.
    - Currently supports hiding notification timestamps for selected widgets.
- Allow focus notices on the rear screen
    - Allows notifications with valid focus / rear-screen params to pass, while still requiring the
      matching widget or template to exist.

#### Wallpaper Manager

- View available rear wallpapers, current wallpaper, and wallpaper status.
- Apply a selected wallpaper.
- Add wallpapers to a rotation schedule.
- Drag to reorder the rotation sequence.
- Configure each wallpaper interval in milliseconds.
- Enable or disable wallpaper rotation.
- Show badges for current, scheduled, unavailable, editable, third-party, and AON status.
- Import MRC / ZIP wallpaper packages.
- Select or generate preview images.
- Edit wallpaper metadata, including title, description, author, designer, category, resource
  subtype, editable state, third-party flag, and AON flag.
- Edit wallpaper template variables when supported.
- Delete wallpapers imported by REAREye or installed from the store.

#### Music Controls

- Music controls whitelist
    - Choose which apps may show music controls on the rear screen.
    - Improves support for apps using standard media controls.
- Forced music controls update
    - Fixes stale rear-screen controls for some apps after track or state changes.

#### Lyrics

- Lyric display mode
    - Supports original text, translations, and romanization.
- Lyric provider switch
    - Supports `Lyricon`.
    - Supports `SuperLyric`.
- Show artist before the first line
    - Applies to static LRC lyrics.
- Live lyric display mode
    - Choose original text or translation.
- Remove native API lyrics
    - Removes lyric metadata provided by Xiaomi's native lyric API.
- Skip title-only metadata updates
    - Avoids lyric interference when only the title changes, such as in Bluetooth metadata churn.
- Take over built-in lyric handling
    - Lets REAREye handle lyric progress and line switching, or falls back to the system logic when
      disabled.

#### Video And Media Behavior

- Force video wallpaper looping.
- Video wallpaper volume
    - Fine-grained playback volume from 0% to 100%.

### Theme Manager Enhancements

- Unlock video wallpaper restrictions
    - Removes video duration limits.
    - Removes default frame-rate limits and tries to load the source frame rate.
- Unlock rear-screen template count limit
    - Removes the original template count cap.
- Unmute video wallpapers
    - Allows adding video wallpapers with audio.
- Rear wallpaper sync
    - Keeps Theme Manager resources and Subscreen Center runtime state aligned with the wallpaper
      manager and import flow.

### Misc

- Remove CN Google Play Services restrictions
    - Used for unlocking Google service features restricted on CN firmware.
    - Examples include Timeline and Location History availability.

### Extra Features

- Quick Settings tiles
    - Move the current app to the rear screen.
    - Move a previously transferred app back to the main screen.
- Home status and quick actions
    - Shows module status, version, build channel, update status, and root status.
    - Provides force-stop shortcuts for Subscreen Center, Theme Manager, and System UI.
- About page
    - Shows project links, docs, QQ group, Coolapk page, contributors, and sponsorship entry.

## Module Scope

Default scope includes these target processes:

- `android`
- `com.xiaomi.subscreencenter`
- `com.android.thememanager`
- `com.android.systemui`

## Requirements

- Android 16 / API 36 or later.
- LSPosed / Xposed compatible environment.
- Xposed minimum version 93.
- A target device with a rear screen. This repository mainly targets Xiaomi 17 Pro / 17 Pro Max.
- Root permission is required for some features or quick actions, such as force-stopping target apps
  and moving apps between the main and rear screens.
- Internet access is required for Rear Store, update checks, and contributor data.

## Installation

1. Download and install the latest APK
   from [Releases](https://github.com/killerprojecte/REAREye/releases).
2. Enable `REAREye` in LSPosed or a compatible framework.
3. Make sure the module scope includes `android`, `com.xiaomi.subscreencenter`,
   `com.android.thememanager`, and `com.android.systemui`.
4. Reboot the device, or at least restart the relevant target apps before configuring the module.
5. Open the module app and enable the features you need.

## Usage Notes

- After changing rear-screen whitelist, music controls whitelist, widget templates, notification
  routes, cards, or wallpaper rotation, restart `com.xiaomi.subscreencenter` or use the force-stop
  shortcut on the Home page, then reopen Subscreen Center.
- Notification routing depends on the bridge between `com.android.systemui` and
  `com.xiaomi.subscreencenter`. If route changes do not apply immediately, restart both target
  processes.
- System framework changes usually require a full reboot to apply reliably.
- Theme Manager changes should be followed by restarting `com.android.thememanager`; reboot if
  needed.
- Some quick actions and system-level features require root authorization.
- Store-managed widgets, cards, and notification routes keep their source metadata. Confirm whether
  you want to uninstall or overwrite them before manual edits.
- This module does not include a rear-screen app launcher. Use ADB or another launcher method if you
  need to start apps directly on the rear screen.
- After unlocking the template count limit, very large template sets should still be tested for
  stability.
- If Markdown previews in store details crash, disable WebView hardware acceleration in module
  settings.

## Use Cases

- Let more apps launch or stay alive on the rear screen.
- Use rear-screen media controls with more third-party music apps.
- Control lyric sources, displayed content, and handling logic.
- Install, update, and uninstall rear widgets or wallpapers from the store.
- Maintain rear widget templates, notification routes, and resident cards yourself.
- Manage rear wallpaper rotation, sorting, imports, metadata, and previews.
- Remove original Theme Manager limits for video wallpapers and template counts.
- Handle CN Google Play Services restrictions without flashing an extra Magisk module.

## Build From Source

The project uses Kotlin, Jetpack Compose, Android Gradle Plugin, and Gradle Wrapper.

- JDK 17.
- Android Compile SDK 37.2 / Build Tools 37.0.0.
- Min SDK 36, target SDK 37.
- For release signing, configure these values in `local.properties`, Gradle properties, or
  environment variables:
    - `androidStoreFile`
    - `KEYSTORE_PASSWORD`
    - `KEY_ALIAS`
    - `KEY_PASSWORD`

Common commands:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

Build outputs are generated under `app/build/outputs/`. Release builds also export a renamed APK.

## Help

- Report issues: [Issues](https://github.com/killerprojecte/REAREye/issues)
- View releases: [Releases](https://github.com/killerprojecte/REAREye/releases)
- Repository: [killerprojecte/REAREye](https://github.com/killerprojecte/REAREye)

## Disclaimer

- This module modifies system and target-app behavior. Evaluate the risk yourself.
- Compatibility can vary across system versions, firmware builds, root solutions, and Xposed
  environments.
- Updates to Subscreen Center, Theme Manager, or System UI may require Hook adaptation.
- You are responsible for any functional issues, data issues, or device risks caused by using this
  module.

## License

See [LICENSE](./LICENSE).
