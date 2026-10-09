# Architecture

English | [简体中文](../zh-CN/ARCHITECTURE.zh-CN.md)

## Runtime flow

```text
Room alarm record
  └─ AlarmScheduler / AlarmManager exact alarm
       ├─ AlarmReceiver
       ├─ Device-protected session queue + session recovery/quiet-end alarms
       ├─ Foreground reminder service + looping audio/vibration + bounded WakeLock handoff
       └─ Full-screen notification / VerificationActivity
            └─ CameraX front camera
                 └─ BrushMotionAnalyzer
                      ├─ Timestamp-based 8 fps sampling
                      ├─ 16 RGB frames at 192×192 with normalization
                      ├─ On-device ONNX Runtime inference every 0.5 s
                      └─ BrushDecisionFilter
                           └─ Complete the bound session token; advance FIFO or stop outputs
```

## Alarm reliability

Android `AlarmManager` schedules the alarm itself; no permanently running
application process is required. Receipt persists an immutable occurrence/session
before handing off a bounded CPU wake lock to the foreground service. Looping
audio or zero-volume vibration runs independently of Activity, camera, unlock,
and screen state. Normal next occurrences are repaired separately from service
startup; session recovery uses system-owned PendingIntents as a best effort.

Room owns editable configuration; device-protected storage owns registration
intents and the durable session FIFO, exact completed identities, revisions and
quiet allowance. Quiet is accepted only after a separate real alarm-clock end
reminder is registered. Its deadline uses elapsed time on the same boot. Reboot
ends temporary quiet, retains unfinished tasks and used allowance, and registers
a real reminder without starting a mediaPlayback service from the boot broadcast.
Recovery preserves overdue unfulfilled normal occurrences; clock/timezone changes
recompute future calendar occurrences. Only verification of the current token
completes that session. These runtime changes still require build and device validation.

Some vendors still require exact-alarm, autostart, background-launch,
lock-screen-display, and background-power permissions. The first launch presents
one permission guide, and the home-screen settings entry can reopen it. A system
“Force stop” blocks all receivers and alarms until the user launches the app
again; a normal third-party Android app cannot bypass that security boundary.

## Recognition data flow

CameraX callbacks retain only the frames required by the model and only in
memory. Each frame is center-square-cropped, resized to 192×192, converted to
RGB float values, and normalized with Kinetics statistics. The ONNX input is
`[1, 16, 3, 192, 192]` in NTCHW layout; the graph transposes it to the NCTHW
layout used by S3D.

The model emits one logit, and the app applies sigmoid to obtain a score in
`[0, 1]`. Camera capture and model execution are independently serialized so the
UI is not blocked and one ONNX session is never invoked concurrently.

Scores at or above 0.65 accumulate evidence using real elapsed time. Scores
below 0.10 reduce evidence, while the middle band holds it. Verification passes
after six seconds of accumulated high-confidence evidence.

## Data and privacy

Room stores alarm settings locally and explicitly excludes them from Android
cloud backup and device-to-device transfer. Production builds save no camera
frames, generate no inference/alarm CSV files, and contain no log-sharing
`FileProvider`. The real log writers, manual labels, and sharing component exist
only in `app/src/debug/`; the Release source set provides non-persistent
implementations with the same interfaces. Debug and Release use different
application IDs and do not share data.

## Key directories

- `app/src/main/`: runtime code and resources shared by both build types
- `app/src/debug/`: test logging, manual labels, export UI, and test-only
  `FileProvider`
- `app/src/release/`: production implementations that retain the same
  interfaces but do not write or expose logs
- `app/src/test/`: local unit tests
- `training/`: data preparation, training, log analysis, and ONNX export
- `docs/`: public architecture, compatibility, model, privacy, and data policy
- `scripts/`: model download and public-tree audits
