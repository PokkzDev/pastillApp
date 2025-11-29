package com.pokkzdev.pastillapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

class HomeFragment : Fragment(), BluetoothConnectionListener {

    // Views
    private var notConnectedCard: MaterialCardView? = null
    private var deviceStatusCard: MaterialCardView? = null
    private var compartmentCard: MaterialCardView? = null
    private var lightSensorCard: MaterialCardView? = null
    private var alarmCard: MaterialCardView? = null
    
    private var goToConnectionButton: MaterialButton? = null
    private var openCompartmentButton: MaterialButton? = null
    private var silenceAlarmButton: MaterialButton? = null
    
    private var deviceNameTextView: TextView? = null
    private var deviceStatusTextView: TextView? = null
    private var lightValueTextView: TextView? = null
    private var alarmStatusTextView: TextView? = null
    private var connectionIndicator: View? = null
    
    // Bluetooth service
    private var bluetoothService: BluetoothConnectionService? = null
    private var isBound = false
    
    // Pill dispensing service
    private var pillDispensingService: PillDispensingService? = null
    private var isPillServiceBound = false
    
    private val bluetoothViewModel: BluetoothViewModel by activityViewModels()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothConnectionService.LocalBinder
            bluetoothService = binder.getService()
            bluetoothService?.addConnectionListener(this@HomeFragment)
            isBound = true
            
            // Update UI with current state
            updateConnectionUI(bluetoothService?.isConnected() == true)
            if (bluetoothService?.isConnected() == true) {
                val currentStatus = bluetoothService?.getCurrentDeviceStatus() ?: "DESCONOCIDO"
                updateDeviceStatus(currentStatus)
                updateLdrValue(bluetoothService?.getCurrentLdrValue() ?: 0)
                // Request current status from ESP32 to ensure we have the latest state
                bluetoothService?.sendCommand("STATUS")
                // Start pill dispensing service when Bluetooth is connected
                startPillDispensingService()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothService?.removeConnectionListener(this@HomeFragment)
            bluetoothService = null
            isBound = false
            // Stop pill dispensing service when Bluetooth disconnects
            stopPillDispensingService()
        }
    }
    
    private val pillDispensingServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PillDispensingService.LocalBinder
            pillDispensingService = binder.getService()
            pillDispensingService?.setBluetoothService(bluetoothService)
            isPillServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            pillDispensingService?.setBluetoothService(null)
            pillDispensingService = null
            isPillServiceBound = false
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startBluetoothService()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        
        initializeViews(view)
        setupClickListeners()
        
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Observe connection state from ViewModel
        bluetoothViewModel.isConnected.observe(viewLifecycleOwner) { isConnected ->
            updateConnectionUI(isConnected)
        }
        
        // Check if device is selected and start service if needed
        checkAndConnectDevice()
    }

    override fun onResume() {
        super.onResume()
        // Bind to service if available
        bindToService()
        // Update UI based on current state
        checkAndConnectDevice()
    }

    override fun onPause() {
        super.onPause()
        unbindFromService()
        unbindFromPillDispensingService()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unbindFromService()
        unbindFromPillDispensingService()
        stopPillDispensingService()
        clearViews()
    }

    private fun initializeViews(view: View) {
        notConnectedCard = view.findViewById(R.id.not_connected_card)
        deviceStatusCard = view.findViewById(R.id.device_status_card)
        compartmentCard = view.findViewById(R.id.compartment_card)
        lightSensorCard = view.findViewById(R.id.light_sensor_card)
        alarmCard = view.findViewById(R.id.alarm_card)
        
        goToConnectionButton = view.findViewById(R.id.go_to_connection_button)
        openCompartmentButton = view.findViewById(R.id.open_compartment_button)
        silenceAlarmButton = view.findViewById(R.id.silence_alarm_button)
        
        deviceNameTextView = view.findViewById(R.id.device_name_textview)
        deviceStatusTextView = view.findViewById(R.id.device_status_textview)
        lightValueTextView = view.findViewById(R.id.light_value_textview)
        alarmStatusTextView = view.findViewById(R.id.alarm_status_textview)
        connectionIndicator = view.findViewById(R.id.connection_indicator)
    }

    private fun clearViews() {
        notConnectedCard = null
        deviceStatusCard = null
        compartmentCard = null
        lightSensorCard = null
        alarmCard = null
        goToConnectionButton = null
        openCompartmentButton = null
        silenceAlarmButton = null
        deviceNameTextView = null
        deviceStatusTextView = null
        lightValueTextView = null
        alarmStatusTextView = null
        connectionIndicator = null
    }

    private fun setupClickListeners() {
        goToConnectionButton?.setOnClickListener {
            navigateToConnection()
        }
        
        openCompartmentButton?.setOnClickListener {
            openCompartment()
        }
        
        silenceAlarmButton?.setOnClickListener {
            silenceAlarm()
        }
    }

    private fun checkAndConnectDevice() {
        val device = SelectedDevice.device
        if (device != null) {
            // Device is selected, check if service is running
            if (bluetoothService?.isConnected() != true) {
                // Try to start/connect service
                requestNotificationPermissionAndStart()
            }
            updateConnectionUI(bluetoothService?.isConnected() == true)
            
            // Update device name
            try {
                if (ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    deviceNameTextView?.text = device.name ?: "PastillApp"
                }
            } catch (e: SecurityException) {
                deviceNameTextView?.text = "PastillApp"
            }
        } else {
            // No device selected
            updateConnectionUI(false)
        }
    }

    private fun requestNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startBluetoothService()
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            startBluetoothService()
        }
    }

    private fun startBluetoothService() {
        val device = SelectedDevice.device ?: return
        
        val intent = Intent(requireContext(), BluetoothConnectionService::class.java).apply {
            action = BluetoothConnectionService.ACTION_CONNECT
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
            bindToService()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error starting Bluetooth service", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindToService() {
        if (!isBound) {
            val intent = Intent(requireContext(), BluetoothConnectionService::class.java)
            try {
                requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                // Service not available yet
            }
        }
    }

    private fun unbindFromService() {
        if (isBound) {
            bluetoothService?.removeConnectionListener(this)
            try {
                requireContext().unbindService(serviceConnection)
            } catch (e: Exception) {
                // Already unbound
            }
            isBound = false
        }
    }

    private fun updateConnectionUI(isConnected: Boolean) {
        if (!isAdded || view == null) return
        
        if (isConnected && SelectedDevice.device != null) {
            // Connected - show device controls
            notConnectedCard?.visibility = View.GONE
            deviceStatusCard?.visibility = View.VISIBLE
            compartmentCard?.visibility = View.VISIBLE
            lightSensorCard?.visibility = View.VISIBLE
            alarmCard?.visibility = View.VISIBLE
            
            connectionIndicator?.setBackgroundResource(R.drawable.circle_green)
            openCompartmentButton?.isEnabled = true
            silenceAlarmButton?.isEnabled = true
        } else {
            // Not connected - show connection prompt
            notConnectedCard?.visibility = View.VISIBLE
            deviceStatusCard?.visibility = View.GONE
            compartmentCard?.visibility = View.GONE
            lightSensorCard?.visibility = View.GONE
            alarmCard?.visibility = View.GONE
            
            lightValueTextView?.text = "-- ADC"
            deviceStatusTextView?.text = "--"
        }
    }

    private fun updateDeviceStatus(status: String) {
        if (!isAdded || view == null) return
        
        // Display the actual status from ESP32
        // Status values: "CERRADO", "ABIERTO", "CUENTA_REGRESIVA", or "DESCONOCIDO"
        val displayStatus = when (status) {
            "CERRADO" -> "Cerrado"
            "ABIERTO" -> "Abierto"
            "CUENTA_REGRESIVA" -> "Cerrando..."
            "DESCONOCIDO" -> "--"
            else -> status
        }
        
        deviceStatusTextView?.text = displayStatus
        
        // Update alarm status based on device state
        when (status) {
            "ABIERTO" -> {
                alarmStatusTextView?.text = getString(R.string.alarm_active)
                alarmStatusTextView?.setTextColor(ContextCompat.getColor(requireContext(), R.color.red))
            }
            "CUENTA_REGRESIVA" -> {
                alarmStatusTextView?.text = getString(R.string.alarm_active)
                alarmStatusTextView?.setTextColor(ContextCompat.getColor(requireContext(), R.color.red))
            }
            else -> {
                alarmStatusTextView?.text = getString(R.string.alarm_inactive)
                alarmStatusTextView?.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey))
            }
        }
    }

    private fun updateLdrValue(value: Int) {
        if (!isAdded || view == null) return
        lightValueTextView?.text = "$value ADC"
    }

    private fun navigateToConnection() {
        // Navigate to ConnectionFragment via MainActivity's bottom navigation
        val mainActivity = activity as? MainActivity
        mainActivity?.let {
            val navView = it.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.nav_view)
            navView?.selectedItemId = R.id.navigation_connection
        }
    }

    private fun openCompartment() {
        if (bluetoothService?.isConnected() != true) {
            Toast.makeText(requireContext(), getString(R.string.device_not_connected), Toast.LENGTH_SHORT).show()
            return
        }
        
        openCompartmentButton?.isEnabled = false
        
        lifecycleScope.launch {
            try {
                bluetoothService?.sendCommand("PASTILLA")
                Toast.makeText(requireContext(), getString(R.string.compartment_opened), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.error_opening_compartment), Toast.LENGTH_SHORT).show()
            } finally {
                openCompartmentButton?.isEnabled = true
            }
        }
    }

    private fun silenceAlarm() {
        if (bluetoothService?.isConnected() != true) {
            Toast.makeText(requireContext(), getString(R.string.device_not_connected), Toast.LENGTH_SHORT).show()
            return
        }
        
        silenceAlarmButton?.isEnabled = false
        
        lifecycleScope.launch {
            try {
                bluetoothService?.sendCommand("SILENCIAR")
                Toast.makeText(requireContext(), getString(R.string.alarm_silenced), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.error_silencing_alarm), Toast.LENGTH_SHORT).show()
            } finally {
                silenceAlarmButton?.isEnabled = true
            }
        }
    }

    private fun startPillDispensingService() {
        // Check if user is logged in
        val auth = FirebaseAuthClient.auth
        if (auth.currentUser == null) {
            return
        }
        
        val intent = Intent(requireContext(), PillDispensingService::class.java).apply {
            action = PillDispensingService.ACTION_START
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
            bindToPillDispensingService()
        } catch (e: Exception) {
            android.util.Log.e("HomeFragment", "Error starting PillDispensingService", e)
        }
    }
    
    private fun stopPillDispensingService() {
        val intent = Intent(requireContext(), PillDispensingService::class.java).apply {
            action = PillDispensingService.ACTION_STOP
        }
        
        try {
            requireContext().startService(intent)
        } catch (e: Exception) {
            android.util.Log.e("HomeFragment", "Error stopping PillDispensingService", e)
        }
    }
    
    private fun bindToPillDispensingService() {
        if (!isPillServiceBound) {
            val intent = Intent(requireContext(), PillDispensingService::class.java)
            try {
                requireContext().bindService(intent, pillDispensingServiceConnection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                // Service not available yet
            }
        }
    }
    
    private fun unbindFromPillDispensingService() {
        if (isPillServiceBound) {
            pillDispensingService?.setBluetoothService(null)
            try {
                requireContext().unbindService(pillDispensingServiceConnection)
            } catch (e: Exception) {
                // Already unbound
            }
            isPillServiceBound = false
        }
    }

    // BluetoothConnectionListener implementation
    override fun onConnectionStateChanged(isConnected: Boolean) {
        activity?.runOnUiThread {
            updateConnectionUI(isConnected)
            bluetoothViewModel.setConnected(isConnected)
            
            // Start/stop pill dispensing service based on connection state
            if (isConnected) {
                // Request current status from ESP32 to get the latest state
                bluetoothService?.sendCommand("STATUS")
                // Update UI with current status
                val currentStatus = bluetoothService?.getCurrentDeviceStatus() ?: "DESCONOCIDO"
                updateDeviceStatus(currentStatus)
                startPillDispensingService()
                // Update Bluetooth service reference in pill dispensing service
                pillDispensingService?.setBluetoothService(bluetoothService)
            } else {
                stopPillDispensingService()
                // Clear status when disconnected
                updateDeviceStatus("--")
            }
        }
    }

    override fun onDeviceStatusReceived(status: String) {
        activity?.runOnUiThread {
            updateDeviceStatus(status)
        }
    }

    override fun onLdrValueReceived(value: Int) {
        activity?.runOnUiThread {
            updateLdrValue(value)
        }
    }

    override fun onEventReceived(event: String) {
        activity?.runOnUiThread {
            when (event) {
                "OPENED" -> {
                    Toast.makeText(requireContext(), getString(R.string.compartment_opened), Toast.LENGTH_SHORT).show()
                }
                "CLOSED" -> {
                    Toast.makeText(requireContext(), "Compartimento cerrado", Toast.LENGTH_SHORT).show()
                }
                "PILL_TAKEN" -> {
                    Toast.makeText(requireContext(), "Pastilla retirada", Toast.LENGTH_SHORT).show()
                }
                "ALARM_SILENCED" -> {
                    Toast.makeText(requireContext(), getString(R.string.alarm_silenced), Toast.LENGTH_SHORT).show()
                    alarmStatusTextView?.text = getString(R.string.alarm_inactive)
                    alarmStatusTextView?.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey))
                }
            }
        }
    }

    override fun onDataReceived(type: String, data: String) {
        // Handle other data if needed
    }
}
