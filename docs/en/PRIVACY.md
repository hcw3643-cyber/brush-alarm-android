# Privacy Notice

English | [简体中文](../PRIVACY.md)

## Production build

The current production build:

- Runs front-camera brushing inference entirely in device memory
- Saves no photographs or video
- Uploads no camera frames, alarm settings, or model outputs
- Contains no advertising, accounts, analytics SDK, or third-party tracking
- Stores alarm configuration only in the local Room database
- Creates and exports no model-inference or alarm-event CSV files

Uninstalling the app or clearing app data removes local alarm settings. The model
is installed with the app and executes through the phone CPU and available
low-level acceleration; no cloud vision service is called.

## Test build

The Debug test package has a distinct name and application ID and may save:

- Timestamp, model ID, inference latency, logit, score, decision, and progress
- Manually assigned “brushing/not brushing” labels
- Alarm scheduling and trigger diagnostics

Test logs contain no video frame, but timestamps, device behavior, and schedules
may still be private. Review and redact them before sharing, and use only a
private channel designated by the maintainer. Never upload them to a public
Issue.

## Volunteer video

Video is accepted only through
[BrushAlarm@163.com](mailto:BrushAlarm@163.com) and with explicit consent in the
email body. Transport and mailbox storage use the 163.com email provider. The
public repository, Issues, pull requests, and model Releases are not acceptable
raw-video channels. The app never collects or uploads volunteer video
automatically. No fixed maximum retention period is promised; deletion and
withdrawal are described in the data contribution notice.

See [`DATA_CONTRIBUTION.md`](DATA_CONTRIBUTION.md) and the copyable
[`DATA_CONTRIBUTION_EMAIL_TEMPLATE.md`](DATA_CONTRIBUTION_EMAIL_TEMPLATE.md).
Any future networking, crash reporting, or data collection must update this
notice and the in-app disclosure before release.
