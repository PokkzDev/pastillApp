# PastillApp ESP32 - Documentación Técnica Completa

## Índice

1. [Descripción General](#descripción-general)
2. [Hardware](#hardware)
3. [Instalación y Configuración](#instalación-y-configuración)
4. [Estados del Sistema](#estados-del-sistema)
5. [Protocolo de Comunicación Bluetooth](#protocolo-de-comunicación-bluetooth)
6. [Integración con App Android](#integración-con-app-android)
7. [Detección de Retiro de Pastilla](#detección-de-retiro-de-pastilla)
8. [Almacenamiento Persistente](#almacenamiento-persistente-nvs)
9. [Funciones del Código](#funciones-del-código)
10. [Ejemplos de Uso](#ejemplos-de-uso)
11. [Troubleshooting](#troubleshooting)
12. [Especificaciones Técnicas](#especificaciones-técnicas)

---

## Descripción General

El **PastillApp ESP32** es un dispensador inteligente de medicamentos diseñado para asistir en la administración controlada de pastillas. El sistema:

- ✅ Abre el compartimento mediante comando Bluetooth
- ✅ Detecta automáticamente cuando el usuario retira la pastilla (sensor LDR)
- ✅ Notifica a la aplicación Android en tiempo real
- ✅ Cierra automáticamente tras un período de espera
- ✅ Proporciona alertas audibles y visuales
- ✅ Mantiene conexión persistente con reconexión automática

### Características Principales

| Característica | Descripción |
|----------------|-------------|
| **Comunicación** | Bluetooth Classic SPP |
| **Detección** | Sensor LDR con algoritmo adaptativo |
| **Alertas** | LED parpadeante + Buzzer |
| **Actuador** | Servo motor SG90 |
| **Almacenamiento** | UUID persistente en NVS |
| **Reconexión** | Automática desde la app Android |

---

## Hardware

### Placa de Desarrollo

| Especificación | Valor |
|----------------|-------|
| **Modelo** | ESP32 DevKit V1 |
| **Microcontrolador** | ESP32-WROOM-32 |
| **CPU** | Dual-core Xtensa LX6 @ 240MHz |
| **RAM** | 520 KB SRAM |
| **Flash** | 4 MB |
| **Conectividad** | WiFi 802.11 b/g/n + Bluetooth Classic + BLE |
| **Voltaje** | 3.3V (5V USB) |

### Diagrama de Conexiones

```
                          ESP32 DevKit V1
                    ┌─────────────────────────┐
                    │                         │
    ┌───────────────┤ GPIO13 (D13)           │
    │   Servo       │         ▲               │
    │   Signal      │         │               │
    └───────────────┘         │               │
                              │               │
    ┌───────────────┐         │               │
    │   Botón       ├─────────┤ GPIO12 (D12)  │
    │   (Pull-up)   │         │               │
    └───────────────┘         │               │
                              │               │
    ┌───────────────┐         │               │
    │   Buzzer (+)  ├─────────┤ GPIO14 (D14)  │
    │               │         │               │
    └───────────────┘         │               │
                              │               │
    ┌───────────────┐         │               │
    │   LED (+)     ├─────────┤ GPIO27 (D27)  │
    │               │         │               │
    └───────────────┘         │               │
                              │               │
    ┌───────────────┐         │               │
    │   LDR         ├─────────┤ GPIO35 (ADC)  │
    │   (Divisor)   │         │               │
    └───────────────┘         │               │
                              │               │
                    │  3.3V ──┼── VCC         │
                    │  GND ───┼── GND         │
                    └─────────────────────────┘
```

### Circuito del Divisor de Voltaje LDR

```
    3.3V
     │
     ├───────┐
     │       │
    ┌┴┐     ┌┴┐
    │ │ R1  │ │ LDR
    │ │10kΩ │ │ 
    └┬┘     └┬┘
     │       │
     └───┬───┘
         │
         ├──────► GPIO35 (ADC Input)
         │
        ─┴─
        GND
```

### Pinout Detallado

| Pin ESP32 | GPIO | Componente | Tipo | Función | Notas |
|-----------|------|------------|------|---------|-------|
| D13 | GPIO13 | Servo SG90 | Output PWM | Señal de control | 500-2400µs |
| D12 | GPIO12 | Botón | Input | Silenciar alarma | Pull-up interno, activo LOW |
| D14 | GPIO14 | Buzzer | Output Digital | Alarma sonora | HIGH = Sonando |
| D27 | GPIO27 | LED | Output Digital | Indicador visual | HIGH = Encendido |
| VP | GPIO35 | LDR | ADC Input | Sensor de luz | ADC1_CH7, 12-bit |
| 3V3 | - | - | Power | Alimentación | 3.3V regulados |
| GND | - | - | Ground | Tierra común | - |

### Especificaciones de Componentes

#### 1. Servo Motor (SG90/MG90S)

```
Especificaciones:
├── Voltaje operación: 4.8V - 6V
├── Torque: 1.8 kg·cm (4.8V)
├── Velocidad: 0.1s/60° (4.8V)
├── Ángulo rotación: 0° - 180°
├── Pulso mínimo: 500µs
├── Pulso máximo: 2400µs
└── Frecuencia PWM: 50Hz

Configuración en código:
├── ANGULO_CERRADO = 10°
├── ANGULO_ABIERTO = 90°
└── DURACION_MOV = 1500ms (transición suave)
```

#### 2. Sensor LDR (Fotorresistor)

```
Especificaciones:
├── Resistencia luz: 1kΩ - 10kΩ
├── Resistencia oscuridad: >1MΩ
├── Sensibilidad espectral: 540nm (verde)
└── Tiempo respuesta: ~20ms

Configuración ADC:
├── Resolución: 12 bits (0-4095)
├── Atenuación: 6dB (~2.2V full-scale)
├── Muestras promedio: 16
└── Intervalo muestreo: 250µs entre muestras
```

#### 3. Buzzer Activo

```
Especificaciones:
├── Tipo: Activo (oscilador interno)
├── Voltaje: 3.3V - 5V
├── Frecuencia: ~2.3kHz
└── Control: Digital (HIGH/LOW)

Modos de operación:
├── Continuo: Estado ABIERTO (si no silenciado)
└── Intermitente: 250ms ON/OFF en cuenta regresiva
```

#### 4. LED Indicador

```
Especificaciones:
├── Color: Según preferencia (recomendado verde/rojo)
├── Voltaje directo: 2V - 3.3V
├── Corriente: 10-20mA
└── Resistencia limitadora: 100Ω - 330Ω

Patrones de parpadeo:
├── Normal (ABIERTO): 500ms ON/OFF
└── Alerta (CUENTA_REGRESIVA): 150ms ON/OFF
```

#### 5. Botón Físico

```
Especificaciones:
├── Tipo: Normalmente abierto (NO)
├── Configuración: INPUT_PULLUP
├── Activo: LOW (presionado)
└── Antirrebote: 50ms por software

Función:
└── Silenciar alarma (buzzer) manualmente
```

---

## Instalación y Configuración

### Requisitos de Software

1. **Arduino IDE** 2.x o **PlatformIO**
2. **ESP32 Board Package** (Espressif)
3. **Librerías requeridas**:
   - `ESP32Servo` - Control de servomotor
   - `BluetoothSerial` - Comunicación SPP (incluida en ESP32)
   - `Preferences` - Almacenamiento NVS (incluida en ESP32)

### Instalación de Librerías

```bash
# Arduino IDE - Gestor de Librerías
ESP32Servo by Kevin Harrington

# PlatformIO
pio lib install "ESP32Servo"
```

### Configuración del Arduino IDE

1. **Agregar URL de placas ESP32**:
   - Archivo → Preferencias → URLs adicionales:
   ```
   https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
   ```

2. **Instalar paquete ESP32**:
   - Herramientas → Placa → Gestor de placas → Buscar "ESP32"

3. **Seleccionar placa**:
   - Herramientas → Placa → ESP32 Arduino → ESP32 Dev Module

4. **Configuración de upload**:
   - Upload Speed: 921600
   - Flash Frequency: 80MHz
   - Flash Mode: QIO

### Subir el Código

1. Conectar ESP32 por USB
2. Seleccionar puerto COM correcto
3. Presionar botón BOOT en ESP32 si es necesario
4. Click en "Subir"

---

## Estados del Sistema

### Diagrama de Estados

```
┌──────────────────────────────────────────────────────────────────────────┐
│                                                                          │
│    ╔═══════════╗                              ╔═══════════╗              │
│    ║           ║      Comando "PASTILLA"      ║           ║              │
│    ║  CERRADO  ║ ════════════════════════════>║  ABIERTO  ║              │
│    ║           ║                              ║           ║              │
│    ╚═══════════╝                              ╚═════╤═════╝              │
│          ▲                                          │                    │
│          │                                          │ Detección LDR      │
│          │                                          │ (mano/sombra)      │
│          │                                          ▼                    │
│          │                              ╔══════════════════════╗         │
│          │       10 segundos            ║                      ║         │
│          ╚══════════════════════════════║  CUENTA_REGRESIVA    ║         │
│                                         ║                      ║         │
│                                         ╚══════════════════════╝         │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘

Leyenda:
═══════> Transición automática o por comando
──────── Transición por timeout
```

### Estado: CERRADO

| Aspecto | Valor/Comportamiento |
|---------|---------------------|
| **Servo** | Posición 10° (cerrado) |
| **LED** | Apagado |
| **Buzzer** | Apagado |
| **LDR** | No monitoreando |
| **Telemetría** | No envía datos LDR |
| **Transición** | Comando `PASTILLA` → ABIERTO |

### Estado: ABIERTO

| Aspecto | Valor/Comportamiento |
|---------|---------------------|
| **Servo** | Posición 90° (abierto) |
| **LED** | Parpadeo 500ms ON/OFF |
| **Buzzer** | Continuo (si no silenciado) |
| **LDR** | Monitoreando activamente |
| **Telemetría** | `LDR={valor}` cada 1 segundo |
| **Transición** | Detección de mano → CUENTA_REGRESIVA |

### Estado: CUENTA_REGRESIVA

| Aspecto | Valor/Comportamiento |
|---------|---------------------|
| **Servo** | Mantiene 90° (abierto) |
| **LED** | Parpadeo rápido 150ms ON/OFF |
| **Buzzer** | Intermitente 250ms ON/OFF |
| **LDR** | Continúa monitoreando |
| **Telemetría** | `LDR={valor}` cada 1 segundo |
| **Duración** | 10 segundos |
| **Transición** | Timeout → CERRADO |

---

## Protocolo de Comunicación Bluetooth

### Configuración SPP

| Parámetro | Valor |
|-----------|-------|
| **Nombre dispositivo** | `PastillApp V1` |
| **Perfil** | SPP (Serial Port Profile) |
| **UUID SPP** | `00001101-0000-1000-8000-00805F9B34FB` |
| **Baudrate efectivo** | 115200 bps |
| **Terminador de línea** | `\n` (LF) |

### Comandos (App → ESP32)

| Comando | Respuesta Exitosa | Respuesta Error | Descripción |
|---------|-------------------|-----------------|-------------|
| `PING` | `PONG` | - | Heartbeat para verificar conexión |
| `STATUS` | `STATUS:{estado}` | - | Obtener estado actual (CERRADO/ABIERTO/CUENTA_REGRESIVA) |
| `GET_UUID` | `UUID:{uuid}` | - | Obtener UUID del dispositivo |
| `SEND_UUID` | `UUID:{uuid}` | `NO_UUID` | Solicitar envío de UUID |
| `SET_UUID:{id}` | `OK:UUID_SET` | `ERROR:INVALID_UUID` | Establecer nuevo UUID (máx 36 chars) |
| `PASTILLA` | `OK:ABRIENDO` | `WARN:YA_ABIERTO` | Abrir compartimento |
| `SILENCIAR` | `OK:SILENCIADO` | - | Silenciar alarma/buzzer |
| `{otro}` | - | `ERROR:COMANDO_DESCONOCIDO:{cmd}` | Comando no reconocido |

### Eventos (ESP32 → App)

| Evento | Momento de Envío | Descripción |
|--------|------------------|-------------|
| `EVENT:CONNECTED` | Al conectar cliente BT | Nuevo cliente conectado |
| `EVENT:OPENED` | Al abrir compartimento | Servo movido a posición abierta |
| `EVENT:PILL_TAKEN` | Al detectar retiro | Sensor LDR detectó cambio significativo |
| `EVENT:CLOSING` | Al iniciar cuenta regresiva | Comenzó período de 10 segundos |
| `EVENT:CLOSED` | Al cerrar compartimento | Servo movido a posición cerrada |
| `EVENT:ALARM_SILENCED` | Al silenciar alarma | Buzzer silenciado (botón o comando) |

### Telemetría Continua

| Mensaje | Formato | Frecuencia | Cuándo se envía |
|---------|---------|------------|-----------------|
| Valor LDR | `LDR={0-4095}` | Cada 1000ms | Estados ABIERTO y CUENTA_REGRESIVA |
| Estado | `STATUS:{estado}` | Bajo demanda | Al conectar y con comando STATUS |
| UUID | `UUID:{uuid}` | Una vez | Al conectar nuevo cliente |

### Secuencia de Conexión Completa

```
┌─────────────────┐                              ┌─────────────────┐
│   App Android   │                              │     ESP32       │
└────────┬────────┘                              └────────┬────────┘
         │                                                │
         │──────── Conexión SPP ─────────────────────────>│
         │                                                │
         │<──────── EVENT:CONNECTED ──────────────────────│
         │<──────── UUID:xxxxxxxx-xxxx-xxxx-xxxx-xxx ─────│
         │<──────── STATUS:CERRADO ───────────────────────│
         │                                                │
         │ ═══════════ Heartbeat Loop (cada 5s) ══════════│
         │                                                │
         │──────── PING ─────────────────────────────────>│
         │<──────── PONG ─────────────────────────────────│
         │                                                │
         │ ═══════════ Dispensación ══════════════════════│
         │                                                │
         │──────── PASTILLA ─────────────────────────────>│
         │<──────── OK:ABRIENDO ──────────────────────────│
         │<──────── EVENT:OPENED ─────────────────────────│
         │<──────── LDR=2048 (cada 1s) ───────────────────│
         │                   ...                          │
         │<──────── EVENT:PILL_TAKEN ─────────────────────│
         │<──────── EVENT:CLOSING ────────────────────────│
         │                (10 segundos)                   │
         │<──────── EVENT:CLOSED ─────────────────────────│
         │                                                │
```

---

## Integración con App Android

### Servicio de Conexión Bluetooth

La app Android implementa un **Foreground Service** (`BluetoothConnectionService.kt`) que:

1. **Mantiene conexión persistente** en segundo plano
2. **Reconecta automáticamente** con backoff exponencial (1s → 30s máx)
3. **Envía heartbeat** cada 5 segundos (`PING`/`PONG`)
4. **Notifica cambios** de estado a la UI

### Flujo de Reconexión

```
┌───────────────────────────────────────────────────────────────┐
│                                                               │
│  Conexión       Reconectar      Reconectar     Reconectar    │
│  Perdida  ──►   en 1s     ──►   en 2s    ──►   en 4s    ──►  │
│                    │               │              │           │
│                    ▼               ▼              ▼           │
│              ¿Éxito?         ¿Éxito?        ¿Éxito?          │
│               No  │           No  │          No  │           │
│                   ▼               ▼              ▼           │
│              Reconectar     Reconectar    ... hasta 30s      │
│                                                               │
│              Si ──────────────────────────────────────────►   │
│                         Conexión Restablecida                 │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

### Componentes Android Relacionados

| Archivo | Propósito |
|---------|-----------|
| `BluetoothConnectionService.kt` | Foreground service para conexión persistente |
| `BluetoothSerialService.kt` | Comunicación serial SPP |
| `HomeFragment.kt` | UI principal con controles del dispositivo |
| `ConnectionFragment.kt` | Escaneo y emparejamiento de dispositivos |

### Permisos Android Requeridos

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

---

## Detección de Retiro de Pastilla

### Algoritmo de Detección LDR

El sistema utiliza un algoritmo de **baseline adaptativo** con **doble umbral**:

```cpp
// 1. Actualización de baseline (media móvil exponencial)
baselineLDR = 0.98 * baselineLDR + 0.02 * lecturaActual;

// 2. Cálculo de diferencias
float absDelta = |lectura - baseline|;
float deltaRel = absDelta / baseline;

// 3. Detección de umbral (cualquiera de las dos condiciones)
bool umbralSuperado = (deltaRel >= 0.25) || (absDelta >= 120);

// 4. Confirmación temporal (debe mantenerse 80ms)
if (umbralSuperado && tiempo_sostenido >= 80ms) {
    cambioDetectado = true;
    iniciarCuentaRegresiva();
}
```

### Diagrama del Algoritmo

```
    Lectura LDR
         │
         ▼
┌────────────────────┐
│ Actualizar Baseline│ ←── Factor: 0.98/0.02
└─────────┬──────────┘
          │
          ▼
┌────────────────────┐
│ Calcular Deltas    │
│ - Absoluto         │
│ - Relativo (%)     │
└─────────┬──────────┘
          │
          ▼
┌────────────────────────────┐
│ ¿Delta Rel >= 25%          │
│    OR                      │───── No ────► Reiniciar Timer
│ ¿Delta Abs >= 120?         │
└─────────┬──────────────────┘
          │ Sí
          ▼
┌────────────────────┐
│ ¿Sostenido >= 80ms?│───── No ────► Esperar
└─────────┬──────────┘
          │ Sí
          ▼
┌────────────────────┐
│ ¡PASTILLA RETIRADA!│
│ Iniciar Cuenta     │
└────────────────────┘
```

### Parámetros de Calibración

| Parámetro | Valor Default | Rango Sugerido | Descripción |
|-----------|---------------|----------------|-------------|
| `UMBRAL_RELATIVO` | 0.25 (25%) | 0.15 - 0.40 | Cambio porcentual para trigger |
| `UMBRAL_ABS` | 120 | 80 - 200 | Cambio absoluto en cuentas ADC |
| `T_CONFIRMACION` | 80ms | 50 - 150ms | Tiempo mínimo de cambio sostenido |
| `PROMEDIO_LDR` | 16 | 8 - 32 | Muestras para suavizado |
| `Factor baseline` | 0.98/0.02 | - | Velocidad de adaptación |

### Ajustes según Ambiente

| Condición | Ajuste Recomendado |
|-----------|-------------------|
| **Luz ambiente alta** | Aumentar `UMBRAL_RELATIVO` a 0.30-0.35 |
| **Luz ambiente baja** | Reducir `UMBRAL_ABS` a 80-100 |
| **Muchas sombras** | Aumentar `T_CONFIRMACION` a 100-120ms |
| **Detección lenta** | Reducir `T_CONFIRMACION` a 50-60ms |
| **Falsos positivos** | Aumentar ambos umbrales |

---

## Almacenamiento Persistente (NVS)

### Configuración NVS

| Parámetro | Valor |
|-----------|-------|
| **Namespace** | `pastillapp` |
| **Key** | `device_uuid` |
| **Tipo** | String |
| **Longitud máxima** | 36 caracteres |

### Generación de UUID

El UUID se genera automáticamente la primera vez usando:

```cpp
void generarUUID(char* uuid) {
    // 1. Obtener Chip ID (derivado de MAC address)
    uint32_t chipId = ESP.getEfuseMac();
    
    // 2. Obtener timestamp
    unsigned long tiempo = millis();
    
    // 3. Formatear UUID
    // Formato: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
    sprintf(uuid, "%08lx-%04lx-%04lx-%04lx-%08lx%04lx", ...);
}
```

### Ciclo de Vida del UUID

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│   Primer Boot ──► ¿UUID existe? ──► No ──► Generar UUID    │
│                         │                        │          │
│                        Sí                       ▼          │
│                         │              Guardar en NVS       │
│                         ▼                        │          │
│                   Cargar UUID                    │          │
│                         │◄───────────────────────┘          │
│                         ▼                                   │
│                   Usar en BT                                │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## Funciones del Código

### Funciones Principales

| Función | Descripción |
|---------|-------------|
| `setup()` | Inicialización de hardware, BT, y carga de UUID |
| `loop()` | Bucle principal: conexión BT, comandos, lógica de estados |
| `procesarComando(String cmd)` | Parser de comandos Bluetooth |

### Funciones de Estado

| Función | Descripción |
|---------|-------------|
| `iniciarAbierto()` | Transición a estado ABIERTO |
| `iniciarCuentaRegresiva()` | Transición a estado CUENTA_REGRESIVA |
| `cerrarCompartimento()` | Transición a estado CERRADO |
| `silenciarAlarma()` | Silencia buzzer y envía evento |

### Funciones Auxiliares

| Función | Descripción |
|---------|-------------|
| `leerLDRPromedio()` | Lee LDR con promedio de 16 muestras |
| `moverSuave(srv, desde, hasta, ms)` | Movimiento suave del servo |
| `getNombreEstado()` | Retorna nombre del estado actual |
| `enviarEstado()` | Envía `STATUS:{estado}` por BT |
| `enviarEvento(evento)` | Envía `EVENT:{evento}` por BT |
| `generarUUID(uuid)` | Genera UUID único |

---

## Ejemplos de Uso

### Ejemplo 1: Ciclo Completo de Dispensación

```
Tiempo  Acción                          Mensaje BT
────────────────────────────────────────────────────────
T+0     App conecta                     EVENT:CONNECTED
T+0     ESP32 envía UUID                UUID:a1b2c3d4-...
T+0     ESP32 envía estado              STATUS:CERRADO
T+1     App envía heartbeat             PING
T+1     ESP32 responde                  PONG
T+5     App envía comando               PASTILLA
T+5     ESP32 responde                  OK:ABRIENDO
T+6     Servo en posición               EVENT:OPENED
T+7     Telemetría                      LDR=2048
T+8     Telemetría                      LDR=2052
T+9     Usuario retira pastilla         EVENT:PILL_TAKEN
T+9     Inicia cuenta regresiva         EVENT:CLOSING
T+10    Telemetría                      LDR=1856
...
T+19    Fin de cuenta regresiva         EVENT:CLOSED
T+20    ESP32 vuelve a esperar          STATUS:CERRADO
```

### Ejemplo 2: Silenciar Alarma

```
# Desde la app
App → ESP32: SILENCIAR
ESP32 → App: OK:SILENCIADO
ESP32 → App: EVENT:ALARM_SILENCED

# Desde el botón físico
[Usuario presiona botón]
ESP32 → App: EVENT:ALARM_SILENCED
```

### Ejemplo 3: Monitoreo de Conexión

```
# Heartbeat exitoso (cada 5 segundos)
App → ESP32: PING
ESP32 → App: PONG

# Si falla 3 veces consecutivas
App: Iniciar reconexión automática
     ├── Intento 1: 1 segundo
     ├── Intento 2: 2 segundos
     ├── Intento 3: 4 segundos
     └── ... hasta máximo 30 segundos
```

---

## Troubleshooting

### Problemas de Conexión

| Problema | Causa Probable | Solución |
|----------|----------------|----------|
| No aparece en escaneo | BT no inicializado | Verificar `SerialBT.begin()` |
| No conecta | No emparejado | Emparejar desde ajustes Android |
| Conexión inestable | Interferencia | Acercar dispositivos |
| UUID no se recibe | Conexión muy rápida | Esperar 1-2s tras conectar |

### Problemas de Hardware

| Problema | Causa Probable | Solución |
|----------|----------------|----------|
| Servo no mueve | Alimentación insuficiente | Usar fuente externa 5V |
| Servo vibra | Señal PWM incorrecta | Verificar pines y pulsos |
| LDR no detecta | Divisor mal conectado | Verificar circuito |
| Buzzer silencioso | Pin incorrecto | Verificar GPIO14 |
| LED no enciende | Resistencia faltante | Agregar 220Ω en serie |

### Problemas de Detección

| Problema | Causa Probable | Solución |
|----------|----------------|----------|
| No detecta mano | Umbral muy alto | Reducir `UMBRAL_RELATIVO` |
| Falsos positivos | Umbral muy bajo | Aumentar `T_CONFIRMACION` |
| Detección lenta | Confirmación larga | Reducir `T_CONFIRMACION` |
| Inconsistente | Luz variable | Aumentar `PROMEDIO_LDR` |

### Depuración Serial

Para habilitar logs de depuración:

```cpp
// En setup()
Serial.begin(115200);

// Agregar logs donde sea necesario
Serial.println("DEBUG: Estado actual = " + String(getNombreEstado()));
Serial.println("DEBUG: LDR = " + String(leerLDRPromedio()));
```

---

## Especificaciones Técnicas

### Consumo de Energía

| Estado | Consumo Estimado |
|--------|------------------|
| CERRADO (idle) | ~80mA |
| ABIERTO (buzzer ON) | ~120mA |
| Movimiento servo | ~250mA (pico) |

### Tiempos de Respuesta

| Operación | Tiempo |
|-----------|--------|
| Conexión BT | 1-3 segundos |
| Respuesta a comando | <50ms |
| Movimiento servo | 1500ms |
| Detección LDR | ~80ms (confirmación) |

### Límites del Sistema

| Parámetro | Límite |
|-----------|--------|
| Longitud UUID | 36 caracteres |
| Longitud comando | ~100 caracteres |
| Clientes BT simultáneos | 1 |
| Rango BT típico | 10 metros |

---

## Historial de Versiones

| Versión | Fecha | Cambios |
|---------|-------|---------|
| 1.0 | - | Versión inicial con funcionalidad básica |
| 2.0 | Nov 2024 | Comandos STATUS, SILENCIAR, PING. Eventos estructurados. Integración con Foreground Service Android |

---

## Licencia

Este código es parte del proyecto **PastillApp** y está destinado para uso educativo y personal.

© 2024 PastillApp - Todos los derechos reservados
