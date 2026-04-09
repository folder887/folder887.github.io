"""ONNX-based ML scorer for the Fraud Detection service.

If the ONNX model file does not exist at the configured path, the scorer
acts as a neutral stub and always returns 0, allowing the rules engine
alone to drive decisions until a real model is deployed.

Expected input features (in this order):
  0  amount_kopecks   – transaction amount in kopecks (int → float32)
  1  hour_of_day      – 0-23
  2  is_new_merchant  – 0 or 1
  3  velocity_1min    – count of txns in last 60 s
  4  velocity_5min    – count of txns in last 5 min

Output:
  A single float in [0, 1] that is scaled to int [0, 1000].
"""

from __future__ import annotations

import os
from typing import Any

import structlog

log: structlog.BoundLogger = structlog.get_logger(__name__)

# onnxruntime is only imported when the model file actually exists so that the
# service can start without the C-extension in environments where ONNX is
# unavailable or the model has not been deployed yet.
_FEATURE_ORDER = [
    "amount_kopecks",
    "hour_of_day",
    "is_new_merchant",
    "velocity_1min",
    "velocity_5min",
]


class OnnxScorer:
    """Thin wrapper around an ONNX inference session."""

    def __init__(self, model_path: str) -> None:
        self._model_path = model_path
        self._session: Any = None  # onnxruntime.InferenceSession | None
        self._input_name: str = ""
        self._enabled: bool = False

        self._load_model()

    def _load_model(self) -> None:
        if not os.path.isfile(self._model_path):
            log.info(
                "onnx_model_not_found",
                path=self._model_path,
                mode="stub",
            )
            return

        try:
            import onnxruntime as ort  # noqa: PLC0415
            import numpy as np  # noqa: PLC0415, F401

            sess_options = ort.SessionOptions()
            sess_options.inter_op_num_threads = 1
            sess_options.intra_op_num_threads = 1

            self._session = ort.InferenceSession(
                self._model_path,
                sess_options=sess_options,
                providers=["CPUExecutionProvider"],
            )
            self._input_name = self._session.get_inputs()[0].name
            self._enabled = True
            log.info("onnx_model_loaded", path=self._model_path)
        except Exception as exc:
            log.warning(
                "onnx_model_load_failed",
                path=self._model_path,
                error=str(exc),
                mode="stub",
            )

    def score(self, features: dict[str, int]) -> int:
        """Return a fraud score in [0, 1000].

        Parameters
        ----------
        features:
            Dictionary with keys from _FEATURE_ORDER. Missing keys default
            to 0.

        Returns
        -------
        int
            0 if model is not loaded (neutral stub), otherwise a value
            derived from the model output scaled to [0, 1000].
        """
        if not self._enabled or self._session is None:
            return 0

        try:
            import numpy as np  # noqa: PLC0415

            feature_vector = [float(features.get(k, 0)) for k in _FEATURE_ORDER]
            input_array = np.array([feature_vector], dtype=np.float32)

            outputs = self._session.run(None, {self._input_name: input_array})
            # Expect first output to be a probability or raw score in [0, 1].
            raw: float = float(outputs[0][0])
            # Clamp and scale to [0, 1000]
            clamped = max(0.0, min(1.0, raw))
            return int(round(clamped * 1000))
        except Exception as exc:
            log.error("onnx_inference_error", error=str(exc))
            return 0

    @property
    def is_enabled(self) -> bool:
        """True when a real model is loaded and inference is active."""
        return self._enabled
