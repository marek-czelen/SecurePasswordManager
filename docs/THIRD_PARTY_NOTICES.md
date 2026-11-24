# Third-party notices

The release must ship the notices required by the licenses of all included libraries.
The dependency set currently includes:

| Component | Version/source | License/obligation |
|---|---|---|
| AndroidX/AppCompat/Core/RecyclerView/ConstraintLayout | Gradle version catalog | Apache License 2.0 |
| AndroidX Credential Manager | 1.6.0 | Apache License 2.0 |
| Material Components | Gradle version catalog | Apache License 2.0 |
| ZXing Android Embedded | 4.3.0 | Apache License 2.0 |
| Google ML Kit Text Recognition | 16.0.1 | Google terms/license; review current SDK terms |
| Bouncy Castle | 1.79 | Bouncy Castle license |
| CameraX (camera2/lifecycle/view) | 1.4.2 | Apache License 2.0 |
| OpenCV | 4.12.0 | Apache License 2.0; verify the resolved artifact notice |
| Apache POI | 5.4.1 | Apache License 2.0 |
| ONNX Runtime Android | 1.22.0 | MIT License; license included at `app/src/main/assets/docaligner/ONNX_RUNTIME_LICENSE.txt` |
| DocAligner LCNet100 heatmap model | DocAligner 1.1.1 release | Apache License 2.0; license included at `app/src/main/assets/docaligner/LICENSE.txt` |

The versions above are pinned in `gradle/libs.versions.toml`. Generate a release
dependency report and verify the final transitive dependency tree, including native
libraries and the ML Kit model artifacts.
Do not copy this table blindly into a legal notice without checking the exact resolved
artifacts and notices.

The bundled `lcnet100_h_e_bifpn_256_fp32.onnx` model is from
[DocsaidLab/DocAligner](https://github.com/DocsaidLab/DocAligner/releases/tag/1.1.1)
(SHA-256 `f4117b786e3a18470f3865c93f3c2bd69d9b998edd60f385574a5c665e79594e`).
Document detection follows DocAligner's heatmap inference pipeline on-device. Captured
corners continue to be editable in the existing review screen before rectification.
