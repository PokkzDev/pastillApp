package com.example.pastillero

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

class HomeFragment : Fragment() {

    private var sensorJob: Job? = null
    private var lightValueTextView: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        
        // Initialize TextView
        lightValueTextView = view.findViewById(R.id.light_value_textview)
        
        // Set up button listeners with simple toasts
        view.findViewById<Button>(R.id.open_compartment_button)?.setOnClickListener {
            Toast.makeText(context, "Compartimento abierto", Toast.LENGTH_SHORT).show()
        }
        
        view.findViewById<Button>(R.id.silence_alarm_button)?.setOnClickListener {
            Toast.makeText(context, "Alarma silenciada", Toast.LENGTH_SHORT).show()
        }
        
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Start mock light sensor after view is fully created
        try {
            startMockLightSensor()
        } catch (e: Exception) {
            // Ignore any errors during startup
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop sensor when fragment is paused
        try {
            sensorJob?.cancel()
            sensorJob = null
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onResume() {
        super.onResume()
        // Restart sensor when fragment resumes
        try {
            if (view != null && lightValueTextView != null) {
                startMockLightSensor()
            }
        } catch (e: Exception) {
            // Ignore any errors during resume
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            sensorJob?.cancel()
            sensorJob = null
            lightValueTextView = null
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun startMockLightSensor() {
        // Cancel any existing job first
        try {
            sensorJob?.cancel()
            sensorJob = null
        } catch (e: Exception) {
            // Ignore
        }
        
        // Check if we can start the sensor
        if (!isAdded || view == null || lightValueTextView == null) {
            return
        }
        
        try {
            sensorJob = viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    try {
                        // Double check view is still valid
                        if (lightValueTextView == null) {
                            break
                        }
                        
                        // Generate random LDR value (typical range 0-1023 for Arduino analog read)
                        val ldrValue = Random.nextInt(0, 1024)
                        
                        // Update the TextView
                        lightValueTextView?.text = "$ldrValue lux"
                        
                        // Update every second
                        delay(1000)
                    } catch (e: Exception) {
                        // If any error occurs, stop the loop
                        break
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore startup errors
        }
    }
}