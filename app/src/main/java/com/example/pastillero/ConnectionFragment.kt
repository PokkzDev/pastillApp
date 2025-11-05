package com.example.pastillero

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

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

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.action
            if (BluetoothDevice.ACTION_FOUND == action) {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                device?.let {
                    if (it !in discoveredDevices && isAdded) {
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
            },
            onPairingFailed = { device ->
                Toast.makeText(requireContext(), "Falló el emparejamiento con ${device?.name ?: "dispositivo"}", Toast.LENGTH_SHORT).show()
            }
        )
        pairingHandler?.register()

        findDevicesButton.setOnClickListener {
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
                SelectedDevice.device = null
                updateConnectionState()
                Toast.makeText(requireContext(), getString(R.string.disconnection_success), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
        findDevicesButton.isEnabled = false
        findDevicesButton.text = getString(R.string.searching_devices)
        discoveredDevices.clear()
        
        // First, add already paired devices to the list
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.bondedDevices?.let { pairedDevices ->
                discoveredDevices.addAll(pairedDevices)
            }
        }
        
        bluetoothDeviceAdapter.notifyDataSetChanged()
        availableDevicesTitle.visibility = View.VISIBLE

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.startDiscovery()
        }

        // Re-enable button after a delay
        findDevicesButton.postDelayed({
            findDevicesButton.isEnabled = true
            findDevicesButton.text = getString(R.string.find_devices_button)
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
}