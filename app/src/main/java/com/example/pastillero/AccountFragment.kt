package com.pokkzdev.pastillapp

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

class AccountFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_account, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val emailTextView = view.findViewById<TextView>(R.id.email_textview)
        val logoutButton = view.findViewById<MaterialButton>(R.id.logout_button)

        // Get the current user's email from Firebase Auth
        val auth = FirebaseAuthClient.auth
        val currentUser = auth.currentUser
        val email = currentUser?.email ?: arguments?.getString("USER_EMAIL")
        emailTextView.text = email ?: (getString(R.string.account_email_label) + ": No se detectó una sesión")

        logoutButton.setOnClickListener {
            logoutButton.isEnabled = false
            logoutButton.text = getString(R.string.connection_status_disconnecting)
            
            lifecycleScope.launch {
                try {
                    // Clear session first
                    SessionManager.clearSession()
                    
                    val auth = FirebaseAuthClient.auth
                    auth.signOut()
                    
                    // Destroy Bluetooth connection
                    SelectedDevice.device = null
                    
                    // Navigate to login
                    val intent = Intent(requireContext(), LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    requireActivity().finish()
                } catch (e: Exception) {
                    // Handle errors
                    logoutButton.isEnabled = true
                    logoutButton.text = getString(R.string.account_button)
                    Toast.makeText(
                        requireContext(),
                        "Error al cerrar sesión. Por favor, intenta de nuevo.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
