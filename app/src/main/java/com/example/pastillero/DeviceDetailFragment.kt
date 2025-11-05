package com.example.pastillero

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

class DeviceDetailFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_device_detail, container, false)

        val deviceNameTextView: TextView = view.findViewById(R.id.device_name_detail)
        val deviceAddressTextView: TextView = view.findViewById(R.id.device_address_detail)

        deviceNameTextView.text = SelectedDevice.device?.name
        deviceAddressTextView.text = SelectedDevice.device?.address

        return view
    }
}