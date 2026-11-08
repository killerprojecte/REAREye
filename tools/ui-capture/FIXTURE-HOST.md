# Production-page fixture capture host

## Status and scope

`app/src/debug` adds `hk.uwu.reareye.ui.capture.UiCaptureActivity`. It renders the
existing production page composables, not lookalike components. The yellow
`FIXTURE HOST` label is intentional and must remain visible in delivered evidence.
This does **not** constitute full-app integration coverage or satisfy the request
for all screens by itself. No screenshot or video has been produced by adding this
host; successful compilation and capture still have to be verified.

There is no release manifest entry or production startup change. MainActivity's
version gate, onboarding, remote-preferences readiness gate, and real navigation
remain unchanged. Do not install the fixture debug APK on a user's real device:
use a disposable emulator with no Xposed service, no user data, and networking
disabled. The existing Application still starts its usual contributor/preference
initialization; this host does not claim to replace those global services.

## Deterministic routes

Start a new Activity instance for every route. Data resets with each instance.

| `page` extra | Actual production UI | Injected data |
| --- | --- | --- |
| `cards` | ConfigDashboard / CardManagerScreen | Existing guide clock card and component |
| `card-editor` | CardManagerScreen edit dialog | Same card; existing guide action opens editor |
| `components` | ConfigDashboard / BusinessManagerScreen | Existing guide component |
| `component-editor` | BusinessManagerScreen edit dialog | Same component; guide action opens editor |
| `wallpapers` | ConfigDashboard / RearWallpaperManagerScreen | Existing synthetic gradient wallpaper |
| `more` | ConfigDashboard More tab | Real categories; navigation callbacks disabled |
| `scene-routes` | SceneRouteManagerScreen | Empty in-memory preference store |
| `business-extras` | BusinessExtraConfigManagerScreen | Empty in-memory preference store |
| `business-extra-editor` | BusinessExtraConfigScreen | `demo_clock`, default values |
| `custom-bounds` | CustomBoundsCompatManagerScreen | Empty in-memory preference store |

The host reuses `FeatureGuideDemoState`, `GuideMemoryPrefs`, and
`FeatureGuideDemoScreen`. Card, component and wallpaper interactions retain their
existing production guide side-effect substitution. Other managers accept memory
prefs directly, but operations that require remote files or device integrations
are **not** simulated: capture their visible/default state, do not claim saves,
imports, installations or hook effects work. Package selectors depend on the
emulator package catalog. Root Back callbacks intentionally do nothing; dialogs
retain their production dismissal handlers.

## Candidate captures

Build `:app:assembleDebug` with the repository's normal SDK, signing and dependency
setup. The configured application ID is `hk.uwu.reareye` (no debug suffix),
version name is `2.0.0` before build-channel decoration, and minSdk is **36**.
Use an API 36+ emulator. Baseline and candidate occupy the same package: use a
fresh emulator/data reset per build and retain the APK hash, since version codes
are derived from commit count. Do not assume a downgrade install preserves a fair
fixture comparison. Release APKs do not contain the host. On a fixed-size, fixed-density,
fixed-locale emulator with animation scales at 1:

```sh
adb -s "$SERIAL" shell am start -S -W \
  -n hk.uwu.reareye/.ui.capture.UiCaptureActivity \
  --es page cards --es backend miuix --ez dark false
```

Repeat with `backend=mochi`, both dark values, and all routes above. Invalid
backend/page names fail rather than silently rendering another page. The backend
extra selects the actual `AppTheme` backend. It does not guarantee every individual
control has migrated; review captured pixels and source coverage separately.

Use `capture.py` for screenshots and MP4 evidence. For a prelaunched activity,
omit `launch` from the scenario. Record real interactions (dashboard tab switches,
card/component editor opening and dismissal, toggles, scroll) for **both** backends.
A still PNG is not animation evidence. Keep entry/reveal recordings separate from
settled screenshots and record duration/device/locale/animation settings. Check
that images are not loading/error/blank frames. Verify repeated dialog dismiss,
reopen and tab changes, not only the first happy path.

## Baseline captures

The baseline `e554a382dabdb79994fa11a4482c072c9f968c82` already contains the same guide
seam but no MixUI/Mochi backend. Make a **separate** checkout of that commit, then run:

```sh
python3 tools/ui-capture/prepare-baseline-fixture.py /path/to/baseline-checkout
```

This copies only the new debug source set and replaces the isolated theme adapter
with an original `AppTheme` call. It refuses an existing destination debug source
set and refuses the candidate checkout. Use `backend=miuix` only. The baseline
adapter explicitly rejects Mochi. Record the source commit, overlay revision,
APK SHA-256 and `fixture-host` capture kind. Never label this overlay build as an
unmodified production launch. Compare the same fixture page/data, theme, locale,
font scale and device between baseline MIUIX and candidate MIUIX/Mochi.

## Outstanding real-screen coverage

The following remain outside this first fixture host:

- MainActivity integrated shell/bottom navigation, activation/version gates and
  all onboarding pages. These require a provisioned device or an explicit
  separately scoped integration fixture; production gates must not be bypassed.
- Home: direct Xposed activation status, root probes, preset-pack repository and
  update lookups are not injected.
- Full ConfigScreen/category/search/favorites flows: context-created remote prefs
  and navigation state are not injected by the guide fixture.
- RearStore listing/detail/download/install and wallpaper/template editors:
  repository/network/assets/remote file dependencies need populated fixtures.
- Mary chat, history/sidebar, voice and settings: ViewModel/controller,
  AgentSettingsStore, configured providers, microphone and persistence are not
  substituted. Never use real credentials to create screenshots.
- About/contributors/licenses, scripts list/editor/import/export, app selectors,
  lyrics settings, nested manager dialogs and remaining configuration controls:
  need explicit route/state fixtures and individual capture verification.

Empty manager fixtures are only empty-state evidence, not populated-list coverage.
The guide wallpaper is synthetic; no actual wallpaper installation is exercised.
Keep these gaps in the comparison manifest until artifacts prove each route.

## Local simulated rendering

Use the existing Robolectric 4.17 native graphics / Compose pixel renderer, without an emulator:

`bash tools/run-local-gradle.sh -PisPublicBeta=true -PuiCapture=true :app:testDebugUnitTest`

`isPublicBeta` uses the repository's existing UI-only packaging mode without native hook libraries. It is not a full native integration build. The local Android SDK must be configured normally. `GRADLE_BIN` can select an already-installed Gradle 9.6; otherwise the checked-in wrapper is used. The runner isolates writable Android settings and derives the current invocation's proxy without printing credentials.

Output: `build/reports/ui-capture/<backend>/<theme>/<page>/`. These are real production-composable fixture PNGs, with sampled press/release frames on the cards route. They require visual review before claiming an animation effect. This test has been authored but has not yet completed a local rendering run. The recovered host includes 15 routes: the 10 manager/editor routes plus onboarding, about, scripts, unconfigured Mary settings and a synthetic version warning. These are not every application state. Onboarding retains actual permission probes, About retains real device/build metadata, scripts start empty on fresh test data, and Mary settings has no configured provider.

Run `python3 tools/check_ui_boundary.py` for strict whole-repository MIUIX reference checking outside mixui. It currently fails because migration is incomplete; no exceptions are silently allowed.
