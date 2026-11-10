# PhoneLink for Wuling Cortez

[Bahasa Indonesia](README.md) · English

Mirror a modern Android phone to the Wuling Cortez headunit, with touch control from the car screen.

The headunit's own PhoneLink app only works on old phones (about Android 6 to 9), because its native code is 32-bit only and uses Android internals that newer versions removed. This is a rewrite in plain Java that speaks the same USB protocol, so the headunit treats it like the original app. Nothing on the headunit is changed.

> Unofficial project. Not affiliated with or endorsed by Wuling, SGMW, or LinkTech. Use at your own risk, and don't operate your phone while driving.

## Download

Get `phonelink-v4.0.apk` from [Releases](https://github.com/arymaulana/phonelink-wuling-cortez/releases) and install it on your phone.

## What works

- Phone screen mirrored to the 1024x600 headunit, in landscape
- Tap and drag on the car screen to control the phone
- Reconnects without asking again if you replug within 15 seconds
- Sound plays over normal Bluetooth pairing with the car, not through this app
- No internet permission: nothing leaves your phone except the USB link to the car

Tested on:

| Phone | Android |
|---|---|
| TECNO LJ7 | 15 |
| Xiaomi 14T (HyperOS) | 14 |

## Requirements

- Android 8.0 or newer
- **USB debugging** turned on. The headunit uses ADB to start the app, so it won't connect without it.
- A USB cable that carries data

## Setup

Open PhoneLink and follow the three steps on screen:

1. **Allow landscape**: lets the app turn the phone sideways to fit the car screen.
2. **Turn on touch**: in Accessibility, switch on **PhoneLink touch**. If the switch is greyed out, open PhoneLink's App info, open its menu, and choose **Allow restricted settings**.
3. **Start mirroring**: plug into the car. PhoneLink opens by itself. Choose **Entire screen** if asked, then tap **Start now**.

Android asks for screen capture approval on every new connection. That's an Android rule apps can't skip.

### Xiaomi, Redmi, POCO

Turn on **Autostart** for PhoneLink in Settings › Apps. Without it, the system may stop the touch service and not restart it.

### Optional: touch only while plugged in

Some banking apps refuse to open while an accessibility service is on. With a computer, run this once:

```bash
adb shell pm grant com.phonelink.phonelinkserver android.permission.WRITE_SECURE_SETTINGS
```

PhoneLink then turns touch on when you plug in and off when you unplug. This permission lets the app change protected system settings, so only grant it to a build you trust. On Xiaomi, turn on **USB debugging (Security settings)** first.

## Known limits

- About 20 full screen updates per second at most, because the headunit only accepts uncompressed frames. Fast map panning can feel slow.
- Apps that protect their content, like Netflix or banking apps, may show black.
- The picture is stretched 17% taller than true shape to reduce empty bands. Set `STRETCH_PERCENT` in `MirrorService.java` to change it.
- Many phone home screens don't support landscape, so part of the home screen may show black and ignore taps. Apps are fine. A launcher that supports landscape fixes it.

## Building

Windows, with the Android SDK installed through Android Studio:

- Build tools 36.1.0 and platform `android-36`
- Android Studio's bundled JDK

```bash
app\build.cmd
```

This runs the protocol self-check, then writes `app\build\phonelink.apk`. If `app\test.keystore` is missing, it creates one. Keep that file: an APK signed with a different key can't update an installed one, and you'd have to uninstall first.

## How it works

The headunit acts as a USB accessory (`LinkTech` / `LinkServer`) and as an ADB host. It runs `am start` to open the app, then switches USB to accessory mode. The app answers the handshake, captures the screen with `MediaProjection`, and sends changed areas as raw RGB565 frames. Touch arrives as coordinates and is replayed with an accessibility service.

Protocol details: [docs/phonelink-protocol.md](docs/phonelink-protocol.md).

## Project layout

```
app/
  AndroidManifest.xml
  build.cmd                 build script, no Gradle
  check/ProtocolCheck.java  self-check run by the build
  res/                      icon, strings, accessory filter
  src/com/phonelink/phonelinkserver/
    MainActivity.java       setup screen
    MirrorService.java      USB link and screen capture
    Protocol.java           message format, pixel and touch math
    TouchService.java       taps from the car screen
    Illustration.java       setup screen pictures
docs/
  phonelink-protocol.md     reverse-engineered protocol notes
```

## License

MIT. See [LICENSE](LICENSE).

---

arydev.id
