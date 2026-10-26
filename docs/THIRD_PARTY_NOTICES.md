# Third-party notices

The release must ship the notices required by the licenses of all included libraries.
The dependency set currently includes:

| Component | Version/source | License/obligation |
|---|---|---|
| AndroidX/AppCompat/Core/RecyclerView/ConstraintLayout | Gradle version catalog | Apache License 2.0 |
| AndroidX Credential Manager | 1.6.0 | Apache License 2.0 |
| Google Play Billing Library | 9.1.0 | Apache License 2.0 |
| Google Mobile Ads SDK | 25.5.0 | Google terms; review SDK and data disclosure requirements |
| Google User Messaging Platform (UMP) | 4.0.0 | Google terms; review SDK data disclosures |
| Material Components | Gradle version catalog | Apache License 2.0 |
| ZXing Android Embedded | 4.3.0 | Apache License 2.0 |
| Google ML Kit Text Recognition | 16.0.1 | Google terms/license; review current SDK terms |
| Bouncy Castle | 1.79 | Bouncy Castle license |
| CameraX (camera2/lifecycle/view) | 1.4.2 | Apache License 2.0 |
| OpenCV | 4.12.0 | Apache License 2.0; verify the resolved artifact notice |
| Apache POI | 5.4.1 | Apache License 2.0 |

The versions above are pinned in `gradle/libs.versions.toml`. Generate a release
dependency report and verify the final transitive dependency tree, including native
libraries and the ML Kit model artifacts.
Do not copy this table blindly into a legal notice without checking the exact resolved
artifacts and notices.
