# SPDX-FileCopyrightText: 2026 Leo Huang
# SPDX-License-Identifier: GPL-3.0-only

from __future__ import annotations

import torch
from torch import nn
from torchvision.models import (
    MobileNet_V3_Large_Weights,
    MobileNet_V3_Small_Weights,
    mobilenet_v3_large,
    mobilenet_v3_small,
)

from training.model import FRAMES

EMBEDDING_SIZE = 192


class FrameEncoder(nn.Module):
    """Encode each sampled frame once, ready for a streaming ring buffer."""

    def __init__(self, pretrained: bool = True, backbone: str = "small") -> None:
        super().__init__()
        if backbone == "small":
            weights = MobileNet_V3_Small_Weights.IMAGENET1K_V1 if pretrained else None
            network = mobilenet_v3_small(weights=weights)
            output_channels = 576
        elif backbone == "large":
            weights = MobileNet_V3_Large_Weights.IMAGENET1K_V2 if pretrained else None
            network = mobilenet_v3_large(weights=weights)
            output_channels = 960
        else:
            raise ValueError(f"Unsupported backbone: {backbone}")
        self.backbone_name = backbone
        self.features = network.features
        self.pool = nn.AdaptiveAvgPool2d(1)
        self.project = nn.Sequential(
            nn.Linear(output_channels, EMBEDDING_SIZE),
            nn.Hardswish(),
        )
        self._trainable_from = len(self.features)

        # Android already produces Kinetics-normalized RGB. Convert it back to
        # [0, 1], then apply the normalization expected by ImageNet weights.
        self.register_buffer(
            "kinetics_mean",
            torch.tensor([0.43216, 0.394666, 0.37645]).view(1, 3, 1, 1),
        )
        self.register_buffer(
            "kinetics_std",
            torch.tensor([0.22803, 0.22145, 0.216989]).view(1, 3, 1, 1),
        )
        self.register_buffer(
            "imagenet_mean",
            torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1),
        )
        self.register_buffer(
            "imagenet_std",
            torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1),
        )

    def set_trainable_stage(self, last_blocks: int = 0) -> None:
        self._trainable_from = max(0, len(self.features) - last_blocks)
        for index, block in enumerate(self.features):
            for parameter in block.parameters():
                parameter.requires_grad = index >= self._trainable_from

    def train(self, mode: bool = True):
        super().train(mode)
        if mode:
            for index, block in enumerate(self.features):
                if index < self._trainable_from:
                    block.eval()
            # The source videos are sampled in batches of only two clips.
            # Updating ImageNet BatchNorm statistics from such a small and
            # strongly correlated video batch destabilizes fine-tuning even
            # though each clip contains 16 frames. Keep the learned statistics
            # while still allowing gradients through unfrozen convolution
            # weights and affine BatchNorm parameters.
            for module in self.features.modules():
                if isinstance(module, nn.modules.batchnorm._BatchNorm):
                    module.eval()
        return self

    def forward(self, frames: torch.Tensor) -> torch.Tensor:
        rgb = frames * self.kinetics_std + self.kinetics_mean
        normalized = (rgb - self.imagenet_mean) / self.imagenet_std
        encoded = self.features(normalized)
        return self.project(self.pool(encoded).flatten(1))


class TemporalResidualBlock(nn.Module):
    """Cheap temporal modeling over cached frame embeddings."""

    def __init__(self) -> None:
        super().__init__()
        self.depthwise = nn.Conv1d(
            EMBEDDING_SIZE,
            EMBEDDING_SIZE,
            kernel_size=3,
            padding=1,
            groups=EMBEDDING_SIZE,
        )
        self.pointwise = nn.Conv1d(EMBEDDING_SIZE, EMBEDDING_SIZE, kernel_size=1)
        self.activation = nn.Hardswish()
        self.norm = nn.LayerNorm(EMBEDDING_SIZE)

    def forward(self, embeddings: torch.Tensor) -> torch.Tensor:
        residual = embeddings
        values = embeddings.transpose(1, 2)
        values = self.pointwise(self.activation(self.depthwise(values)))
        values = values.transpose(1, 2)
        return self.norm(residual + values)


class TemporalBrushHead(nn.Module):
    """Classify a 16-frame embedding window with negligible extra compute."""

    def __init__(self, use_motion_summary: bool = True) -> None:
        super().__init__()
        self.use_motion_summary = use_motion_summary
        self.blocks = nn.Sequential(TemporalResidualBlock(), TemporalResidualBlock())
        self.attention = nn.Linear(EMBEDDING_SIZE, 1)
        classifier_input = EMBEDDING_SIZE * (3 if use_motion_summary else 1)
        self.classifier = nn.Sequential(
            nn.Linear(classifier_input, 192 if use_motion_summary else 96),
            nn.Hardswish(),
            nn.Dropout(0.15),
            nn.Linear(192 if use_motion_summary else 96, 1),
        )

    def forward(self, embeddings: torch.Tensor) -> torch.Tensor:
        values = self.blocks(embeddings)
        weights = torch.softmax(self.attention(values).squeeze(-1), dim=1)
        pooled = torch.sum(values * weights.unsqueeze(-1), dim=1)
        if self.use_motion_summary:
            motion = torch.abs(values[:, 1:] - values[:, :-1])
            pooled = torch.cat(
                (pooled, motion.mean(dim=1), motion.std(dim=1, unbiased=False)),
                dim=1,
            )
        return self.classifier(pooled).squeeze(1)


class LightweightBrushClassifier(nn.Module):
    """Training wrapper; deployment exports encoder and temporal head separately."""

    def __init__(
        self,
        pretrained: bool = True,
        backbone: str = "small",
        head_version: str = "motion",
    ) -> None:
        super().__init__()
        self.encoder = FrameEncoder(pretrained=pretrained, backbone=backbone)
        if head_version not in ("pooled", "motion"):
            raise ValueError(f"Unsupported temporal head: {head_version}")
        self.temporal_head = TemporalBrushHead(
            use_motion_summary=head_version == "motion"
        )

    def set_trainable_stage(self, last_blocks: int = 0) -> None:
        self.encoder.set_trainable_stage(last_blocks)

    def forward(self, frames: torch.Tensor) -> torch.Tensor:
        batch, time, channels, height, width = frames.shape
        encoded = self.encoder(
            frames.reshape(batch * time, channels, height, width)
        ).reshape(batch, time, EMBEDDING_SIZE)
        return self.temporal_head(encoded)

    @staticmethod
    def deployment_shapes() -> dict[str, tuple[int, ...]]:
        return {
            "frame": (1, 3, 192, 192),
            "embedding_window": (1, FRAMES, EMBEDDING_SIZE),
        }
