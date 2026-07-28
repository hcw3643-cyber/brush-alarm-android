# Brushing Video Contribution Notice

English | [简体中文](../DATA_CONTRIBUTION.md)

> Current status: **collection is open.** Video is accepted only through
> [BrushAlarm@163.com](mailto:BrushAlarm@163.com).

Brushing video may capture an identifiable face, audio, and living environment.
Never upload raw video to a public Issue, pull request, Git repository, or
Release. Submit only through the project email address above.

## What may be submitted

- The contributor must be at least 18 and the only identifiable person shown.
- Submit only self-recorded video that you have the right to authorize.
- Prefer an ordinary room and clean background. Avoid bathrooms, mirrors,
  addresses, identity documents, medication, and private belongings.
- Disable audio when possible. Do not show minors or non-consenting bystanders.
- Real brushing clips and confusing negatives such as holding a toothbrush near
  the mouth or stopping the action are both useful.
- Never perform dangerous or physically uncomfortable actions for data capture.

## Email submission

Send to `BrushAlarm@163.com`, use the subject “Brush Alarm data contribution,”
and retain the following confirmation in the body:

> I am at least 18 years old, I am the person shown in the video, and I have the
> right to submit it. I voluntarily allow Brush Alarm maintainer Leo Huang to
> store, view, crop, label, and use this video to train, validate, and improve
> the brushing-action model, and to publish model weights and aggregate metrics
> that do not contain the original video. I have read this contribution notice.

An attachment without that or equivalent explicit confirmation is not accepted.
The software license does not grant permission to use a video. A copyable
template is available in
[`DATA_CONTRIBUTION_EMAIL_TEMPLATE.md`](DATA_CONTRIBUTION_EMAIL_TEMPLATE.md).

## How video is handled

- Original video is used only to train, validate, and improve this project's
  brushing-action model.
- It does not enter Git, Releases, or a public dataset and is not intentionally
  provided to unrelated third parties.
- Email transport and mailbox storage use the 163.com email provider and remain
  subject to that provider's service and privacy terms.
- The project may publish trained weights, code, and non-identifying aggregate
  results.
- Reasonable account and local-storage security measures are used, but internet
  transmission and electronic storage cannot guarantee zero security incidents.
- All data from one contributor stays in exactly one of train, validation, or
  test, preventing identity leakage across evaluation splits.
- No fixed maximum retention period is promised. The maintainer deletes original
  video when it is no longer needed for model training, validation, and necessary
  review. Until then, the contributor may request deletion as described below.

## Withdrawal

A contributor may write from the original sending address to request deletion of
original video still retained by the maintainer. The maintainer will acknowledge
the request and stop future use within a reasonable time. If a model trained on
the video has already been published, the original can still be deleted, but
historical models and copies downloaded by others cannot be reliably recalled,
and the statistical influence of one sample cannot be guaranteed to disappear.

This notice is not a software license and does not require contributors to make
raw video public.
