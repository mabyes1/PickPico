# Third-party notices

## Android Debug Bridge client engine

PickPico's optional PicoADB feature packages an ARM64 Android `adb` client
binary. The binary is pinned by release and SHA-256 in `app/build.gradle`.
This build supports 16 KB pages and retains TLS / SPAKE2 pairing.

- Source: https://github.com/che2n3jigw/aosp-adb-arm64
- Release: `v36.0.1-baklava-16kb-pair` (`adb_arm64` packaged as `libadb.so`)
- Source commit: `9a2f3b557e92192110cea0fb20429f46238fedf5`
- Binary SHA-256: `B73CDFB58E85ED7EF3DC95E80A5F2F9B99EFDA6B3FA5F7353A1337E2F09D8818`
- Component license: Apache License 2.0; upstream `NOTICE` and `MODULE_LICENSE_APACHE2`

The Android Debug Bridge implementation originates from the Android Open Source
Project. PickPico exposes ADB shell and command execution through its capability
layer, using the paired phone, Hyper Mode, and the user's approval policy.

## Node.js for Mobile Apps

- Source: https://github.com/capawesome-team/nodejs-mobile
- Release: `v18.20.4-capawesome.1`
- Source commit: `5b92ed1534bc2c36b922fade47d89f23c9791941`
- Android archive SHA-256: `1B3C7979C81AEC89A7F51B29AF1F4875A5D637727AD6E2C392CDF2E127715DA9`
- Based on upstream `release18-20-4+16kb-fix`; Node remains 18.20.4.
- License: upstream Node.js `LICENSE`, including its bundled dependency notices.

The fork's changes from upstream commit `ff4e063f1f1911047c067335ad0a3d81336236ca`
are confined to its release workflow. Its Android binaries are rebuilt with
16 KB ELF alignment. PickPico retains the same Node API and isolated processes.

