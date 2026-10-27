# Trademark neutralization

Removed direct third-party keyboard branding strings from project documentation/comments and
from user-facing dictionary entries.

Notes:

- The ONNX Runtime Maven coordinate still contains the vendor namespace
  `com.microsoft.onnxruntime`; this is a required runtime package identifier and
  cannot be renamed inside Shade.
- ONNX model binaries may internally contain runtime/operator namespaces such as
  `com.microsoft`. These are binary model/runtime metadata, not Shade branding.
- Generic vocabulary words and legacy data were not rebranded unless they directly
  referenced the keyboard trademark.
