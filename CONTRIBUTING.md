# Contributing

English | [简体中文](docs/zh-CN/CONTRIBUTING.zh-CN.md)

Participation is subject to the
[Community Code of Conduct](CODE_OF_CONDUCT.md).

Thank you for helping improve Brush Alarm. Unless agreed otherwise in writing,
submitting code or documentation means that you have the right to contribute it
and agree to provide it under GNU GPL v3.0 only (`GPL-3.0-only`) in the root
[LICENSE](LICENSE). That default does not cover model weights, training data, or
volunteer video, which require their own license and consent process.

## Code contributions

1. Search existing Issues first. Open an Issue before making a large behavioral
   change.
2. Create a short-lived branch from `main` and keep each commit focused on one
   topic.
3. Do not commit generated files, APKs, models, signing keys, logs, training
   data, or personal information.
4. Android changes should run at least:

   ```bash
   ./gradlew testDebugUnitTest testReleaseUnitTest
   ./gradlew assembleDebug assembleRelease
   ```

5. Model changes must also update `docs/en/MODEL_CARD.md` with the split, metrics,
   thresholds, hashes, and limitations.

## Bug and compatibility reports

Prefer the repository Issue forms. Include only the device model, Android/vendor
OS version, and textual reproduction steps that are needed for diagnosis. Never
upload the following to a public Issue:

- Identifiable face or bathroom video and photographs
- CSV files, notifications, or settings screenshots that have not been redacted
- Email addresses, phone numbers, device identifiers, or precise schedules
- Third-party data without clear authorization

Remove personal information from diagnostic filenames and content before
sharing. Production builds do not generate these logs by default.

## Brushing video

Brushing video is accepted only at
[BrushAlarm@163.com](mailto:BrushAlarm@163.com) and only with explicit consent
in the email body. Raw video is never accepted through Issues, pull requests, or
the Git repository. See
[`docs/en/DATA_CONTRIBUTION.md`](docs/en/DATA_CONTRIBUTION.md).

## Pull request checklist

- Behavior matches the documentation and adds no hidden upload or tracking.
- Debug-only test behavior cannot enter a Release build.
- New dependencies are recorded in `THIRD_PARTY_NOTICES.md` with name, version,
  purpose, and license.
- No `local.properties`, signing material, model, or build output is committed.
- Changes involving lock-screen behavior, exact alarms, foreground services, or
  Direct Boot include a physical-device validation note.
