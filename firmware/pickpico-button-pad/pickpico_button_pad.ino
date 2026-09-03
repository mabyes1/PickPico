#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>

// PickPico button-pad protocol v1.
// Packet: [protocolVersion, event, sequence]
static const char *DEVICE_NAME = "PickPico Buttons";
static const char *SERVICE_UUID = "5f7c0001-8ec5-4d31-9ba1-1b99d0c6a001";
static const char *EVENT_CHARACTERISTIC_UUID = "5f7c0002-8ec5-4d31-9ba1-1b99d0c6a001";

static const uint8_t PROTOCOL_VERSION = 1;
static const uint8_t EVENT_APPROVE = 1;
static const uint8_t EVENT_REJECT = 2;
static const uint8_t EVENT_DETAIL = 3;
static const uint8_t EVENT_VOICE_DOWN = 4;
static const uint8_t EVENT_VOICE_UP = 5;

// ESP32-S3 demo wiring for the four 3-pin button modules.
static const uint8_t PIN_APPROVE = 4;
static const uint8_t PIN_REJECT = 5;
static const uint8_t PIN_DETAIL = 6;
static const uint8_t PIN_VOICE = 7;

// Dedicated low-current 3.3 V sources for the four simple button modules.
// Each module only needs VCC + OUT; its GND pin can stay unconnected because
// the ESP input itself is held LOW with INPUT_PULLDOWN while idle.
static const uint8_t PIN_POWER_APPROVE = 15;
static const uint8_t PIN_POWER_REJECT = 16;
static const uint8_t PIN_POWER_DETAIL = 17;
static const uint8_t PIN_POWER_VOICE = 18;

static const uint32_t DEBOUNCE_MS = 28;

BLECharacteristic *eventCharacteristic = nullptr;
volatile bool connected = false;
uint8_t sequenceNumber = 0;

struct ButtonState {
  uint8_t pin;
  uint8_t pressEvent;
  uint8_t releaseEvent;
  bool rawPressed;
  bool stablePressed;
  uint32_t changedAtMs;
};

static const char *buttonName(uint8_t pin) {
  switch (pin) {
    case PIN_APPROVE: return "GPIO4";
    case PIN_REJECT: return "GPIO5";
    case PIN_DETAIL: return "GPIO6";
    case PIN_VOICE: return "GPIO7";
    default: return "GPIO?";
  }
}

ButtonState buttons[] = {
    {PIN_APPROVE, EVENT_APPROVE, 0, false, false, 0},
    {PIN_REJECT, EVENT_REJECT, 0, false, false, 0},
    {PIN_DETAIL, EVENT_DETAIL, 0, false, false, 0},
    {PIN_VOICE, EVENT_VOICE_DOWN, EVENT_VOICE_UP, false, false, 0},
};

class PickPicoServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    connected = true;
    Serial.println("BLE central connected");
  }

  void onDisconnect(BLEServer *server) override {
    connected = false;
    Serial.println("BLE central disconnected");
    delay(30);
    BLEDevice::startAdvertising();
    Serial.println("BLE advertising restarted");
  }
};

void sendEvent(uint8_t eventId) {
  Serial.print("event=");
  Serial.print(eventId);
  Serial.print(" seq=");
  Serial.println(sequenceNumber);
  if (!connected || eventCharacteristic == nullptr || eventId == 0) {
    return;
  }
  uint8_t packet[3] = {PROTOCOL_VERSION, eventId, sequenceNumber++};
  eventCharacteristic->setValue(packet, sizeof(packet));
  eventCharacteristic->notify();
}

void setup() {
  Serial.begin(115200);

  const uint8_t powerPins[] = {
      PIN_POWER_APPROVE,
      PIN_POWER_REJECT,
      PIN_POWER_DETAIL,
      PIN_POWER_VOICE,
  };
  for (uint8_t pin : powerPins) {
    pinMode(pin, OUTPUT);
    digitalWrite(pin, HIGH);
  }

  for (auto &button : buttons) {
    // The 3-pin button modules expose GND / OUT / VCC. We intentionally leave
    // the module GND pins disconnected and use the ESP32-S3's internal pull-down
    // so each button only needs VCC + OUT wiring. OUT is HIGH while pressed.
    pinMode(button.pin, INPUT_PULLDOWN);
    button.rawPressed = digitalRead(button.pin) == HIGH;
    button.stablePressed = button.rawPressed;
    button.changedAtMs = millis();
  }

  BLEDevice::init(DEVICE_NAME);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new PickPicoServerCallbacks());

  BLEService *service = server->createService(SERVICE_UUID);
  eventCharacteristic = service->createCharacteristic(
      EVENT_CHARACTERISTIC_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  eventCharacteristic->addDescriptor(new BLE2902());
  uint8_t initialPacket[3] = {PROTOCOL_VERSION, 0, 0};
  eventCharacteristic->setValue(initialPacket, sizeof(initialPacket));
  service->start();

  // Keep the primary advertisement and scan response comfortably below the
  // legacy 31-byte BLE limit. The 128-bit service UUID lives in the primary
  // packet and the human-readable name lives in the scan response.
  BLEAdvertisementData advertisementData;
  advertisementData.setFlags(0x06);
  advertisementData.setCompleteServices(BLEUUID(SERVICE_UUID));

  BLEAdvertisementData scanResponseData;
  scanResponseData.setName(DEVICE_NAME);

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->setAdvertisementData(advertisementData);
  advertising->setScanResponseData(scanResponseData);
  advertising->start();

  Serial.println("PickPico Buttons ready");
  Serial.println("BLE advertising started");
}

void loop() {
  uint32_t now = millis();
  for (auto &button : buttons) {
    bool pressed = digitalRead(button.pin) == HIGH;
    if (pressed != button.rawPressed) {
      button.rawPressed = pressed;
      button.changedAtMs = now;
    }
    if (pressed == button.stablePressed || now - button.changedAtMs < DEBOUNCE_MS) {
      continue;
    }

    button.stablePressed = pressed;
    Serial.print(buttonName(button.pin));
    Serial.println(pressed ? " DOWN" : " UP");
    if (pressed) {
      sendEvent(button.pressEvent);
    } else if (button.releaseEvent != 0) {
      sendEvent(button.releaseEvent);
    }
  }
  delay(4);
}
