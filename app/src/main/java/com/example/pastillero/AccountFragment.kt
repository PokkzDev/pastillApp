package com.example.pastillero

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.github.jan.supabase.gotrue.auth
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
        val logoutButton = view.findViewById<Button>(R.id.logout_button)

        // Get the current user's email
        val email = arguments?.getString("USER_EMAIL")
        emailTextView.text = email ?: "No se detecto una sesión"

        logoutButton.setOnClickListener {
            lifecycleScope.launch {
                SupabaseClient.client.auth.signOut()
                /* Destruye Conexion Bluetooth Mock*/
                SelectedDevice.device = null
                val intent = Intent(requireContext(), LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                requireActivity().finish()
            }
        }
    }
}
