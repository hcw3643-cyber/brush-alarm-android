from __future__ import annotations

import torch
from torch import nn
from torchvision.models.video import S3D_Weights, s3d

FRAMES = 16
SIZE = 192
SAMPLE_FPS = 8.0


class BrushVideoClassifier(nn.Module):
    """Kinetics-pretrained S3D video network fine-tuned for tooth brushing."""

    def __init__(self, pretrained: bool = True) -> None:
        super().__init__()
        weights = S3D_Weights.KINETICS400_V1 if pretrained else None
        backbone = s3d(weights=weights)
        self.features = backbone.features
        self.pool = nn.AdaptiveAvgPool3d(1)
        self.classifier = nn.Linear(1024, 1)
        self._trainable_from = len(self.features)

        # S3D was already trained to distinguish "brushing teeth" in Kinetics-400.
        # Reusing that class head gives binary fine-tuning a useful, interpretable start.
        if pretrained:
            class_index = S3D_Weights.KINETICS400_V1.meta["categories"].index("brushing teeth")
            source = backbone.classifier[1]
            with torch.no_grad():
                self.classifier.weight.copy_(source.weight[class_index, :, 0, 0, 0])
                self.classifier.bias.copy_(source.bias[class_index].reshape(1))

    def set_trainable_stage(self, last_blocks: int = 0) -> None:
        self._trainable_from = max(0, len(self.features) - last_blocks)
        for index, block in enumerate(self.features):
            for parameter in block.parameters():
                parameter.requires_grad = index >= self._trainable_from

    def train(self, mode: bool = True):
        super().train(mode)
        if mode:
            # Keep frozen BatchNorm running statistics unchanged on the small dataset.
            for index, block in enumerate(self.features):
                if index < self._trainable_from:
                    block.eval()
        return self

    def forward(self, frames: torch.Tensor) -> torch.Tensor:
        # Android supplies N,T,C,H,W; S3D consumes N,C,T,H,W.
        encoded = self.features(frames.permute(0, 2, 1, 3, 4))
        encoded = self.pool(encoded).flatten(1)
        return self.classifier(encoded).squeeze(1)
