<p align="center">
  <img src="design/brush-alarm-logo-source.png" width="160" alt="Brush Alarm logo">
</p>

# Brush Alarm

English | [简体中文](README.md)

An Android alarm clock that stops only after on-device video verification
detects that the user is brushing their teeth.

> [!IMPORTANT]
> This is community-driven experimental free software. End-to-end testing has
> only been completed on a vivo X300 running Android 16 / OriginOS 6. It is not
> a medically validated health product, has not been certified across Android
> vendors, and cannot guarantee wake-up. Keep a system alarm or another backup.

## Project scope

- The software source code is licensed under
  [GNU GPL v3.0 only](LICENSE). Anyone may run, study, modify, and redistribute
  it, including commercially, subject to the GPLv3 source and same-license
  obligations for distributed derivatives and APKs.
- `brush_classifier.onnx` is not GPL software source. It remains subject to the
  separate [model license and provenance limitations](MODEL_LICENSE_EN.md).
  Licensing the application does not grant missing rights to model weights or
  training data.
- The production app does not upload camera frames and does not write or export
  inference logs.

## Implemented features

- Create, edit, enable, disable, and delete alarms.
- Select each repeat day from Monday through Sunday.
- Hour and minute wheels.
- Continuous mode: the alarm keeps sounding until brushing verification passes.
- Roommate mode: the alarm may be silenced temporarily, but rings again every
  minute until verification passes.
- Exact `AlarmManager` alarms, full-screen notifications, a foreground ringing
  service, and a CPU wake lock.
- Alarm re-registration after screen-off operation, recent-task dismissal,
  device reboot, and Direct Boot.
- On-device front-camera video inference with ONNX Runtime; frames are processed
  in memory.
- The verification screen disables Back, hides the recent-task UI, and requests
  Android screen pinning.
- A separate Debug test application retains numerical inference logs and manual
  labels without mixing them into the production build.

## Known limitations

- Android vendors may impose extra restrictions on autostart, background
  activity launches, lock-screen display, and battery usage. The first launch
  presents a consolidated permission guide; it can later be reopened from the
  settings button on the home screen.
- A normal Android application cannot override a system-level “Force stop” or
  provide Device Owner / kiosk-level lock-down.
- The current model has limited user and real-device coverage. It may miss
  brushing with unfamiliar faces, toothbrushes, angles, or lighting.
- See [docs/en/COMPATIBILITY.md](docs/en/COMPATIBILITY.md) for the current compatibility
  scope and test method.

## Download the app

Download the installer from the
[`v1.0.0` release page](https://github.com/hcw3643-cyber/brush-alarm-android/releases/tag/v1.0.0).

### Choose an APK

- **Recommended:** `BrushAlarm-v1.0.0-arm64-v8a.apk`
  Suitable for most recent Android phones and smaller in size.
- **Compatibility version:** `BrushAlarm-v1.0.0-universal.apk`
  Use this version if the recommended APK cannot be installed or you are unsure
  whether your device is compatible.

### Installation and first use

1. Download the APK and allow installation from the current source when Android
   asks.
2. Open the app and follow the first-launch guide to grant notification, camera,
   and exact-alarm permissions.
3. Follow the in-app settings guide to allow autostart, background operation,
   and lock-screen display.
4. Set an alarm a few minutes ahead and test it while the phone is locked and
   after the app has been dismissed from recent tasks.

The brushing-recognition model is already included in the APK. No separate model
download is required.

End-to-end testing has currently been completed only on a vivo X300 running
Android 16 / OriginOS 6. Background restrictions vary between Android vendors.
Keep a system alarm as a backup until you have confirmed reliable operation on
your device.

## Build from source

### 1. Requirements

- Android Studio Ladybug or newer
- JDK 17
- Android SDK 35
- A physical device running Android 8.0 (API 26) or newer

The stable application ID is `io.github.hcw3643cyber.brushalarm`. Earlier
internal builds used `com.example.brushalarm`; Android treats those as a
different application and does not migrate alarm settings.

### 2. Fetch the model

The model is not committed to Git history. It is distributed as a separate
GitHub Release asset and must be placed at:

```text
app/src/main/assets/brush_classifier.onnx
```

```bash
./scripts/fetch-model.sh
```

On Windows PowerShell:

```powershell
.\scripts\fetch-model.ps1
```

It can also be downloaded manually from the `model-v1.0.0` Release. See
[docs/en/MODEL_CARD.md](docs/en/MODEL_CARD.md) for provenance, input format, metrics,
and limitations.

### 3. Build

```bash
./gradlew testDebugUnitTest testReleaseUnitTest
./gradlew assembleDebug assembleRelease
```

- `debug`: separate test package with a `-test` version suffix, numerical logs,
  and manual labels.
- `release`: production package with no inference/alarm CSV generation and no
  log-export UI.

## Current recognition model

The current S3D model receives an approximately two-second motion window:

- 16 RGB frames
- Timestamp-based sampling at 8 fps, spanning approximately 1.875 seconds
- Center crop and resize to 192×192
- One overlapping-window prediction every 0.5 seconds
- High/low confidence thresholds of 0.65/0.10
- Six seconds of accumulated high-confidence evidence required

It starts from TorchVision S3D/Kinetics-400 weights, is fine-tuned on UCF101
brushing and hard-negative classes, and receives limited domain adaptation from
one maintainer-consented phone recording. The third-party dataset terms are not
fully explicit. The model is therefore released as a separate experimental
asset, does not claim redistribution rights to UCF101 source videos, and
contains no source training recordings. See
[docs/en/MODEL_CARD.md](docs/en/MODEL_CARD.md) and
[MODEL_LICENSE_EN.md](MODEL_LICENSE_EN.md).

MoViNet A0 has completed training on the same UCF split, streaming TFLite
export, and numerical parity checks. Its video-level F1 is 93.67%, close to
S3D's 93.83%, at roughly 65 times fewer theoretical operations. It still lacks
phone-domain validation and has not replaced the production model. See
[training/MOVINET_A0_EN.md](training/MOVINET_A0_EN.md).

## Contributing

- Use the repository Issue forms for general bugs, device compatibility, and
  numerical model feedback.
- Never upload identifiable face video, bathroom footage, raw logs, or other
  personal data to a public Issue, pull request, or Git repository.
- Volunteer brushing-video collection is open only through
  [BrushAlarm@163.com](mailto:BrushAlarm@163.com). Read the data contribution
  notice and include its explicit confirmation before sending anything.
- Code contribution rules: [CONTRIBUTING_EN.md](CONTRIBUTING_EN.md)
- Data contribution principles:
  [docs/en/DATA_CONTRIBUTION.md](docs/en/DATA_CONTRIBUTION.md)

## Repository layout

```text
.
├── app/                 Android application, resources, and tests
├── training/            Training, calibration, and model export code
├── scripts/             Model download and pre-release audit scripts
├── docs/                Architecture, compatibility, model, privacy, and data docs
├── design/              Logo source
├── .github/             Issue forms
├── README.md            Chinese README
├── README_EN.md         English README
├── LICENSE              GNU GPL v3.0 software license
├── MODEL_LICENSE.md     Model-weight licensing boundary
└── THIRD_PARTY_NOTICES.md
```

See [docs/en/ARCHITECTURE.md](docs/en/ARCHITECTURE.md) for the detailed data flow and
component responsibilities.

## Privacy and security

The production build analyzes camera frames only in device memory and does not
save or upload them. Test-build logs contain numerical model output, timestamps,
and device information but no image, video, or audio data; they should still be
reviewed manually before sharing. See [docs/en/PRIVACY.md](docs/en/PRIVACY.md),
and follow [SECURITY_EN.md](SECURITY_EN.md) for security reports.

## Licensing

- Maintainer: Leo Huang
- Original software, repository documentation, and original artwork:
  GNU GPL v3.0 only (`GPL-3.0-only`)
- Project-licensable portions of the current model: CC BY-NC 4.0
- Third-party components and base weights: their original terms remain in force
- The logo and “Brush Alarm” name do not grant trademark rights or permission to
  impersonate an official build

GPL permits commercial use and paid distribution, but a distributor must comply
with GPLv3 and cannot turn a derivative into closed proprietary software. Model
weights are outside the root `LICENSE`; distribution of an APK containing the
model must also comply with [MODEL_LICENSE_EN.md](MODEL_LICENSE_EN.md) and the relevant
third-party terms.

See [LICENSE](LICENSE), [MODEL_LICENSE_EN.md](MODEL_LICENSE_EN.md), and
[THIRD_PARTY_NOTICES_EN.md](THIRD_PARTY_NOTICES_EN.md).
