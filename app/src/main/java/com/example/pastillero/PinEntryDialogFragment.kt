package com.example.pastillero

import android.app.Dialog
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class PinEntryDialogFragment(
    private val device: BluetoothDevice,
    private val onPinEntered: (String) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireActivity())
        
        val input = EditText(requireContext())
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "Ingrese el PIN (ej: 1234)"
        
        builder.setTitle("Ingrese el PIN")
            .setMessage("El dispositivo '${device.name}' requiere un PIN para emparejar.")
            .setView(input)
            .setPositiveButton("Confirmar") { _, _ ->
                val pin = input.text.toString()
                if (pin.isNotEmpty()) {
                    onPinEntered(pin)
                }
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                dialog.cancel()
            }
        
        return builder.create()
    }

    companion object {
        const val TAG = "PinEntryDialog"
    }
}
