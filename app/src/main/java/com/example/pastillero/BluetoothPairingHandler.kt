package com.pokkzdev.pastillapp

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.widget.Toast

class BluetoothPairingHandler(
    private val context: Context,
    private val onPairingSuccess: (BluetoothDevice) -> Unit,
    private val onPairingFailed: (BluetoothDevice?) -> Unit
) {
    
    companion object {
        private const val TAG = "BluetoothPairingHandler"
    }

    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val pairingVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
                    
                    Log.d(TAG, "Pairing request received from: ${device?.name}, variant: $pairingVariant")
                    
                    // Handle different pairing types
                    when (pairingVariant) {
                        BluetoothDevice.PAIRING_VARIANT_PIN -> {
                            Log.d(TAG, "PIN pairing required")
                            // The system will show PIN dialog automatically
                            // You can also set a PIN programmatically if you know it
                            // device?.setPin("1234".toByteArray())
                            // abortBroadcast()
                        }
                        BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION -> {
                            Log.d(TAG, "Passkey confirmation required")
                            // You can auto-confirm if needed
                            // device?.setPairingConfirmation(true)
                        }
                        else -> {
                            Log.d(TAG, "Other pairing variant: $pairingVariant")
                            // Handle other pairing types (including CONSENT on supported devices)
                        }
                    }
                }
                
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    
                    Log.d(TAG, "Bond state changed from $previousBondState to $bondState for device: ${device?.name}")
                    
                    when (bondState) {
                        BluetoothDevice.BOND_BONDED -> {
                            Log.d(TAG, "Device paired successfully")
                            device?.let { onPairingSuccess(it) }
                        }
                        BluetoothDevice.BOND_BONDING -> {
                            Log.d(TAG, "Pairing in progress...")
                        }
                        BluetoothDevice.BOND_NONE -> {
                            if (previousBondState == BluetoothDevice.BOND_BONDING) {
                                Log.d(TAG, "Pairing failed")
                                onPairingFailed(device)
                            }
                        }
                    }
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // Set high priority to receive pairing requests before system
        filter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        context.registerReceiver(pairingReceiver, filter)
        Log.d(TAG, "Pairing receiver registered")
    }

    fun unregister() {
        try {
            context.unregisterReceiver(pairingReceiver)
            Log.d(TAG, "Pairing receiver unregistered")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Receiver was not registered", e)
        }
    }

    /**
     * Initiate pairing with a device
     */
    fun pairDevice(device: BluetoothDevice): Boolean {
        return try {
            when (device.bondState) {
                BluetoothDevice.BOND_NONE -> {
                    Log.d(TAG, "Initiating pairing with ${device.name}")
                    device.createBond()
                }
                BluetoothDevice.BOND_BONDED -> {
                    Log.d(TAG, "Device already paired")
                    onPairingSuccess(device)
                    true
                }
                BluetoothDevice.BOND_BONDING -> {
                    Log.d(TAG, "Pairing already in progress")
                    true
                }
                else -> false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception while pairing", e)
            false
        }
    }

    /**
     * Set a specific PIN for pairing (if you know the device PIN)
     */
    fun setPinForDevice(device: BluetoothDevice, pin: String): Boolean {
        return try {
            val pinBytes = pin.toByteArray()
            device.setPin(pinBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting PIN", e)
            false
        }
    }

    /**
     * Unpair a device
     */
    fun unpairDevice(device: BluetoothDevice): Boolean {
        return try {
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                val method = device.javaClass.getMethod("removeBond")
                method.invoke(device) as Boolean
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unpairing device", e)
            false
        }
    }
}
