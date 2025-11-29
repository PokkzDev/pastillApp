package com.pokkzdev.pastillapp

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class ConnectionFragment : Fragment() {
    private lateinit var findDevicesButton: Button
    private lateinit var devicesRecyclerView: RecyclerView
    private lateinit var bluetoothDeviceAdapter: BluetoothDeviceAdapter
    private lateinit var connectedDeviceCard: MaterialCardView
    private lateinit var connectedDeviceName: TextView
    private lateinit var connectedDeviceAddress: TextView
    private lateinit var disconnectButton: Button
    private lateinit var connectionStatusTextView: TextView
    private lateinit var availableDevicesTitle: TextView
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val discoveredDevices = mutableListOf<BluetoothDevice>()

    private val bluetoothViewModel: BluetoothViewModel by activityViewModels()
    private var pairingHandler: BluetoothPairingHandler? = null
    private var bluetoothSerialService: BluetoothSerialService? = null

    /**
     * Verifica si un dispositivo tiene un nombre válido (no null ni "Dispositivo Desconocido")
     */
    private fun hasValidDeviceName(device: BluetoothDevice): Boolean {
        return if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            val deviceName = device.name
            deviceName != null && deviceName.isNotBlank() && deviceName != "Dispositivo Desconocido"
        } else {
            false
        }
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.action
            if (BluetoothDevice.ACTION_FOUND == action) {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                device?.let {
                    // Solo agregar dispositivos con nombres válidos
                    if (it !in discoveredDevices && isAdded && hasValidDeviceName(it)) {
                        discoveredDevices.add(it)
                        bluetoothDeviceAdapter.notifyItemInserted(discoveredDevices.size - 1)
                    }
                }
            }
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            scanDevices()
        } else {
            Toast.makeText(requireContext(), "Permissions are required to scan devices", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_connection, container, false)

        findDevicesButton = view.findViewById(R.id.find_devices_button)
        devicesRecyclerView = view.findViewById(R.id.devices_recycler_view)
        connectedDeviceCard = view.findViewById(R.id.connected_device_card)
        connectedDeviceName = view.findViewById(R.id.connected_device_name)
        connectedDeviceAddress = view.findViewById(R.id.connected_device_address)
        disconnectButton = view.findViewById(R.id.disconnect_button)
        connectionStatusTextView = view.findViewById(R.id.connection_status_textview)
        availableDevicesTitle = view.findViewById(R.id.available_devices_title)

        devicesRecyclerView.layoutManager = LinearLayoutManager(context)
        bluetoothDeviceAdapter = BluetoothDeviceAdapter(discoveredDevices) { device ->
            showPairingDialog(device)
        }
        devicesRecyclerView.adapter = bluetoothDeviceAdapter

        // Initialize pairing handler
        pairingHandler = BluetoothPairingHandler(
            context = requireContext(),
            onPairingSuccess = { device ->
                Toast.makeText(requireContext(), "Emparejamiento exitoso con ${device.name}", Toast.LENGTH_SHORT).show()
                SelectedDevice.device = device
                updateConnectionState()
                
                // Si es un dispositivo PastillApp, intentar registrar
                if (device.name?.startsWith("PastillApp") == true) {
                    handlePastillAppRegistration(device)
                }
            },
            onPairingFailed = { device ->
                Toast.makeText(requireContext(), "Falló el emparejamiento con ${device?.name ?: "dispositivo"}", Toast.LENGTH_SHORT).show()
            }
        )
        pairingHandler?.register()

        findDevicesButton.setOnClickListener {
            if (bluetoothAdapter == null) {
                Toast.makeText(requireContext(), "Bluetooth no está disponible en este dispositivo", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (checkAndRequestPermissions()) {
                scanDevices()
            }
        }

        disconnectButton.setOnClickListener {
            disconnectDevice()
        }

        connectedDeviceCard.setOnClickListener {
            SelectedDevice.device?.let {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment, DeviceDetailFragment())
                    .addToBackStack(null)
                    .commit()
            }
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        requireActivity().registerReceiver(discoveryReceiver, filter)

        // Initialize button state based on Bluetooth availability
        if (bluetoothAdapter == null) {
            findDevicesButton.isEnabled = false
            findDevicesButton.text = getString(R.string.bluetooth_not_available)
        }

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Stop bluetooth discovery if it's running
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (e: SecurityException) {
            // Ignore if we don't have permission
        }
        // Unregister the broadcast receiver
        try {
            requireActivity().unregisterReceiver(discoveryReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, ignore
        }
        // Unregister pairing handler
        pairingHandler?.unregister()
        pairingHandler = null
        
        // Close Bluetooth Serial connection
        bluetoothSerialService?.close()
        bluetoothSerialService = null
    }
    
    override fun onPause() {
        super.onPause()
        // Stop bluetooth discovery when leaving the fragment
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (e: SecurityException) {
            // Ignore if we don't have permission
        }
    }

    override fun onResume() {
        super.onResume()
        updateConnectionState()
        
        // Disable Bluetooth features if adapter is not available (e.g., on emulator)
        if (bluetoothAdapter == null) {
            findDevicesButton.isEnabled = false
            findDevicesButton.text = getString(R.string.bluetooth_not_available)
        } else {
            findDevicesButton.isEnabled = true
            findDevicesButton.text = getString(R.string.find_devices_button)
        }
    }

    private fun updateConnectionState() {
        val device = SelectedDevice.device
        if (device != null) {
            bluetoothViewModel.setConnected(true)
            connectedDeviceCard.visibility = View.VISIBLE
            connectionStatusTextView.text = getString(R.string.connection_status_connected)
            connectionStatusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.green))
            findDevicesButton.visibility = View.GONE
            devicesRecyclerView.visibility = View.GONE
            availableDevicesTitle.visibility = View.GONE

            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                connectedDeviceName.text = device.name
                connectedDeviceAddress.text = device.address
            }

        } else {
            bluetoothViewModel.setConnected(false)
            connectedDeviceCard.visibility = View.GONE
            connectionStatusTextView.text = getString(R.string.connection_status_disconnected)
            connectionStatusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey))
            findDevicesButton.visibility = View.VISIBLE
            devicesRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun disconnectDevice() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.disconnect_button))
            .setMessage("¿Está seguro de que desea desconectar el dispositivo '${SelectedDevice.device?.name}'?")
            .setPositiveButton(getString(R.string.disconnect_button)) { _, _ ->
                // Stop Bluetooth Connection Service
                stopBluetoothService()
                
                SelectedDevice.device = null
                updateConnectionState()
                Toast.makeText(requireContext(), getString(R.string.disconnection_success), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Inicia el servicio de conexión Bluetooth en primer plano
     */
    private fun startBluetoothService() {
        val intent = Intent(requireContext(), BluetoothConnectionService::class.java).apply {
            action = BluetoothConnectionService.ACTION_CONNECT
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("ConnectionFragment", "Error starting Bluetooth service", e)
        }
    }
    
    /**
     * Detiene el servicio de conexión Bluetooth
     */
    private fun stopBluetoothService() {
        val intent = Intent(requireContext(), BluetoothConnectionService::class.java).apply {
            action = BluetoothConnectionService.ACTION_DISCONNECT
        }
        
        try {
            requireContext().startService(intent)
        } catch (e: Exception) {
            android.util.Log.e("ConnectionFragment", "Error stopping Bluetooth service", e)
        }
    }

    private fun checkAndRequestPermissions(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )

        val permissionsNeeded = permissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        return if (permissionsNeeded.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsNeeded.toTypedArray())
            false
        } else {
            true
        }
    }

    private fun scanDevices() {
        // Check if Bluetooth adapter is available
        if (bluetoothAdapter == null) {
            Toast.makeText(requireContext(), "Bluetooth no está disponible en este dispositivo", Toast.LENGTH_LONG).show()
            findDevicesButton.isEnabled = false
            findDevicesButton.text = getString(R.string.bluetooth_not_available)
            return
        }
        
        findDevicesButton.isEnabled = false
        findDevicesButton.text = getString(R.string.searching_devices)
        discoveredDevices.clear()
        
        // First, add already paired devices to the list (solo los que tienen nombres válidos)
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.bondedDevices?.let { pairedDevices ->
                val validPairedDevices = pairedDevices.filter { hasValidDeviceName(it) }
                discoveredDevices.addAll(validPairedDevices)
            }
        }
        
        bluetoothDeviceAdapter.notifyDataSetChanged()
        availableDevicesTitle.visibility = View.VISIBLE

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.startDiscovery()
        }

        // Re-enable button after a delay
        findDevicesButton.postDelayed({
            if (bluetoothAdapter != null) {
                findDevicesButton.isEnabled = true
                findDevicesButton.text = getString(R.string.find_devices_button)
            }
        }, 10000) // 10 seconds
    }

    private fun showPairingDialog(device: BluetoothDevice) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            
            // Check if device is already paired
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                // Already paired, just connect
                SelectedDevice.device = device
                updateConnectionState()
                Toast.makeText(requireContext(), "Dispositivo ya emparejado", Toast.LENGTH_SHORT).show()
                
                // Si es un dispositivo PastillApp, verificar registro y conectar
                if (device.name?.startsWith("PastillApp") == true) {
                    handlePastillAppRegistration(device)
                } else {
                    // Start Bluetooth service for other paired devices
                    startBluetoothService()
                }
                return
            }
            
            // Show dialog to initiate pairing
            AlertDialog.Builder(requireContext())
                .setTitle("Emparejar Dispositivo")
                .setMessage("¿Desea emparejar con '${device.name}'?\n\nSi el dispositivo requiere un PIN, el sistema le solicitará ingresarlo.")
                .setPositiveButton("Emparejar") { _, _ ->
                    // Cancel discovery to improve pairing performance
                    bluetoothAdapter?.cancelDiscovery()
                    
                    // Initiate pairing
                    val pairingInitiated = pairingHandler?.pairDevice(device) ?: false
                    if (!pairingInitiated) {
                        Toast.makeText(requireContext(), "No se pudo iniciar el emparejamiento", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "Iniciando emparejamiento...", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    /**
     * Maneja el registro de un dispositivo PastillApp
     */
    private fun handlePastillAppRegistration(device: BluetoothDevice) {
        lifecycleScope.launch {
            val progressDialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle("Registrando dispositivo")
                .setMessage("Conectando y obteniendo UUID...")
                .setCancelable(false)
                .create()
            
            try {
                progressDialog.show()
                
                // Verificación de seguridad: el dispositivo debe estar vinculado (bonded)
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    progressDialog.dismiss()
                    Toast.makeText(
                        requireContext(),
                        "Error de seguridad: El dispositivo debe estar emparejado antes de conectar",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                
                // Obtener UID de enfermera (requerido por las reglas de Firestore)
                val enfermeraUid = FirebaseAuthClient.auth.currentUser?.uid
                if (enfermeraUid == null) {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), "Error: No hay usuario autenticado", Toast.LENGTH_LONG).show()
                    return@launch
                }
                
                // Conectar Bluetooth Serial
                bluetoothSerialService = BluetoothSerialService(device)
                val connected = bluetoothSerialService?.connect() ?: false
                
                if (!connected) {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), "Error al conectar con el dispositivo", Toast.LENGTH_LONG).show()
                    return@launch
                }
                
                progressDialog.setMessage("Obteniendo UUID del dispositivo...")
                
                // Esperar un poco para que el ESP32 envíe el UUID automáticamente
                delay(1500)
                
                // Limpiar buffer de entrada antes de leer
                var uuidResponse: String? = null
                
                // Intentar leer cualquier dato que el ESP32 haya enviado automáticamente
                val autoResponse = bluetoothSerialService?.readResponse(2000)
                
                // Buscar UUID en la respuesta automática
                if (autoResponse != null && autoResponse.contains("UUID:", ignoreCase = true)) {
                    uuidResponse = autoResponse
                } else {
                    // Si no se recibió UUID automáticamente, solicitarlo explícitamente
                    uuidResponse = bluetoothSerialService?.sendCommandAndWaitResponse("GET_UUID", 5000)
                }
                
                // Extraer UUID de la respuesta
                val uuid = when {
                    uuidResponse == null -> {
                        android.util.Log.e("ConnectionFragment", "Respuesta UUID es null")
                        null
                    }
                    uuidResponse.contains("UUID:", ignoreCase = true) -> {
                        // Formato: "UUID:xxxx-xxxx-xxxx..." o puede venir con otros datos
                        val lines = uuidResponse.lines()
                        val uuidLine = lines.find { it.contains("UUID:", ignoreCase = true) }
                        uuidLine?.substringAfter("UUID:", "")?.trim()?.takeIf { it.isNotEmpty() && it.length >= 10 }
                    }
                    uuidResponse.contains("NO_UUID", ignoreCase = true) -> {
                        android.util.Log.e("ConnectionFragment", "ESP32 respondió NO_UUID")
                        null
                    }
                    uuidResponse.trim().length >= 10 -> {
                        // Respuesta directa sin prefijo UUID:
                        uuidResponse.trim().takeIf { it.matches(Regex("[0-9a-fA-F-]{10,}")) }
                    }
                    else -> {
                        android.util.Log.e("ConnectionFragment", "Formato UUID inválido: $uuidResponse")
                        null
                    }
                }
                
                if (uuid == null || uuid.length < 10) {
                    progressDialog.dismiss()
                    Toast.makeText(
                        requireContext(),
                        "Error: No se pudo obtener el UUID del dispositivo\nRespuesta: ${uuidResponse?.take(50)}",
                        Toast.LENGTH_LONG
                    ).show()
                    android.util.Log.e("ConnectionFragment", "UUID inválido o nulo. Respuesta completa: $uuidResponse")
                    return@launch
                }
                
                android.util.Log.d("ConnectionFragment", "UUID obtenido exitosamente: $uuid")
                
                // Verificar si el dispositivo ya está registrado en Firestore
                progressDialog.setMessage("Verificando registro en Firestore...")
                val deviceExists = DeviceFirestoreService.deviceExists(uuid)
                
                if (deviceExists) {
                    progressDialog.dismiss()
                    Toast.makeText(
                        requireContext(),
                        "Dispositivo ya registrado\nUUID: ${uuid.take(8)}...",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Start Bluetooth Connection Service
                    startBluetoothService()
                } else {
                    // Registrar en Firestore
                    progressDialog.setMessage("Guardando información en Firestore...")
                    
                    val macAddress = device.address
                    android.util.Log.d("ConnectionFragment", "Intentando registrar dispositivo: UUID=$uuid, MAC=$macAddress, UID=$enfermeraUid")
                    
                    val registered = DeviceFirestoreService.registerDevice(uuid, macAddress, enfermeraUid)
                    
                    progressDialog.dismiss()
                    
                    if (registered) {
                        Toast.makeText(
                            requireContext(),
                            "Dispositivo registrado exitosamente\nUUID: ${uuid.take(8)}...",
                            Toast.LENGTH_LONG
                        ).show()
                        android.util.Log.d("ConnectionFragment", "Dispositivo registrado exitosamente en Firestore")
                        // Start Bluetooth Connection Service
                        startBluetoothService()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "Error al registrar dispositivo en Firestore\nVerifica los logs para más detalles",
                            Toast.LENGTH_LONG
                        ).show()
                        android.util.Log.e("ConnectionFragment", "Error al registrar dispositivo en Firestore")
                    }
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(
                    requireContext(),
                    "Error durante el registro: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                bluetoothSerialService?.close()
                bluetoothSerialService = null
            }
        }
    }
}