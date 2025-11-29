package com.pokkzdev.pastillapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize session manager
        SessionManager.init(this)
        
        // Check if user has a valid session (Firebase user + not expired)
        if (SessionManager.hasValidSession()) {
            // Session is valid, go directly to MainActivity
            SessionManager.updateLastActivity()
            val user = FirebaseAuthClient.auth.currentUser
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("USER_EMAIL", user?.email ?: "")
            startActivity(intent)
            finish()
            return
        } else {
            // Session expired or no user, ensure Firebase is signed out
            val currentUser = FirebaseAuthClient.auth.currentUser
            if (currentUser != null) {
                // Session expired, sign out from Firebase
                FirebaseAuthClient.auth.signOut()
                SessionManager.clearSession()
            }
        }
        
        setContentView(R.layout.activity_login)

        val username = findViewById<TextInputEditText>(R.id.username)
        val password = findViewById<TextInputEditText>(R.id.password)
        val usernameLayout = findViewById<TextInputLayout>(R.id.username_layout)
        val passwordLayout = findViewById<TextInputLayout>(R.id.password_layout)
        val loginButton = findViewById<Button>(R.id.login)
        val registerLink = findViewById<android.widget.TextView>(R.id.register_link)

        // Navigate to register screen
        registerLink.setOnClickListener {
            val intent = Intent(this@LoginActivity, RegisterActivity::class.java)
            startActivity(intent)
        }
        
        loginButton.setOnClickListener { view ->
            usernameLayout.error = null
            passwordLayout.error = null

            val email = username.text.toString().trim()
            val pass = password.text.toString().trim()

            // Validate inputs
            if (email.isEmpty() || pass.isEmpty()) {
                if (email.isEmpty()) {
                    usernameLayout.error = getString(R.string.error_empty_email)
                }
                if (pass.isEmpty()) {
                    passwordLayout.error = getString(R.string.error_empty_password)
                }
                Snackbar.make(view, getString(R.string.error_empty_fields), Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Validate minimum length
            if (email.length < 8 || pass.length < 8) {
                Snackbar.make(view, getText(R.string.error_field_length), Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Authenticate with Firebase only
            lifecycleScope.launch {
                try {
                    val auth = FirebaseAuthClient.auth
                    val result = auth.signInWithEmailAndPassword(email, pass).await()
                    val user = result.user
                    
                    if (user != null) {
                        // Start session and update activity timestamp
                        SessionManager.updateLastActivity()
                        
                        val intent = Intent(this@LoginActivity, MainActivity::class.java)
                        intent.putExtra("USER_EMAIL", user.email ?: email)
                        startActivity(intent)
                        finish()
                    } else {
                        passwordLayout.error = getString(R.string.error_auth_failed)
                        Snackbar.make(view, getString(R.string.error_auth_failed), Snackbar.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    val spanishError = getSpanishErrorMessage(this@LoginActivity, e)
                    passwordLayout.error = spanishError
                    Snackbar.make(view, spanishError, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }
}
