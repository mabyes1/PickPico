# Third-party notices

## Android Debug Bridge client engine

PickPico's optional PicoADB feature packages an ARM64 Android `adb` client
binary. The binary is pinned by commit and SHA-256 in `app/build.gradle` and is
retrieved from the LADB repository's `jniLibs` directory during the build.

- Mirror repository: `tytydraco/LADB`
- Pinned commit: `60f48029cf9d8e0bc848ca41a7bd76694d4ab796`
- Binary SHA-256: `47EA035FA5ED57F6149A2B025BBBD4B21584C355C05D0400416804715E4C12DE`
- Component license: Apache License 2.0, as declared alongside the mirrored
  `jniLibs` binaries

The Android Debug Bridge implementation originates from the Android Open Source
Project. PickPico does not expose this binary as an unrestricted remote shell;
its privileged capability layer applies explicit command validation and policy.

