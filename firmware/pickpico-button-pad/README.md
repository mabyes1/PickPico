# PickPico four-button BLE pad

This firmware turns an ESP32-S3 into the four physical controls mounted on the PickPico phone stand.

## Default wiring

The current demo uses four 3-pin button modules. Each module's `OUT` connects to its input GPIO, while `VCC` is supplied by a dedicated GPIO held at 3.3 V. The module's `GND` pin remains disconnected. Internal pull-downs keep the inputs LOW while idle, and a press drives `OUT` HIGH.

| Button | OUT GPIO | VCC GPIO | Android action |
| --- | ---: | ---: | --- |
| Approve | 4 | 15 | Complete the newest waiting human-help request with its positive/first action |
| Reject | 5 | 16 | Complete it with its negative/second action |
| Detail | 6 | 17 | Open the newest waiting human-help request on the phone |
| Voice | 7 | 18 | Hold to record from the phone microphone; release to send the WAV reply to the Agent |

Change the `PIN_*` and matching `PIN_POWER_*` constants in `pickpico_button_pad.ino` if the final CS533 shell uses different pins. Do not wire these inputs to GND unless the firmware is also changed to `INPUT_PULLUP` with active-LOW presses.

## BLE protocol

Device name: `PickPico Buttons`

Service UUID: `5f7c0001-8ec5-4d31-9ba1-1b99d0c6a001`

Notify characteristic UUID: `5f7c0002-8ec5-4d31-9ba1-1b99d0c6a001`

Each notification is three bytes: `[version, event, sequence]`.

Events: `1=approve`, `2=reject`, `3=detail`, `4=voice down`, `5=voice up`.

No BLE bonding is required. PickPico scans for the service UUID and reconnects automatically while its node service is running.
