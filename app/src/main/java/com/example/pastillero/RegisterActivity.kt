package com.pokkzdev.pastillapp

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        val emailEditText = findViewById<TextInputEditText>(R.id.register_email)
        val passwordEditText = findViewById<TextInputEditText>(R.id.register_password)
        val confirmPasswordEditText = findViewById<TextInputEditText>(R.id.register_confirm_password)
        val emailLayout = findViewById<TextInputLayout>(R.id.register_email_layout)
        val passwordLayout = findViewById<TextInputLayout>(R.id.register_password_layout)
        val confirmPasswordLayout = findViewById<TextInputLayout>(R.id.register_confirm_password_layout)
        val registerButton = findViewById<Button>(R.id.register_button)
        val loginLink = findViewById<TextView>(R.id.login_link)

        // Navigate back to login
        loginLink.setOnClickListener {
            finish()
        }

        registerButton.setOnClickListener { view ->
            // Clear previous errors
            emailLayout.error = null
            passwordLayout.error = null
            confirmPasswordLayout.error = null

            val email = emailEditText.text.toString().trim()
            val password = passwordEditText.text.toString().trim()
            val confirmPassword = confirmPasswordEditText.text.toString().trim()

            // Validate inputs
            var isValid = true

            // Email validation
            if (email.isEmpty()) {
                emailLayout.error = getString(R.string.error_empty_email)
                isValid = false
            } else if (email.length < 8) {
                emailLayout.error = getString(R.string.error_email_length)
                isValid = false
            } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                emailLayout.error = getString(R.string.error_invalid_email)
                isValid = false
            }

            // Password validation
            if (password.isEmpty()) {
                passwordLayout.error = getString(R.string.error_empty_password)
                isValid = false
            } else if (password.length < 8) {
                passwordLayout.error = getString(R.string.error_password_length)
                isValid = false
            } else if (!isPasswordComplex(password)) {
                passwordLayout.error = getString(R.string.error_password_complexity)
                isValid = false
            }

            // Confirm password validation
            if (confirmPassword.isEmpty()) {
                confirmPasswordLayout.error = getString(R.string.error_empty_confirm_password)
                isValid = false
            } else if (password != confirmPassword) {
                confirmPasswordLayout.error = getString(R.string.error_passwords_not_match)
                isValid = false
            }

            if (!isValid) {
                Snackbar.make(view, getString(R.string.error_fix_fields), Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Register user with Firebase
            lifecycleScope.launch {
                try {
                    val auth = FirebaseAuthClient.auth
                    val result = auth.createUserWithEmailAndPassword(email, password).await()
                    val user = result.user
                    
                    // Start session after successful registration
                    SessionManager.init(this@RegisterActivity)
                    SessionManager.updateLastActivity()
                    
                    Snackbar.make(view, getString(R.string.register_success), Snackbar.LENGTH_LONG).show()
                    
                    // Navigate to MainActivity
                    val intent = Intent(this@RegisterActivity, MainActivity::class.java)
                    intent.putExtra("USER_EMAIL", user?.email ?: email)
                    startActivity(intent)
                    finish()
                    
                } catch (e: Exception) {
                    val spanishError = getSpanishErrorMessage(this@RegisterActivity, e)
                    Snackbar.make(view, spanishError, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun isPasswordComplex(password: String): Boolean {
        val hasUpperCase = password.any { it.isUpperCase() }
        val hasLowerCase = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSpecialChar = password.any { !it.isLetterOrDigit() }
        
        return hasUpperCase && hasLowerCase && hasDigit && hasSpecialChar
    }
}
