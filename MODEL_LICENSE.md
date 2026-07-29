# Model License Notice

English | [简体中文](MODEL_LICENSE.zh-CN.md)

> This file applies only to model weights, not to repository software source.
> Software source is licensed under GNU GPL v3.0 only in the root
> [LICENSE](LICENSE). Loading the model from the application or distributing it
> beside source code does not automatically relicense the model under GPL.

## License grant

Contributions in `brush_classifier.onnx` that the project maintainer owns and is
entitled to license are provided under
[Creative Commons Attribution-NonCommercial 4.0 International
(CC BY-NC 4.0)](https://creativecommons.org/licenses/by-nc/4.0/legalcode).

Within that scope, anyone may copy, share, and adapt the model for
noncommercial purposes, provided they:

1. Attribute the “Brush Alarm community model” and this repository.
2. Link to CC BY-NC 4.0.
3. Indicate whether changes were made.
4. Preserve this file and `THIRD_PARTY_NOTICES.md`.
5. Do not use the model in paid products, ad-supported monetization, commercial
   integrations, paid APIs, or other uses primarily intended for commercial
   advantage.

## Third-party boundary

This grant covers only rights actually held by the maintainer and cannot
relicense third-party material:

- The architecture and initial weights come from TorchVision
  `S3D_Weights.KINETICS400_V1`.
- The initial weights were trained on Kinetics-400.
- The model is fine-tuned on UCF101 brushing and hard-negative classes.
- A second stage uses one maintainer-consented local phone brushing video.

TorchVision source uses BSD-3-Clause, but its documentation warns that pretrained
models may remain subject to their training-data terms. Kinetics videos come
from YouTube and remain subject to each source video's terms. UCF101 does not
provide a clear, unified, verifiable commercial or derivative-weight license.
The project publishes no original Kinetics, UCF101, or volunteer video.

The model is therefore a provenance-transparent but license-incomplete
noncommercial experimental asset. CC BY-NC 4.0 must not be interpreted as the
project granting third-party rights it does not hold, and no non-infringement
warranty is made.

## Commercial use

No commercial model license is currently offered. Even separate permission from
the maintainer would not resolve third-party base-weight or training-data rights;
users must review those independently.

## Privacy

The model is not intended to identify individuals. Original volunteer video must
not be published with the model, source, Issues, or Releases. See
`docs/DATA_CONTRIBUTION.md` for data-handling principles.
