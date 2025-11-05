# PastillApp

PastillApp es una aplicación para Android diseñada para ayudar a los usuarios a gestionar su medicación, con un pastillero inteligente.

## Funcionalidades

*   **Autenticación de usuarios:**
    *   Inicio de sesión y registro de usuarios utilizando Supabase.
    *   Validación de campos de correo y contraseña.

*   **Navegación principal:**
    *   Una barra de navegación inferior para cambiar entre las pantallas principales: Inicio, Calendario, Conexión y Cuenta.

*   **Pantalla de Inicio:**
    *   Muestra información relevante sobre los medicamentos del usuario.

*   **Pantalla de Calendario:**
    *   Permite a los usuarios ver su horario de medicación.
    *   Opción para añadir nuevos eventos/recordatorios de medicación.

*   **Pantalla de Conexión:**
    *   Busca y se conecta a pastilleros inteligentes mediante Bluetooth.
    *   Muestra el estado de la conexión.
    *   Guarda el dispositivo conectado para futuras sesiones.

*   **Pantalla de Cuenta:**
    *   Muestra el correo electrónico del usuario.
    *   Permite al usuario cerrar la sesión.

## Tecnologías Integradas

*   **Kotlin:** Como lenguaje de programación principal.
*   **Supabase:** Utilizado como backend para la autenticación de usuarios y la gestión de la base de datos.
*   **Android SDK:**
    *   **Fragments:** Para construir una interfaz de usuario modular.
    *   **Bluetooth:** Para la comunicación con el pastillero inteligente.

## Cómo Empezar

1.  **Clonar el repositorio:**
    ```bash
    git clone https://github.com/tu-usuario/PastillApp.git
    ```

2.  **Abrir en Android Studio:**
    *   Abre Android Studio y selecciona `Open an existing project`.
    *   Navega hasta el directorio del proyecto clonado y ábrelo.

3.  **Configurar Supabase:**
    *   Necesitarás un `SUPABASE_URL` y un `SUPABASE_ANON_KEY`.
    *   Crea un archivo `SupabaseClient.kt` en `app/src/main/java/com/example/pastillero/` y añade tus credenciales:

    ```kotlin
    package com.example.pastillero

    import io.github.jan.supabase.SupabaseClient
    import io.github.jan.supabase.createSupabaseClient
    import io.github.jan.supabase.gotrue.GoTrue

    object SupabaseClient {
        val client: SupabaseClient = createSupabaseClient(
            supabaseUrl = "TU_URL_DE_SUPABASE",
            supabaseKey = "TU_LLAVE_ANONIMA_DE_SUPABASE"
        ) {
            install(GoTrue)
        }
    }
    ```

4.  **Ejecutar la aplicación:**
    *   Selecciona un emulador o un dispositivo físico y haz clic en el botón `Run`.

## Licencia

Este proyecto está licenciado bajo la Licencia MIT. Consulta el archivo `LICENSE` para más detalles.
