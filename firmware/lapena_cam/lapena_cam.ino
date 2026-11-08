/*
 * La Peña Cam — motor que gira el celular para seguir la jugada.
 *
 * Placa:  ESP32 DevKit (cualquier ESP32 con Bluetooth)
 * Motor:  28BYJ-48 (5 V) con su driver ULN2003
 *
 * Conexiones (cables dupont hembra-hembra):
 *   ULN2003 IN1 -> ESP32 GPIO 16
 *   ULN2003 IN2 -> ESP32 GPIO 17
 *   ULN2003 IN3 -> ESP32 GPIO 18
 *   ULN2003 IN4 -> ESP32 GPIO 19
 *   ULN2003 +   -> ESP32 5V (o VIN)
 *   ULN2003 -   -> ESP32 GND
 * La ESP32 se alimenta con el power bank por USB.
 *
 * La app "La Peña Live" se conecta por Bluetooth y manda líneas de texto:
 *   S<n>  velocidad en pasos por segundo (negativo = al otro lado). S0 = quieto.
 *   C     volver al centro
 *   H     la posición actual pasa a ser el centro
 *
 * Al prenderlo, la posición en la que está es el CENTRO. Apuntalo al medio de la cancha
 * antes de enchufar el power bank. No gira más de LIMIT_DEG grados para cada lado
 * (así no se enredan los cables).
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ---------- Ajustes ----------
const char* DEVICE_NAME = "LaPena-Cam";     // tiene que coincidir con la app
const int PINS[4] = {16, 17, 18, 19};
const float STEPS_PER_REV = 4096.0;         // 28BYJ-48 en medio paso
const float LIMIT_DEG = 100.0;              // giro máximo para cada lado desde el centro
const float MAX_SPEED = 800.0;              // pasos por segundo (~70 grados por segundo)
const float ACCEL = 1200.0;                 // pasos por segundo, por segundo (arranque y frenado suaves)
const unsigned long COMMAND_TIMEOUT_MS = 1000;  // sin órdenes por 1 s -> se frena
const unsigned long COILS_OFF_MS = 600;     // apaga las bobinas al estar quieto (no calienta)
const int LED_PIN = 2;                      // LED de la placa: prendido = conectado

#define SERVICE_UUID "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define RX_UUID      "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define TX_UUID      "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

// Secuencia de medio paso del 28BYJ-48
const uint8_t SEQ[8][4] = {
  {1,0,0,0},{1,1,0,0},{0,1,0,0},{0,1,1,0},
  {0,0,1,0},{0,0,1,1},{0,0,0,1},{1,0,0,1}
};

const long LIMIT_STEPS = (long)(LIMIT_DEG / 360.0 * STEPS_PER_REV);

volatile float targetSpeed = 0;   // lo que pide la app
volatile bool goingHome = false;
volatile unsigned long lastCommandAt = 0;
volatile bool connected = false;

float speed = 0;                  // velocidad actual (con aceleración)
long position = 0;                // pasos desde el centro
int phase = 0;
unsigned long lastStepAt = 0;
unsigned long lastMoveAt = 0;
unsigned long lastLoopAt = 0;
bool coilsOn = false;
BLEServer* server = nullptr;

void writeCoils(int p) {
  for (int i = 0; i < 4; i++) digitalWrite(PINS[i], SEQ[p][i]);
  coilsOn = true;
}

void coilsOff() {
  for (int i = 0; i < 4; i++) digitalWrite(PINS[i], LOW);
  coilsOn = false;
}

void handleLine(String line) {
  line.trim();
  if (line.length() == 0) return;
  lastCommandAt = millis();
  char cmd = line.charAt(0);
  if (cmd == 'S') {
    goingHome = false;
    float s = line.substring(1).toFloat();
    if (s > MAX_SPEED) s = MAX_SPEED;
    if (s < -MAX_SPEED) s = -MAX_SPEED;
    targetSpeed = s;
  } else if (cmd == 'C') {
    goingHome = true;
  } else if (cmd == 'H') {
    goingHome = false;
    targetSpeed = 0;
    position = 0;
  }
}

class RxCallbacks : public BLECharacteristicCallbacks {
  String buffer;
  void onWrite(BLECharacteristic* c) override {
    String value = String(c->getValue().c_str());
    for (unsigned int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch == '\n') { handleLine(buffer); buffer = ""; }
      else if (buffer.length() < 32) buffer += ch;
    }
  }
};

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* s) override {
    connected = true;
    digitalWrite(LED_PIN, HIGH);
  }
  void onDisconnect(BLEServer* s) override {
    connected = false;
    targetSpeed = 0;
    digitalWrite(LED_PIN, LOW);
    BLEDevice::startAdvertising();  // vuelve a quedar visible para la app
  }
};

void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN, OUTPUT);
  for (int i = 0; i < 4; i++) pinMode(PINS[i], OUTPUT);
  coilsOff();

  BLEDevice::init(DEVICE_NAME);
  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService* service = server->createService(SERVICE_UUID);
  BLECharacteristic* tx = service->createCharacteristic(TX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  tx->addDescriptor(new BLE2902());
  BLECharacteristic* rx = service->createCharacteristic(
    RX_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rx->setCallbacks(new RxCallbacks());
  service->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("La Pena Cam lista. Esperando la app...");
  lastLoopAt = micros();
}

void loop() {
  unsigned long nowUs = micros();
  float dt = (nowUs - lastLoopAt) / 1000000.0;
  lastLoopAt = nowUs;
  unsigned long nowMs = millis();

  // Qué velocidad queremos
  float want = targetSpeed;
  if (goingHome) {
    want = -position * 4.0;
    if (want > MAX_SPEED * 0.75) want = MAX_SPEED * 0.75;
    if (want < -MAX_SPEED * 0.75) want = -MAX_SPEED * 0.75;
    if (labs(position) <= 2) { goingHome = false; want = 0; targetSpeed = 0; }
  } else if (nowMs - lastCommandAt > COMMAND_TIMEOUT_MS) {
    want = 0;  // se cortó la conexión o la app dejó de mandar: frenar
  }
  // Topes: no pasar del límite para cada lado
  if (position >= LIMIT_STEPS && want > 0) want = 0;
  if (position <= -LIMIT_STEPS && want < 0) want = 0;

  // Acelerar / frenar de a poco
  float maxDelta = ACCEL * dt;
  if (want > speed + maxDelta) speed += maxDelta;
  else if (want < speed - maxDelta) speed -= maxDelta;
  else speed = want;

  // Dar un paso cuando corresponde
  float absSpeed = fabs(speed);
  if (absSpeed > 5) {
    unsigned long interval = (unsigned long)(1000000.0 / absSpeed);
    if (nowUs - lastStepAt >= interval) {
      lastStepAt = nowUs;
      int dir = speed > 0 ? 1 : -1;
      if ((dir > 0 && position < LIMIT_STEPS) || (dir < 0 && position > -LIMIT_STEPS)) {
        phase = (phase + dir + 8) % 8;
        position += dir;
        writeCoils(phase);
        lastMoveAt = nowMs;
      }
    }
  } else if (coilsOn && nowMs - lastMoveAt > COILS_OFF_MS) {
    coilsOff();
  }
}
