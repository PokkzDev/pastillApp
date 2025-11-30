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
#include "mbedtls/aes.h"
#include "mbedtls/base64.h"

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
const int ANGULO_CERRADO = 0;  // grados - posición cerrada
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
// ALMACENAMIENTO DE EVENTOS DE PASTILLAS (OFFLINE MODE)
// ============================================================================
struct PillEvent {
  char id[37];           // UUID del evento
  char date[11];         // Fecha en formato "YYYY-MM-DD"
  char pillName[64];     // Nombre de la pastilla
  char amount[32];       // Cantidad
  char time[6];          // Hora en formato "HH:mm"
  bool dispensed;        // Si ya fue dispensada
};

const int MAX_EVENTS = 100;  // Máximo número de eventos almacenados
PillEvent events[MAX_EVENTS];
int eventCount = 0;
const char* EVENTS_KEY = "pill_events";
const char* EVENT_COUNT_KEY = "event_count";

// Variables para dispensación offline
unsigned long lastEventCheck = 0;
const unsigned long EVENT_CHECK_INTERVAL = 60000; // Verificar cada minuto

// ============================================================================
// ENCRIPTACIÓN AES-256-CBC
// ============================================================================
// Clave compartida (32 bytes = 256 bits) - DEBE SER LA MISMA QUE EN ANDROID
const unsigned char AES_KEY[32] = {
  0x2B, 0x7E, 0x15, 0x16, 0x28, 0xAE, 0xD2, 0xA6,
  0xAB, 0xF7, 0x15, 0x88, 0x09, 0xCF, 0x4F, 0x3C,
  0x1A, 0x2B, 0x3C, 0x4D, 0x5E, 0x6F, 0x70, 0x81,
  0x92, 0xA3, 0xB4, 0xC5, 0xD6, 0xE7, 0xF8, 0x09
};
const int AES_IV_SIZE = 16; // 128 bits
const int AES_BLOCK_SIZE = 16; // 128 bits

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
 * Envía el estado actual por Bluetooth (sin encriptar, usado internamente)
 */
void enviarEstado() {
  if (SerialBT.hasClient()) {
    char statusMsg[64];
    snprintf(statusMsg, sizeof(statusMsg), "STATUS:%s", getNombreEstado());
    // Enviar sin encriptar ya que es notificación automática
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
 * Desencripta un mensaje encriptado con AES-256-CBC
 * @param encryptedBase64 String Base64 que contiene: IV(16 bytes) + Datos encriptados
 * @param output Buffer para almacenar el texto desencriptado
 * @param outputSize Tamaño del buffer de salida
 * @return true si la desencriptación fue exitosa
 */
bool decryptCommand(const char* encryptedBase64, char* output, size_t outputSize) {
  mbedtls_aes_context aes;
  mbedtls_aes_init(&aes);
  
  // Decodificar Base64
  size_t olen;
  unsigned char* decoded = (unsigned char*)malloc(strlen(encryptedBase64) * 3 / 4 + 1);
  if (!decoded) {
    mbedtls_aes_free(&aes);
    return false;
  }
  
  int ret = mbedtls_base64_decode(decoded, strlen(encryptedBase64) * 3 / 4 + 1, &olen, 
                                   (const unsigned char*)encryptedBase64, strlen(encryptedBase64));
  if (ret != 0 || olen < AES_IV_SIZE) {
    free(decoded);
    mbedtls_aes_free(&aes);
    return false;
  }
  
  // Extraer IV y datos encriptados
  unsigned char iv[AES_IV_SIZE];
  memcpy(iv, decoded, AES_IV_SIZE);
  
  size_t encryptedLen = olen - AES_IV_SIZE;
  unsigned char* encrypted = decoded + AES_IV_SIZE;
  
  // Validar que el tamaño encriptado sea múltiplo del tamaño de bloque
  if (encryptedLen == 0 || (encryptedLen % AES_BLOCK_SIZE) != 0) {
    Serial.print("[AES] Error: tamaño encriptado inválido: ");
    Serial.println(encryptedLen);
    free(decoded);
    mbedtls_aes_free(&aes);
    return false;
  }
  
  // Configurar clave
  ret = mbedtls_aes_setkey_dec(&aes, AES_KEY, 256);
  if (ret != 0) {
    Serial.println("[AES] Error configurando clave");
    free(decoded);
    mbedtls_aes_free(&aes);
    return false;
  }
  
  // Desencriptar
  unsigned char* decrypted = (unsigned char*)malloc(encryptedLen + 1);
  if (!decrypted) {
    free(decoded);
    mbedtls_aes_free(&aes);
    return false;
  }
  
  ret = mbedtls_aes_crypt_cbc(&aes, MBEDTLS_AES_DECRYPT, encryptedLen, iv, encrypted, decrypted);
  
  if (ret == 0) {
    // Validar y remover padding PKCS5 correctamente
    int padding = decrypted[encryptedLen - 1];
    
    // Validar que el padding sea válido (1-16 bytes)
    if (padding > 0 && padding <= AES_BLOCK_SIZE && padding <= encryptedLen) {
      // Verificar que todos los bytes de padding sean idénticos
      bool validPadding = true;
      for (int i = encryptedLen - padding; i < encryptedLen; i++) {
        if (decrypted[i] != padding) {
          validPadding = false;
          break;
        }
      }
      
      if (validPadding) {
        encryptedLen -= padding;
        decrypted[encryptedLen] = '\0';
        
        // Copiar resultado
        strncpy(output, (char*)decrypted, outputSize - 1);
        output[outputSize - 1] = '\0';
      } else {
        Serial.println("[AES] Error: padding PKCS5 inválido (bytes no coinciden)");
        free(decrypted);
        free(decoded);
        mbedtls_aes_free(&aes);
        return false;
      }
    } else {
      Serial.print("[AES] Error: valor de padding inválido: ");
      Serial.println(padding);
      free(decrypted);
      free(decoded);
      mbedtls_aes_free(&aes);
      return false;
    }
  }
  
  free(decrypted);
  free(decoded);
  mbedtls_aes_free(&aes);
  
  return (ret == 0);
}

/**
 * Encripta un mensaje con AES-256-CBC
 * @param plainText Texto a encriptar
 * @param output Buffer para almacenar el resultado en Base64
 * @param outputSize Tamaño del buffer de salida
 * @return true si la encriptación fue exitosa
 */
bool encryptResponse(const char* plainText, char* output, size_t outputSize) {
  mbedtls_aes_context aes;
  mbedtls_aes_init(&aes);
  
  size_t plainLen = strlen(plainText);
  
  // Calcular tamaño con padding PKCS5
  size_t paddedLen = ((plainLen / AES_BLOCK_SIZE) + 1) * AES_BLOCK_SIZE;
  unsigned char* padded = (unsigned char*)malloc(paddedLen);
  if (!padded) {
    mbedtls_aes_free(&aes);
    return false;
  }
  
  memcpy(padded, plainText, plainLen);
  
  // Agregar padding PKCS5
  int padding = paddedLen - plainLen;
  for (int i = plainLen; i < paddedLen; i++) {
    padded[i] = padding;
  }
  
  // Generar IV aleatorio
  unsigned char iv[AES_IV_SIZE];
  unsigned char iv_original[AES_IV_SIZE];  // Copia del IV original (mbedtls modifica iv in-place)
  for (int i = 0; i < AES_IV_SIZE; i++) {
    iv[i] = esp_random() & 0xFF;  // Usar esp_random() para mejor entropía
    iv_original[i] = iv[i];       // Guardar copia antes de encriptación
  }
  
  // Configurar clave
  int ret = mbedtls_aes_setkey_enc(&aes, AES_KEY, 256);
  if (ret != 0) {
    free(padded);
    mbedtls_aes_free(&aes);
    return false;
  }
  
  // Encriptar
  unsigned char* encrypted = (unsigned char*)malloc(paddedLen);
  if (!encrypted) {
    free(padded);
    mbedtls_aes_free(&aes);
    return false;
  }
  
  ret = mbedtls_aes_crypt_cbc(&aes, MBEDTLS_AES_ENCRYPT, paddedLen, iv, padded, encrypted);
  
  if (ret == 0) {
    // Validar que el tamaño encriptado sea correcto (múltiplo del tamaño de bloque)
    if (paddedLen % AES_BLOCK_SIZE != 0) {
      Serial.print("[AES] Error: tamaño encriptado inválido: ");
      Serial.println(paddedLen);
      free(encrypted);
      free(padded);
      mbedtls_aes_free(&aes);
      return false;
    }
    
    // Combinar IV original + datos encriptados
    // IMPORTANTE: Usar iv_original porque mbedtls_aes_crypt_cbc modifica iv in-place
    size_t combinedSize = AES_IV_SIZE + paddedLen;
    unsigned char* combined = (unsigned char*)malloc(combinedSize);
    if (combined) {
      memcpy(combined, iv_original, AES_IV_SIZE);  // Usar IV original, no el modificado
      memcpy(combined + AES_IV_SIZE, encrypted, paddedLen);
      
      // Calcular tamaño necesario para Base64 (aproximadamente 4/3 del tamaño original)
      size_t base64Size = ((combinedSize + 2) / 3) * 4 + 1;
      if (base64Size > outputSize) {
        Serial.print("[AES] Error: buffer de salida demasiado pequeño (necesita ");
        Serial.print(base64Size);
        Serial.print(", tiene ");
        Serial.print(outputSize);
        Serial.println(")");
        free(combined);
        free(encrypted);
        free(padded);
        mbedtls_aes_free(&aes);
        return false;
      }
      
      // Codificar a Base64
      size_t olen;
      ret = mbedtls_base64_encode((unsigned char*)output, outputSize, &olen, 
                                   combined, combinedSize);
      
      if (ret == 0 && olen > 0) {
        output[olen] = '\0';
      } else {
        Serial.println("[AES] Error: fallo en codificación Base64");
        ret = -1;
      }
      
      free(combined);
    } else {
      Serial.println("[AES] Error: no se pudo asignar memoria para datos combinados");
      ret = -1;
    }
  } else {
    Serial.print("[AES] Error en encriptación: ");
    Serial.println(ret);
  }
  
  free(encrypted);
  free(padded);
  mbedtls_aes_free(&aes);
  
  return (ret == 0);
}

/**
 * Verifica si un string parece estar encriptado (Base64 válido)
 */
bool isEncrypted(const char* text) {
  if (strlen(text) < 20) return false; // Mínimo tamaño esperado
  
  // Verificar que sea Base64 válido
  for (int i = 0; text[i] != '\0'; i++) {
    char c = text[i];
    if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || 
          (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=')) {
      return false;
    }
  }
  return true;
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

// ============================================================================
// FUNCIONES DE GESTIÓN DE EVENTOS
// ============================================================================

/**
 * Carga eventos desde Preferences (NVS)
 */
void loadEventsFromStorage() {
  preferences.begin("pastillapp", true); // Modo lectura
  eventCount = preferences.getInt(EVENT_COUNT_KEY, 0);
  
  if (eventCount > MAX_EVENTS) {
    eventCount = MAX_EVENTS;
  }
  
  Serial.print("[EVENTS] Cargando ");
  Serial.print(eventCount);
  Serial.println(" eventos desde almacenamiento");
  
  for (int i = 0; i < eventCount; i++) {
    String key = String(EVENTS_KEY) + "_" + String(i);
    String eventData = preferences.getString(key.c_str(), "");
    
    if (eventData.length() > 0) {
      // Parsear evento desde string: id|date|pillName|amount|time|dispensed
      int pos = 0;
      int nextPos = eventData.indexOf('|', pos);
      if (nextPos > 0) {
        eventData.substring(pos, nextPos).toCharArray(events[i].id, sizeof(events[i].id));
        pos = nextPos + 1;
      }
      
      nextPos = eventData.indexOf('|', pos);
      if (nextPos > 0) {
        eventData.substring(pos, nextPos).toCharArray(events[i].date, sizeof(events[i].date));
        pos = nextPos + 1;
      }
      
      nextPos = eventData.indexOf('|', pos);
      if (nextPos > 0) {
        eventData.substring(pos, nextPos).toCharArray(events[i].pillName, sizeof(events[i].pillName));
        pos = nextPos + 1;
      }
      
      nextPos = eventData.indexOf('|', pos);
      if (nextPos > 0) {
        eventData.substring(pos, nextPos).toCharArray(events[i].amount, sizeof(events[i].amount));
        pos = nextPos + 1;
      }
      
      nextPos = eventData.indexOf('|', pos);
      if (nextPos > 0) {
        eventData.substring(pos, nextPos).toCharArray(events[i].time, sizeof(events[i].time));
        pos = nextPos + 1;
      }
      
      events[i].dispensed = (eventData.substring(pos) == "1");
    }
  }
  
  preferences.end();
  Serial.print("[EVENTS] Eventos cargados: ");
  Serial.println(eventCount);
}

/**
 * Guarda eventos en Preferences (NVS)
 */
void saveEventsToStorage() {
  preferences.begin("pastillapp", false); // Modo escritura
  preferences.putInt(EVENT_COUNT_KEY, eventCount);
  
  for (int i = 0; i < eventCount; i++) {
    String key = String(EVENTS_KEY) + "_" + String(i);
    // Formato: id|date|pillName|amount|time|dispensed
    String eventData = String(events[i].id) + "|" + 
                      String(events[i].date) + "|" + 
                      String(events[i].pillName) + "|" + 
                      String(events[i].amount) + "|" + 
                      String(events[i].time) + "|" + 
                      (events[i].dispensed ? "1" : "0");
    preferences.putString(key.c_str(), eventData);
  }
  
  preferences.end();
  Serial.print("[EVENTS] Eventos guardados: ");
  Serial.println(eventCount);
}

/**
 * Agrega un evento (reemplaza si ya existe con el mismo ID)
 */
void addEvent(PillEvent* event) {
  // Buscar si ya existe
  for (int i = 0; i < eventCount; i++) {
    if (strcmp(events[i].id, event->id) == 0) {
      // Actualizar evento existente
      memcpy(&events[i], event, sizeof(PillEvent));
      saveEventsToStorage();
      Serial.print("[EVENTS] Evento actualizado: ");
      Serial.println(event->id);
      return;
    }
  }
  
  // Agregar nuevo evento
  if (eventCount < MAX_EVENTS) {
    memcpy(&events[eventCount], event, sizeof(PillEvent));
    eventCount++;
    saveEventsToStorage();
    Serial.print("[EVENTS] Evento agregado: ");
    Serial.println(event->id);
  } else {
    Serial.println("[EVENTS] ERROR: Máximo de eventos alcanzado");
  }
}

/**
 * Obtiene eventos para una fecha específica
 */
int getEventsForDate(const char* date, PillEvent* result, int maxResults) {
  int count = 0;
  for (int i = 0; i < eventCount && count < maxResults; i++) {
    if (strcmp(events[i].date, date) == 0 && !events[i].dispensed) {
      result[count++] = events[i];
    }
  }
  return count;
}

/**
 * Marca un evento como dispensado
 */
bool markEventAsDispensed(const char* eventId) {
  for (int i = 0; i < eventCount; i++) {
    if (strcmp(events[i].id, eventId) == 0) {
      events[i].dispensed = true;
      saveEventsToStorage();
      Serial.print("[EVENTS] Evento marcado como dispensado: ");
      Serial.println(eventId);
      return true;
    }
  }
  return false;
}

/**
 * Parsea un evento desde JSON simple
 * Formato esperado: {"id":"...","date":"...","pillName":"...","amount":"...","time":"...","dispensed":false}
 */
bool parseEventFromJson(String json, PillEvent* event) {
  // Limpiar estructura
  memset(event, 0, sizeof(PillEvent));
  
  // Extraer campos simples (sin usar biblioteca JSON completa)
  int idStart = json.indexOf("\"id\":\"");
  int idEnd = json.indexOf("\"", idStart + 6);
  if (idStart >= 0 && idEnd > idStart) {
    json.substring(idStart + 6, idEnd).toCharArray(event->id, sizeof(event->id));
  }
  
  int dateStart = json.indexOf("\"date\":\"");
  int dateEnd = json.indexOf("\"", dateStart + 8);
  if (dateStart >= 0 && dateEnd > dateStart) {
    json.substring(dateStart + 8, dateEnd).toCharArray(event->date, sizeof(event->date));
  }
  
  int nameStart = json.indexOf("\"pillName\":\"");
  int nameEnd = json.indexOf("\"", nameStart + 12);
  if (nameStart >= 0 && nameEnd > nameStart) {
    json.substring(nameStart + 12, nameEnd).toCharArray(event->pillName, sizeof(event->pillName));
  }
  
  int amountStart = json.indexOf("\"amount\":\"");
  int amountEnd = json.indexOf("\"", amountStart + 10);
  if (amountStart >= 0 && amountEnd > amountStart) {
    json.substring(amountStart + 10, amountEnd).toCharArray(event->amount, sizeof(event->amount));
  }
  
  int timeStart = json.indexOf("\"time\":\"");
  int timeEnd = json.indexOf("\"", timeStart + 8);
  if (timeStart >= 0 && timeEnd > timeStart) {
    json.substring(timeStart + 8, timeEnd).toCharArray(event->time, sizeof(event->time));
  }
  
  // Dispensed puede ser true/false
  int dispStart = json.indexOf("\"dispensed\":");
  if (dispStart >= 0) {
    int boolStart = json.indexOf("true", dispStart);
    event->dispensed = (boolStart > dispStart && boolStart < dispStart + 20);
  }
  
  return (strlen(event->id) > 0 && strlen(event->date) > 0);
}

/**
 * Procesa un comando recibido por Bluetooth
 * @param cmd Comando recibido (ya trimmeado, puede estar encriptado)
 */
void procesarComando(String cmd) {
  // Intentar desencriptar el comando
  char decryptedCmd[128];
  bool wasEncrypted = false;
  
  if (isEncrypted(cmd.c_str())) {
    if (decryptCommand(cmd.c_str(), decryptedCmd, sizeof(decryptedCmd))) {
      cmd = String(decryptedCmd);
      wasEncrypted = true;
      Serial.print("[AES] Comando desencriptado: ");
      Serial.println(cmd);
    } else {
      Serial.println("[AES] Error desencriptando comando");
      SerialBT.println("ERROR:DECRYPT_FAILED");
      return;
    }
  }
  
  // Convertir a mayúsculas para comparación (excepto SET_UUID que tiene datos)
  String cmdUpper = cmd;
  cmdUpper.toUpperCase();
  
  // === PING - Heartbeat ===
  if (cmdUpper == "PING") {
    sendEncryptedResponse("PONG", wasEncrypted);
    return;
  }
  
  // === STATUS - Estado actual ===
  if (cmdUpper == "STATUS") {
    char statusMsg[64];
    snprintf(statusMsg, sizeof(statusMsg), "STATUS:%s", getNombreEstado());
    sendEncryptedResponse(statusMsg, wasEncrypted);
    return;
  }
  
  // === SILENCIAR - Silenciar alarma ===
  if (cmdUpper == "SILENCIAR") {
    silenciarAlarma();
    sendEncryptedResponse("OK:SILENCIADO", wasEncrypted);
    return;
  }
  
  // === GET_UUID ===
  if (cmdUpper == "GET_UUID") {
    if (strlen(deviceUUID) > 0) {
      char uuidMsg[64];
      snprintf(uuidMsg, sizeof(uuidMsg), "UUID:%s", deviceUUID);
      sendEncryptedResponse(uuidMsg, wasEncrypted);
    } else {
      generarUUID(deviceUUID);
      preferences.begin("pastillapp", false);
      preferences.putString(UUID_KEY, String(deviceUUID));
      preferences.end();
      char uuidMsg[64];
      snprintf(uuidMsg, sizeof(uuidMsg), "UUID:%s", deviceUUID);
      sendEncryptedResponse(uuidMsg, wasEncrypted);
    }
    return;
  }
  
  // === SEND_UUID ===
  if (cmdUpper == "SEND_UUID") {
    if (strlen(deviceUUID) > 0) {
      char uuidMsg[64];
      snprintf(uuidMsg, sizeof(uuidMsg), "UUID:%s", deviceUUID);
      sendEncryptedResponse(uuidMsg, wasEncrypted);
    } else {
      sendEncryptedResponse("NO_UUID", wasEncrypted);
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
      sendEncryptedResponse("OK:UUID_SET", wasEncrypted);
    } else {
      sendEncryptedResponse("ERROR:INVALID_UUID", wasEncrypted);
    }
    return;
  }
  
  // === PASTILLA - Abrir compartimento ===
  if (cmdUpper == "PASTILLA") {
    if (estado == CERRADO) {
      iniciarAbierto();
      sendEncryptedResponse("OK:ABRIENDO", wasEncrypted);
    } else {
      sendEncryptedResponse("WARN:YA_ABIERTO", wasEncrypted);
    }
    return;
  }
  
  // === SET_EVENTS:{json_array} - Recibir eventos desde Android ===
  if (cmd.startsWith("SET_EVENTS:")) {
    String jsonData = cmd.substring(11);
    jsonData.trim();
    
    Serial.print("[EVENTS] Recibiendo eventos JSON: ");
    Serial.println(jsonData.substring(0, min(100, (int)jsonData.length())));
    
    // Parsear array JSON simple: [{"id":"...","date":"..."},...]
    int receivedCount = 0;
    int pos = jsonData.indexOf('{');
    
    while (pos >= 0 && receivedCount < MAX_EVENTS) {
      int endPos = jsonData.indexOf('}', pos);
      if (endPos > pos) {
        String eventJson = jsonData.substring(pos, endPos + 1);
        PillEvent event;
        if (parseEventFromJson(eventJson, &event)) {
          addEvent(&event);
          receivedCount++;
        }
        pos = jsonData.indexOf('{', endPos);
      } else {
        break;
      }
    }
    
    char response[64];
    snprintf(response, sizeof(response), "OK:EVENTS_RECEIVED:%d", receivedCount);
    sendEncryptedResponse(response, wasEncrypted);
    Serial.print("[EVENTS] Eventos procesados: ");
    Serial.println(receivedCount);
    return;
  }
  
  // === GET_EVENTS - Solicitar eventos desde Android (si tiene internet) ===
  if (cmdUpper == "GET_EVENTS") {
    // Este comando es informativo - el ESP32 no puede forzar al Android a enviar eventos
    // El Android enviará eventos automáticamente cuando esté offline
    sendEncryptedResponse("INFO:REQUEST_EVENTS", wasEncrypted);
    return;
  }
  
  // === GET_LOCAL_EVENTS - Retornar eventos almacenados localmente ===
  if (cmdUpper == "GET_LOCAL_EVENTS") {
    char response[256];
    snprintf(response, sizeof(response), "LOCAL_EVENTS:%d", eventCount);
    sendEncryptedResponse(response, wasEncrypted);
    return;
  }
  
  // Comando no reconocido
  char errorMsg[128];
  snprintf(errorMsg, sizeof(errorMsg), "ERROR:COMANDO_DESCONOCIDO:%s", cmd.c_str());
  sendEncryptedResponse(errorMsg, wasEncrypted);
}

/**
 * Envía una respuesta encriptada o en texto plano según corresponda
 * @param response Texto de respuesta
 * @param encrypt true si debe encriptarse (cuando el comando recibido estaba encriptado)
 */
void sendEncryptedResponse(const char* response, bool encrypt) {
  if (!SerialBT.hasClient()) return;
  
  if (encrypt) {
    char encrypted[256];
    if (encryptResponse(response, encrypted, sizeof(encrypted))) {
      SerialBT.println(encrypted);
    } else {
      Serial.println("[AES] Error encriptando respuesta");
      SerialBT.println("ERROR:ENCRYPT_FAILED");
    }
  } else {
    // Enviar en texto plano (compatibilidad hacia atrás)
    SerialBT.println(response);
  }
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
  
  // Cargar eventos desde almacenamiento
  loadEventsFromStorage();

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
      // Verificar eventos locales para dispensación offline
      if (now - lastEventCheck >= EVENT_CHECK_INTERVAL) {
        lastEventCheck = now;
        checkOfflineEvents();
      }
      break;
  }
}

/**
 * Verifica eventos locales y dispensa si es necesario (modo offline)
 * Nota: Esta función requiere que el ESP32 tenga RTC configurado o reciba la fecha/hora desde Android
 * Por ahora, solo verifica eventos sin hora específica o que ya deberían haberse dispensado
 */
void checkOfflineEvents() {
  // Por simplicidad, verificamos todos los eventos no dispensados
  // En una implementación completa, se necesitaría sincronizar fecha/hora desde Android
  
  for (int i = 0; i < eventCount; i++) {
    if (!events[i].dispensed && estado == CERRADO) {
      // Si el evento no tiene hora específica, dispensarlo
      if (strlen(events[i].time) == 0 || strcmp(events[i].time, "") == 0) {
        Serial.print("[OFFLINE] Dispensando evento sin hora: ");
        Serial.println(events[i].id);
        iniciarAbierto();
        // Marcar como dispensado (se marcará cuando se detecte la pastilla retirada)
        // O después de un tiempo, marcar automáticamente
        return; // Solo uno a la vez
      }
      // TODO: Implementar comparación de hora cuando se tenga RTC o sincronización de tiempo
    }
  }
}
