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
#include "mbedtls/md.h"      // Para HMAC-SHA256

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
// HMAC-SHA256 PARA INTEGRIDAD DE DATOS (3.1.1.2)
// ============================================================================
// Clave HMAC (32 bytes) - DEBE SER LA MISMA QUE EN ANDROID
const unsigned char HMAC_KEY[32] = {
  0x3A, 0x8F, 0x24, 0x17, 0x39, 0xBE, 0xE3, 0xB7,
  0xBA, 0xF8, 0x26, 0x99, 0x1A, 0xDF, 0x5F, 0x4D,
  0x2B, 0x3C, 0x4D, 0x5E, 0x6F, 0x80, 0x91, 0xA2,
  0xB3, 0xC4, 0xD5, 0xE6, 0xF7, 0x08, 0x19, 0x2A
};

// ============================================================================
// OPTIMIZACIÓN ENERGÉTICA (3.1.3.10)
// ============================================================================
unsigned long lastActivityTime = 0;
const unsigned long SLEEP_TIMEOUT = 300000; // 5 minutos sin actividad -> sleep
bool lowPowerMode = false;

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
  
  // Configurar clave
  ret = mbedtls_aes_setkey_dec(&aes, AES_KEY, 256);
  if (ret != 0) {
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
    // Remover padding PKCS5
    int padding = decrypted[encryptedLen - 1];
    if (padding > 0 && padding <= AES_BLOCK_SIZE) {
      encryptedLen -= padding;
    }
    decrypted[encryptedLen] = '\0';
    
    // Copiar resultado
    strncpy(output, (char*)decrypted, outputSize - 1);
    output[outputSize - 1] = '\0';
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
  for (int i = 0; i < AES_IV_SIZE; i++) {
    iv[i] = random(256);
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
    // Combinar IV + datos encriptados
    unsigned char* combined = (unsigned char*)malloc(AES_IV_SIZE + paddedLen);
    if (combined) {
      memcpy(combined, iv, AES_IV_SIZE);
      memcpy(combined + AES_IV_SIZE, encrypted, paddedLen);
      
      // Codificar a Base64
      size_t olen;
      ret = mbedtls_base64_encode((unsigned char*)output, outputSize, &olen, 
                                   combined, AES_IV_SIZE + paddedLen);
      output[olen] = '\0';
      
      free(combined);
    } else {
      ret = -1;
    }
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
 * Genera HMAC-SHA256 para verificación de integridad (3.1.1.2)
 * @param message Mensaje a proteger
 * @param hmacOut Buffer para almacenar HMAC en hexadecimal (65 bytes)
 * @return true si se generó correctamente
 */
bool generateHMAC(const char* message, char* hmacOut) {
  unsigned char hmac[32]; // SHA-256 produce 32 bytes
  
  mbedtls_md_context_t ctx;
  mbedtls_md_type_t md_type = MBEDTLS_MD_SHA256;
  
  mbedtls_md_init(&ctx);
  
  if (mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(md_type), 1) != 0) {
    mbedtls_md_free(&ctx);
    return false;
  }
  
  if (mbedtls_md_hmac_starts(&ctx, HMAC_KEY, 32) != 0) {
    mbedtls_md_free(&ctx);
    return false;
  }
  
  if (mbedtls_md_hmac_update(&ctx, (const unsigned char*)message, strlen(message)) != 0) {
    mbedtls_md_free(&ctx);
    return false;
  }
  
  if (mbedtls_md_hmac_finish(&ctx, hmac) != 0) {
    mbedtls_md_free(&ctx);
    return false;
  }
  
  mbedtls_md_free(&ctx);
  
  // Convertir a hexadecimal
  for (int i = 0; i < 32; i++) {
    sprintf(&hmacOut[i * 2], "%02x", hmac[i]);
  }
  hmacOut[64] = '\0';
  
  return true;
}

/**
 * Verifica HMAC de un mensaje (3.1.1.2)
 * @param message Mensaje recibido
 * @param receivedHMAC HMAC recibido en hexadecimal
 * @return true si el HMAC es válido
 */
bool verifyHMAC(const char* message, const char* receivedHMAC) {
  char calculatedHMAC[65];
  if (!generateHMAC(message, calculatedHMAC)) {
    return false;
  }
  
  // Comparación case-insensitive
  return strcasecmp(calculatedHMAC, receivedHMAC) == 0;
}

/**
 * Extrae y verifica mensaje con formato: mensaje|HMAC (3.1.1.2)
 * @param messageWithHMAC Mensaje con HMAC adjunto
 * @param output Buffer para almacenar mensaje verificado
 * @param outputSize Tamaño del buffer de salida
 * @return true si la integridad es válida
 */
bool verifyAndExtractMessage(const char* messageWithHMAC, char* output, size_t outputSize) {
  // Buscar el separador |
  const char* separator = strchr(messageWithHMAC, '|');
  if (separator == NULL) {
    Serial.println("[HMAC] Formato inválido: se esperaba mensaje|HMAC");
    return false;
  }
  
  // Extraer mensaje
  size_t messageLen = separator - messageWithHMAC;
  if (messageLen >= outputSize) {
    Serial.println("[HMAC] Mensaje demasiado largo");
    return false;
  }
  
  strncpy(output, messageWithHMAC, messageLen);
  output[messageLen] = '\0';
  
  // Extraer HMAC
  const char* receivedHMAC = separator + 1;
  
  // Verificar integridad
  if (verifyHMAC(output, receivedHMAC)) {
    Serial.println("[HMAC] ✓ Integridad verificada");
    return true;
  } else {
    Serial.println("[HMAC] ✗ Integridad comprometida");
    return false;
  }
}

/**
 * Añade HMAC a un mensaje: mensaje|HMAC (3.1.1.2)
 * @param message Mensaje original
 * @param output Buffer para almacenar mensaje|HMAC
 * @param outputSize Tamaño del buffer de salida
 * @return true si se generó correctamente
 */
bool attachHMAC(const char* message, char* output, size_t outputSize) {
  char hmac[65];
  if (!generateHMAC(message, hmac)) {
    return false;
  }
  
  if (strlen(message) + 1 + 64 >= outputSize) {
    Serial.println("[HMAC] Buffer insuficiente");
    return false;
  }
  
  sprintf(output, "%s|%s", message, hmac);
  return true;
}

/**
 * Actualiza timestamp de actividad para gestión de energía (3.1.3.10)
 */
void updateActivityTimestamp() {
  lastActivityTime = millis();
  if (lowPowerMode) {
    Serial.println("[POWER] Saliendo de modo bajo consumo");
    lowPowerMode = false;
  }
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
 * @param cmd Comando recibido (ya trimmeado, puede estar encriptado)
 */
void procesarComando(String cmd) {
  // Actualizar timestamp de actividad (3.1.3.10)
  updateActivityTimestamp();
  
  // Intentar desencriptar el comando (3.1.1.1)
  char decryptedCmd[128];
  bool wasEncrypted = false;
  
  if (isEncrypted(cmd.c_str())) {
    if (decryptCommand(cmd.c_str(), decryptedCmd, sizeof(decryptedCmd))) {
      cmd = String(decryptedCmd);
      wasEncrypted = true;
      Serial.print("[AES] Comando desencriptado: ");
      Serial.println(cmd);
      
      // Verificar integridad con HMAC (3.1.1.2)
      char verifiedMessage[128];
      if (!verifyAndExtractMessage(cmd.c_str(), verifiedMessage, sizeof(verifiedMessage))) {
        Serial.println("[HMAC] Error: Integridad comprometida");
        SerialBT.println("ERROR:INTEGRITY_FAILED");
        return;
      }
      cmd = String(verifiedMessage);
      Serial.println("[HMAC] ✓ Integridad verificada");
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
    // Añadir HMAC a la respuesta (3.1.1.2)
    char responseWithHMAC[256];
    if (!attachHMAC(response, responseWithHMAC, sizeof(responseWithHMAC))) {
      Serial.println("[HMAC] Error añadiendo HMAC a respuesta");
      SerialBT.println("ERROR:HMAC_FAILED");
      return;
    }
    
    // Encriptar respuesta con HMAC (3.1.1.1)
    char encrypted[384];
    if (encryptResponse(responseWithHMAC, encrypted, sizeof(encrypted))) {
      SerialBT.println(encrypted);
      Serial.println("[TX] Respuesta enviada con HMAC y encriptación");
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

  // --- Gestión de energía (3.1.3.10) ---
  if (!lowPowerMode && (now - lastActivityTime) > SLEEP_TIMEOUT) {
    Serial.println("[POWER] Entrando en modo bajo consumo por inactividad");
    lowPowerMode = true;
    // Reducir frecuencia de telemetría
  }

  // --- Detección de conexión Bluetooth ---
  if (SerialBT.hasClient() && !uuidEnviado && strlen(deviceUUID) > 0) {
    updateActivityTimestamp();
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
    updateActivityTimestamp();
    silenciarAlarma();
    delay(50); // antirrebote
  }
  ultimoBtn = btn;

  // --- Procesar comandos Bluetooth ---
  if (SerialBT.available()) {
    updateActivityTimestamp();
    String cmd = SerialBT.readStringUntil('\n');
    cmd.trim();
    if (cmd.length() > 0) {
      procesarComando(cmd);
    }
  }

  // --- Lógica por estado ---
  switch (estado) {
    case ABIERTO:
      // Telemetría LDR (ajustar frecuencia en modo bajo consumo)
      unsigned long intervaloTelemetria = lowPowerMode ? 
        (INTERVALO_TELEMETRIA_LDR * 2) : INTERVALO_TELEMETRIA_LDR;
      
      if (now - tTelemetriaLDR >= intervaloTelemetria) {
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
