from pathlib import Path

import onnx
import torch
from onnx.external_data_helper import convert_model_from_external_data

from model import FRAMES, SIZE, BrushVideoClassifier

ROOT = Path(__file__).resolve().parent
checkpoint = torch.load(ROOT / "checkpoints/best.pt", map_location="cpu", weights_only=True)
model = BrushVideoClassifier(pretrained=False)
model.load_state_dict(checkpoint["model"])
model.eval()
sample = torch.zeros(1, FRAMES, 3, SIZE, SIZE)
output = ROOT / "export/brush_classifier.onnx"
output.parent.mkdir(exist_ok=True)
torch.onnx.export(
    model,
    (sample,),
    output,
    input_names=["frames"],
    output_names=["logit"],
    opset_version=18,
    dynamo=True,
)
# PyTorch's new exporter stores weights in a sidecar by default. Android assets
# need a single self-contained file because the session is created from bytes.
model_proto = onnx.load(output, load_external_data=True)
convert_model_from_external_data(model_proto)
onnx.save_model(model_proto, output)
sidecar = output.with_suffix(output.suffix + ".data")
if sidecar.exists():
    sidecar.unlink()
onnx.checker.check_model(onnx.load(output))
print(output, output.stat().st_size, checkpoint.get("metrics"))
