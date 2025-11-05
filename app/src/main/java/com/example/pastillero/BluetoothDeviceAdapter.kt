package com.example.pastillero

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class BluetoothDeviceAdapter(
    private val devices: List<BluetoothDevice>,
    private val onDeviceClicked: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<BluetoothDeviceAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.device_list_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.bind(device)
        holder.itemView.setOnClickListener { onDeviceClicked(device) }
    }

    override fun getItemCount() = devices.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.device_name)
        private val macTextView: TextView = itemView.findViewById(R.id.device_mac)

        fun bind(device: BluetoothDevice) {
            if (ContextCompat.checkSelfPermission(itemView.context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                val deviceName = device.name ?: "Dispositivo Desconocido"
                val pairingStatus = when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> " (Emparejado)"
                    BluetoothDevice.BOND_BONDING -> " (Emparejando...)"
                    else -> ""
                }
                nameTextView.text = "$deviceName$pairingStatus"
                macTextView.text = device.address
            }
        }
    }
}
