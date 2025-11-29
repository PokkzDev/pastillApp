# PastillApp – Firestore & Auth Data Model (Prototipo)

Este proyecto es un prototipo de sistema compuesto por:

- **App Android (Enfermera)**
- **Dispositivo físico PastillApp (ESP32 Devkit1, pastillero)**
- **Firebase Auth (login enfermera)**
- **Cloud Firestore (datos mínimos)**

**Objetivo:** permitir que una enfermera:

- Registre un dispositivo PastillApp (ESP32) y le asigne un UUID.
- Asigne ese dispositivo a un paciente.
- Configure horarios de medicación.
- Registre si una toma fue realizada o no.

La estructura debe ser simple, legible y fácil de defender.

Firestore es schema-less, pero este archivo define el contrato que el código debe respetar.

## 1. Resumen de colecciones

Colecciones y subcolecciones usadas:

- `patients` – Pacientes gestionados por la enfermera.
- `devices` – Dispositivos físicos PastillApp (ESP32) registrados.
- `patients/{patientId}/schedules` – Horarios configurados para un paciente.
- `patients/{patientId}/intakes` – Registro de tomas realizadas / no realizadas.

**Autenticación:**

Se usa Firebase Auth.

El `uid` del usuario autenticado representa a la enfermera o usuario que realiza acciones.

## 2. Colección patients

**Ruta:** `patients/{patientId}`

`patientId` es un ID generado por Firestore (o por la app).

**Campos:**

```json
{
  "nombre": "string",              // Nombre del paciente
  "rut": "string",                 // Opcional para prototipo
  "enfermeraUid": "string",        // uid de Firebase Auth del responsable
  "deviceId": "string|null",       // UUID del dispositivo PastillApp asignado (1 a 1 en el prototipo)
  "creadoEn": "timestamp"          // FieldValue.serverTimestamp()
}
```

**Reglas para la IA/código:**

- Filtrar pacientes por `enfermeraUid` para mostrar solo los del usuario logueado.
- Inicialmente `deviceId` puede ser `null`; se setea cuando se registra el dispositivo.

## 3. Colección devices

Representa el pastillero físico (ESP32) registrado.

**Ruta:** `devices/{deviceId}`

`deviceId` = UUID generado por la app y enviado al ESP32.

El ESP32 guarda ese UUID localmente (NVS/EEPROM).

**Campos:**

```json
{
  "patientId": "string",        // Id del paciente al que está asociado
  "enfermeraUid": "string",     // uid de quien lo registró
  "macAddress": "string",       // MAC Bluetooth del ESP32 (opcional pero recomendado)
  "creadoEn": "timestamp"       // FieldValue.serverTimestamp()
}
```

**Flujo esperado:**

1. App genera UUID.
2. App envía UUID al ESP32 vía Bluetooth.
3. Si ESP32 responde OK:
   - Crear doc en `devices/{UUID}`.
   - Actualizar `patients/{patientId}.deviceId = UUID`.

## 4. Subcolección schedules (Horarios por paciente)

Horarios simples para el prototipo (1 horario = 1 hora fija diaria).

**Ruta:** `patients/{patientId}/schedules/{scheduleId}`

**Campos:**

```json
{
  "deviceId": "string",         // UUID del dispositivo asignado
  "medicamento": "string",      // Ej: "Paracetamol 500mg"
  "dosis": 1,                   // número de pastillas/unidades
  "hora": "string",             // Ej: "08:00" (HH:mm)
  "activo": true,               // Permite habilitar/deshabilitar
  "creadoEn": "timestamp"
}
```

**Reglas:**

- Para varias tomas al día → crear varios documentos (ej: 08:00 y 20:00).
- La app puede leer estos horarios y enviarlos al ESP32 vía Bluetooth.

## 5. Subcolección intakes (Registro de tomas)

Registra el resultado de cada toma programada: tomada o no tomada.

**Ruta:** `patients/{patientId}/intakes/{intakeId}`

**Campos:**

```json
{
  "scheduleId": "string",         // Id del horario asociado
  "deviceId": "string",           // UUID del dispositivo PastillApp
  "fechaProgramada": "timestamp", // Día y hora que correspondía la toma
  "tomado": true,                 // true = se tomó, false = no
  "registradoPorUid": "string",   // uid de quien registra (app/enfermera)
  "registradoEn": "timestamp"     // Momento en que se guarda el registro
}
```

**Posible comportamiento:**

- Cuando el ESP32 + app detectan retiro (LDR / confirmación):
  - Crear doc con `tomado: true`.
- Si se deja pasar el tiempo sin detección:
  - Crear doc con `tomado: false` (manual o automático).

## 6. Notas para la IA del IDE

- No crear "schemas" en Firestore, solo usar estas rutas y campos al leer/escribir.
- Siempre usar `FirebaseAuth.getInstance().currentUser?.uid` para `enfermeraUid` y `registradoPorUid`.
- Mantener 1:1 `patient ↔ device` en este prototipo para simplificar.
- Subcolecciones dependen del `patientId`:
  - Nunca crear `schedules` o `intakes` sueltos fuera de `patients/{patientId}`.
- Usar `FieldValue.serverTimestamp()` para `creadoEn` y `registradoEn` cuando sea posible.

Con esto la IA de tu IDE ya tiene el mapa completo para generar código coherente con tu prototipo PastillApp.
