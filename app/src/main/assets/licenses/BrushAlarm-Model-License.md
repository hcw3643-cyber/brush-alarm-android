# 模型许可说明

[English](../../MODEL_LICENSE.md) | 简体中文

> 本文件只适用于模型权重，不适用于仓库中的软件代码。软件代码使用根目录
> [LICENSE](../../LICENSE) 中的 GNU GPL v3.0 only。模型不因被 App 加载或与源码在同一
> Release 中发布而自动改为 GPL。

## 许可范围

`brush_classifier.onnx` 中由项目维护者拥有并有权许可的贡献，以
[Creative Commons Attribution-NonCommercial 4.0 International
（CC BY-NC 4.0）](https://creativecommons.org/licenses/by-nc/4.0/legalcode)
提供。

在该范围内，任何人可以为非商业目的复制、分享和修改模型，但必须：

1. 注明“刷牙闹钟社区模型”和本仓库地址；
2. 链接 CC BY-NC 4.0；
3. 标明是否修改；
4. 保留本文件和 `THIRD_PARTY_NOTICES.md`；
5. 不将模型用于收费产品、广告变现、商业集成、付费 API 或其他主要谋取商业利益的用途。

## 第三方边界

该许可只能授予项目维护者实际拥有的权利，不能重新许可第三方材料：

- 网络结构和初始权重来自 TorchVision `S3D_Weights.KINETICS400_V1`；
- 初始权重在 Kinetics-400 上训练；
- 本模型使用 UCF101 的刷牙与困难负样本微调；
- 第二阶段使用一段经维护者同意的本地真机刷牙视频。

TorchVision 源码采用 BSD-3-Clause，但其官方说明指出预训练模型可能受训练数据条款
影响。Kinetics 视频来自 YouTube 并分别受原视频条款约束；UCF101 官方没有提供一个
清晰、统一、可验证的商用或衍生权重许可证。项目不公开或再分发任何 Kinetics、
UCF101 或志愿者原始视频。

因此，本模型是来源透明但许可链尚不完全明确的非商业实验性资产。CC BY-NC 4.0
不应被解释为项目方替第三方授予其无权授予的许可，也不提供不侵权保证。

## 商业用途

当前模型不提供商业许可。即使单独取得维护者授权，使用者仍需自行解决第三方基础
权重和训练数据可能涉及的权利。

## 隐私

模型不是身份识别模型，不以识别具体人物为目的。原始志愿者视频不得随模型、源码、
Issue 或 Release 公开。数据处理原则见 `docs/zh-CN/DATA_CONTRIBUTION.zh-CN.md`。
