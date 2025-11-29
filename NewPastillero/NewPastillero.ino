/*
 * ============================================================================
 * PastillApp ESP32 - Dispensador de Pastillas Inteligente
 * ============================================================================
 * 
 * Dispositivo: ESP32 DevKit V1
 * Versión: 2.0
 * 
 * Descripción:
 * Sistema de dispensación de medicamentos con detección de retiro de pastilla
 * mediante sensor LDR, notificaciones por LED y buzzer, y comunicación
 * Bluetooth Serial con aplicación Android.
 * 
 * Protocolo de Comandos Bluetooth:
 * - PING          : Responde "PONG" (heartbeat)
 * - STATUS        : Retorna estado actual del dispositivo
 * - GET_UUID      : Retorna UUID del dispositivo
 * - SEND_UUID     : Retorna "UUID:{uuid}"
 * - SET_UUID:{id} : Establece nuevo UUID
 * - PASTILLA      : Abre el compartimento
 * - SILENCIAR     : Silencia la alarma/buzzer
 * 
 * Eventos emitidos:
 * - EVENT:CONNECTED       : Cliente Bluetooth conectado
 * - EVENT:OPENED          : Compartimento abierto
 * - EVENT:PILL_TAKEN      : Pastilla retirada (mano detectada)
 * - EVENT:CLOSING         : Iniciando cierre
 * - EVENT:CLOSED          : Compartimento cerrado
 * - EVENT:ALARM_SILENCED  : Alarma silenciada
 * - STATUS:{estado}       : Estado actual (CERRADO/ABIERTO/CUENTA_REGRESIVA)
 * - LDR={valor}           : Telemetría del sensor de luz
 * 
 * ============================================================================
 */

#include <ESP32Servo.h>
#include <BluetoothSerial.h>
#include <Preferences.h>
#include <math.h>

BluetoothSerial SerialBT;
Servo servoMotor;
Preferences preferences;

// ============================================================================
// CONFIGURACIÓN DE PINES
// ============================================================================
const int PIN_SERVO     = 13;   // D13: señal PWM servo
const int PIN_BOTON     = 12;   // D12: botón con INPUT_PULLUP (activo en LOW)
const int PIN_ZUMBADOR  = 14;   // D14: zumbador activo (HIGH = sonando)
const int PIN_LED       = 27;   // D27: LED indicador
const int PIN_LDR       = 35;   // GPIO35: ADC1_CH7, divisor de voltaje con LDR

// ============================================================================
// CONFIGURACIÓN DEL SERVO
// ============================================================================
const int PULSO_MIN = 500;      // µs (~0°)
const int PULSO_MAX = 2400;     // µs (~180°)
const int ANGULO_CERRADO = 10;  // grados - posición cerrada
const int ANGULO_ABIERTO = 90;  // grados - posición abierta

// ============================================================================
// CONFIGURACIÓN DE INDICADORES
// ============================================================================
const unsigned long INTERVALO_LED_ABIERTO = 500;  // ms (parpadeo normal)
const unsigned long INTERVALO_LED_ALERTA  = 150;  // ms (parpadeo rápido)
const unsigned long INTERVALO_BUZZER_ALERTA = 250; // ms ON/OFF intermitente

// ============================================================================
// CONFIGURACIÓN DEL SENSOR LDR
// ============================================================================
const float UMBRAL_RELATIVO = 0.25f;        // 25% de cambio respecto a baseline
const int   UMBRAL_ABS      = 120;          // cuentas ADC absolutas (~3% de 4095)
const unsigned long T_CONFIRMACION = 80;    // ms que debe mantenerse el cambio
const int PROMEDIO_LDR = 16;                // muestras para suavizar lectura

// ============================================================================
// CONFIGURACIÓN DE TIEMPOS
// ============================================================================
const unsigned long DURACION_MOV = 1500;     // ms movimiento suave servo
const unsigned long CUENTA_MS    = 10000;    // ms de cuenta regresiva tras detectar mano
const unsigned long INTERVALO_TELEMETRIA_LDR = 1000; // ms entre envíos de telemetría

// ============================================================================
// ESTADOS DEL SISTEMA
// ============================================================================
enum Estado { 
  CERRADO,          // Compartimento cerrado, esperando comando
  ABIERTO,          // Compartimento abierto, esperando retiro de pastilla
  CUENTA_REGRESIVA  // Pastilla retirada, cuenta regresiva para cerrar
};
Estado estado = CERRADO;

// ============================================================================
// VARIABLES DE CONTROL
// ============================================================================
int anguloActual = ANGULO_CERRADO;
bool estadoLED = LOW;
bool alarmaSilenciada = false;

unsigned long tBlink = 0;
unsigned long tBuzzer = 0;
unsigned long finCuenta = 0;
unsigned long tTelemetriaLDR = 0;

// Variables LDR
float baselineLDR = 0.0f;
bool cambioDetectado = false;
unsigned long tInicioCambio = 0;

// Control de notificaciones (evita spam)
bool notificacionAbierto = false;
bool notificacionCierre = false;

// UUID del dispositivo
char deviceUUID[37] = "";
const char* UUID_KEY = "device_uuid";
bool uuidEnviado = false;

// ============================================================================
// FUNCIONES AUXILIARES
// ============================================================================

/**
 * Lee el sensor LDR con promedio para suavizar
 * @return Valor promediado del ADC (0-4095)
 */
int leerLDRPromedio() {
  long suma = 0;
  for (int i = 0; i < PROMEDIO_LDR; i++) {
    suma += analogRead(PIN_LDR);
    delayMicroseconds(250);
  }
  return (int)(suma / PROMEDIO_LDR);
}

/**
 * Mueve el servo suavemente de una posición a otra
 * @param srv Referencia al servo
 * @param desde Ángulo inicial
 * @param hasta Ángulo final
 * @param duracionMs Duración del movimiento en ms
 */
void moverSuave(Servo &srv, int desde, int hasta, int duracionMs) {
  int pasos = abs(hasta - desde);
  if (pasos == 0) { srv.write(hasta); return; }
  int retardoPaso = max(1, duracionMs / pasos);
  int paso = (hasta > desde) ? 1 : -1;
  for (int pos = desde; pos != hasta; pos += paso) {
    srv.write(pos);
    delay(retardoPaso);
  }
  srv.write(hasta);
}

/**
 * Retorna el nombre del estado actual como string
 * @return String con el nombre del estado
 */
const char* getNombreEstado() {
  switch (estado) {
    case CERRADO: return "CERRADO";
    case ABIERTO: return "ABIERTO";
    case CUENTA_REGRESIVA: return "CUENTA_REGRESIVA";
    default: return "DESCONOCIDO";
  }
}

/**
 * Envía el estado actual por Bluetooth
 */
void enviarEstado() {
  if (SerialBT.hasClient()) {
    SerialBT.print("STATUS:");
    SerialBT.println(getNombreEstado());
  }
}

/**
 * Envía un evento por Bluetooth
 * @param evento Nombre del evento
 */
void enviarEvento(const char* evento) {
  if (SerialBT.hasClient()) {
    SerialBT.print("EVENT:");
    SerialBT.println(evento);
  }
}

/**
 * Abre el compartimento e inicia el modo ABIERTO
 */
void iniciarAbierto() {
  moverSuave(servoMotor, anguloActual, ANGULO_ABIERTO, DURACION_MOV);
  anguloActual = ANGULO_ABIERTO;
  estado = ABIERTO;

  // LED parpadeo normal
  estadoLED = LOW;
  digitalWrite(PIN_LED, estadoLED);
  tBlink = millis();

  // Buzzer continuo si no está silenciado
  digitalWrite(PIN_ZUMBADOR, alarmaSilenciada ? LOW : HIGH);

  // Reset detección LDR
  cambioDetectado = false;
  tInicioCambio = 0;
  baselineLDR = leerLDRPromedio();
  Serial.print("[LDR] Baseline inicial establecido: ");
  Serial.println(baselineLDR, 1);
  
  // Notificar evento
  notificacionAbierto = true;
  enviarEvento("OPENED");
}

/**
 * Cierra el compartimento y vuelve al estado CERRADO
 */
void cerrarCompartimento() {
  // Apaga señales
  digitalWrite(PIN_ZUMBADOR, LOW);
  digitalWrite(PIN_LED, LOW);
  estadoLED = LOW;

  // Cierra
  moverSuave(servoMotor, anguloActual, ANGULO_CERRADO, DURACION_MOV);
  anguloActual = ANGULO_CERRADO;
  estado = CERRADO;

  // Limpia banderas
  cambioDetectado = false;
  tInicioCambio = 0;
  notificacionAbierto = false;
  notificacionCierre = false;

  // Rehabilita alarma para el próximo ciclo
  alarmaSilenciada = false;
  
  // Notificar evento
  enviarEvento("CLOSED");
}

/**
 * Inicia la cuenta regresiva después de detectar retiro de pastilla
 */
void iniciarCuentaRegresiva() {
  estado = CUENTA_REGRESIVA;
  finCuenta = millis() + CUENTA_MS;

  // Reactivar alarma si estaba silenciada
  alarmaSilenciada = false;

  // Arranca patrones rápidos
  tBlink = millis();
  tBuzzer = millis();
  digitalWrite(PIN_ZUMBADOR, LOW);
  digitalWrite(PIN_LED, LOW);
  estadoLED = LOW;
  
  // Notificar evento
  enviarEvento("PILL_TAKEN");
}

/**
 * Silencia la alarma (buzzer)
 */
void silenciarAlarma() {
  alarmaSilenciada = true;
  digitalWrite(PIN_ZUMBADOR, LOW);
  enviarEvento("ALARM_SILENCED");
}

/**
 * Genera un UUID único basado en el chip ID y tiempo
 * @param uuid Buffer para almacenar el UUID generado
 */
void generarUUID(char* uuid) {
  uint32_t chipId = 0;
  for(int i=0; i<17; i+=8) {
    chipId |= ((ESP.getEfuseMac() >> (40 - i)) & 0xff) << i;
  }
  unsigned long tiempo = millis();
  
  sprintf(uuid, "%08lx-%04lx-%04lx-%04lx-%08lx%04lx",
    chipId,
    (tiempo >> 16) & 0xFFFF,
    tiempo & 0xFFFF,
    ((chipId >> 16) & 0x0FFF) | 0x4000,
    chipId, (tiempo >> 8) & 0xFFFF);
}

/**
 * Procesa un comando recibido por Bluetooth
 * @param cmd Comando recibido (ya trimmeado)
 */
void procesarComando(String cmd) {
  // Convertir a mayúsculas para comparación (excepto SET_UUID que tiene datos)
  String cmdUpper = cmd;
  cmdUpper.toUpperCase();
  
  // === PING - Heartbeat ===
  if (cmdUpper == "PING") {
    SerialBT.println("PONG");
    return;
  }
  
  // === STATUS - Estado actual ===
  if (cmdUpper == "STATUS") {
    enviarEstado();
    return;
  }
  
  // === SILENCIAR - Silenciar alarma ===
  if (cmdUpper == "SILENCIAR") {
    silenciarAlarma();
    SerialBT.println("OK:SILENCIADO");
    return;
  }
  
  // === GET_UUID ===
  if (cmdUpper == "GET_UUID") {
    if (strlen(deviceUUID) > 0) {
      SerialBT.print("UUID:");
      SerialBT.println(deviceUUID);
    } else {
      generarUUID(deviceUUID);
      preferences.begin("pastillapp", false);
      preferences.putString(UUID_KEY, String(deviceUUID));
      preferences.end();
      SerialBT.print("UUID:");
      SerialBT.println(deviceUUID);
    }
    return;
  }
  
  // === SEND_UUID ===
  if (cmdUpper == "SEND_UUID") {
    if (strlen(deviceUUID) > 0) {
      SerialBT.print("UUID:");
      SerialBT.println(deviceUUID);
    } else {
      SerialBT.println("NO_UUID");
    }
    return;
  }
  
  // === SET_UUID:{uuid} ===
  if (cmdUpper.startsWith("SET_UUID:")) {
    String newUUID = cmd.substring(9);
    newUUID.trim();
    if (newUUID.length() > 0 && newUUID.length() <= 36) {
      newUUID.toCharArray(deviceUUID, 37);
      preferences.begin("pastillapp", false);
      preferences.putString(UUID_KEY, newUUID);
      preferences.end();
      SerialBT.println("OK:UUID_SET");
    } else {
      SerialBT.println("ERROR:INVALID_UUID");
    }
    return;
  }
  
  // === PASTILLA - Abrir compartimento ===
  if (cmdUpper == "PASTILLA") {
    if (estado == CERRADO) {
      iniciarAbierto();
      SerialBT.println("OK:ABRIENDO");
    } else {
      SerialBT.println("WARN:YA_ABIERTO");
    }
    return;
  }
  
  // Comando no reconocido
  SerialBT.print("ERROR:COMANDO_DESCONOCIDO:");
  SerialBT.println(cmd);
}

// ============================================================================
// SETUP
// ============================================================================
void setup() {
  // Serial Monitor
  Serial.begin(115200);
  
  // Bluetooth
  SerialBT.begin("PastillApp V1");
  
  // Cargar UUID desde NVS
  preferences.begin("pastillapp", false);
  String uuidStr = preferences.getString(UUID_KEY, "");
  uuidStr.toCharArray(deviceUUID, 37);
  
  if (strlen(deviceUUID) == 0) {
    generarUUID(deviceUUID);
    preferences.putString(UUID_KEY, String(deviceUUID));
  }
  
  preferences.end();
  uuidEnviado = false;

  // Configuración de pines
  pinMode(PIN_BOTON, INPUT_PULLUP);
  pinMode(PIN_ZUMBADOR, OUTPUT);
  pinMode(PIN_LED, OUTPUT);

  // Configuración ADC
  analogReadResolution(12);
  analogSetPinAttenuation(PIN_LDR, ADC_6db);

  // Servo
  servoMotor.attach(PIN_SERVO, PULSO_MIN, PULSO_MAX);

  // Estado inicial
  servoMotor.write(ANGULO_CERRADO);
  digitalWrite(PIN_ZUMBADOR, LOW);
  digitalWrite(PIN_LED, LOW);
  estado = CERRADO;
  anguloActual = ANGULO_CERRADO;
  
  Serial.println("PastillApp iniciado - Monitor Serial activo");
}

// ============================================================================
// LOOP PRINCIPAL
// ============================================================================
void loop() {
  unsigned long now = millis();

  // --- Detección de conexión Bluetooth ---
  if (SerialBT.hasClient() && !uuidEnviado && strlen(deviceUUID) > 0) {
    enviarEvento("CONNECTED");
    SerialBT.print("UUID:");
    SerialBT.println(deviceUUID);
    enviarEstado();
    uuidEnviado = true;
  }
  
  if (!SerialBT.hasClient() && uuidEnviado) {
    uuidEnviado = false;
  }
 
  // --- Botón físico: silencia el buzzer ---
  static bool ultimoBtn = HIGH;
  bool btn = digitalRead(PIN_BOTON);
  if (ultimoBtn == HIGH && btn == LOW) {
    silenciarAlarma();
    delay(50); // antirrebote
  }
  ultimoBtn = btn;

  // --- Procesar comandos Bluetooth ---
  if (SerialBT.available()) {
    String cmd = SerialBT.readStringUntil('\n');
    cmd.trim();
    if (cmd.length() > 0) {
      procesarComando(cmd);
    }
  }

  // --- Lógica por estado ---
  switch (estado) {
    case ABIERTO:
      // Telemetría LDR
      if (now - tTelemetriaLDR >= INTERVALO_TELEMETRIA_LDR) {
        tTelemetriaLDR = now;
        int lecturaInst = leerLDRPromedio();
        Serial.print("[LDR] Lectura: ");
        Serial.print(lecturaInst);
        Serial.print(" | Baseline: ");
        Serial.print(baselineLDR, 1);
        Serial.print(" | Estado: ABIERTO");
        Serial.println();
        if (SerialBT.hasClient()) {
          SerialBT.print("LDR=");
          SerialBT.println(lecturaInst);
        }
      }
      
      // LED parpadeo normal
      if (now - tBlink >= INTERVALO_LED_ABIERTO) {
        tBlink = now;
        estadoLED = !estadoLED;
        digitalWrite(PIN_LED, estadoLED);
      }

      // Detección de mano con LDR
      if (!cambioDetectado) {
        int lectura = leerLDRPromedio();
        baselineLDR = 0.98f * baselineLDR + 0.02f * (float)lectura;
        
        float base = fmaxf(1.0f, baselineLDR);
        float absDelta = fabsf((float)lectura - base);
        float deltaRel = absDelta / base;

        bool umbralSuperado = (deltaRel >= UMBRAL_RELATIVO) || (absDelta >= (float)UMBRAL_ABS);

        // Output serial para debugging
        static unsigned long tUltimoDebug = 0;
        if (now - tUltimoDebug >= 200) { // Cada 200ms para no saturar
          tUltimoDebug = now;
          Serial.print("[LDR] Lectura: ");
          Serial.print(lectura);
          Serial.print(" | Baseline: ");
          Serial.print(baselineLDR, 1);
          Serial.print(" | Delta: ");
          Serial.print(absDelta, 1);
          Serial.print(" | DeltaRel: ");
          Serial.print(deltaRel * 100, 1);
          Serial.print("% | Umbral: ");
          Serial.print(umbralSuperado ? "SI" : "NO");
          if (tInicioCambio > 0) {
            Serial.print(" | Confirmando: ");
            Serial.print(now - tInicioCambio);
            Serial.print("ms");
          }
          Serial.println();
        }

        if (umbralSuperado) {
          if (tInicioCambio == 0) {
            tInicioCambio = now;
            Serial.println("[LDR] Cambio detectado - Iniciando confirmación...");
          }
          if (now - tInicioCambio >= T_CONFIRMACION) {
            cambioDetectado = true;
            Serial.println("[LDR] ¡MANO DETECTADA! - Iniciando cuenta regresiva");
            iniciarCuentaRegresiva();
          }
        } else {
          if (tInicioCambio > 0) {
            Serial.println("[LDR] Cambio no confirmado - Reset");
          }
          tInicioCambio = 0;
        }
      }

      // Buzzer mientras ABIERTO
      if (!cambioDetectado) {
        digitalWrite(PIN_ZUMBADOR, alarmaSilenciada ? LOW : HIGH);
      }
      break;

    case CUENTA_REGRESIVA:
      // Telemetría LDR
      if (now - tTelemetriaLDR >= INTERVALO_TELEMETRIA_LDR) {
        tTelemetriaLDR = now;
        int lecturaInst = leerLDRPromedio();
        Serial.print("[LDR] Lectura: ");
        Serial.print(lecturaInst);
        Serial.print(" | Baseline: ");
        Serial.print(baselineLDR, 1);
        Serial.print(" | Estado: CUENTA_REGRESIVA");
        Serial.println();
        if (SerialBT.hasClient()) {
          SerialBT.print("LDR=");
          SerialBT.println(lecturaInst);
        }
      }
      
      // LED parpadeo rápido
      if (now - tBlink >= INTERVALO_LED_ALERTA) {
        tBlink = now;
        estadoLED = !estadoLED;
        digitalWrite(PIN_LED, estadoLED);
      }

      // Buzzer intermitente
      if (!alarmaSilenciada) {
        if (now - tBuzzer >= INTERVALO_BUZZER_ALERTA) {
          tBuzzer = now;
          digitalWrite(PIN_ZUMBADOR, !digitalRead(PIN_ZUMBADOR));
        }
      } else {
        digitalWrite(PIN_ZUMBADOR, LOW);
      }

      // Notificación de cierre (una sola vez)
      if (!notificacionCierre) {
        enviarEvento("CLOSING");
        notificacionCierre = true;
      }

      // Fin de cuenta regresiva
      if ((long)(finCuenta - now) <= 0) {
        cerrarCompartimento();
      }
      break;

    case CERRADO:
      // No requiere tarea periódica
      break;
  }
}
