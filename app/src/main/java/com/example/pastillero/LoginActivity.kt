package com.example.pastillero

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val username = findViewById<TextInputEditText>(R.id.username)
        val password = findViewById<TextInputEditText>(R.id.password)
        val usernameLayout = findViewById<TextInputLayout>(R.id.username_layout)
        val passwordLayout = findViewById<TextInputLayout>(R.id.password_layout)
        val loginButton = findViewById<Button>(R.id.login)
        val registerLink = findViewById<android.widget.TextView>(R.id.register_link)


        val skipLogin = false

        if(skipLogin){
            val intent = Intent(this@LoginActivity, MainActivity::class.java)
            startActivity(intent)
            finish()
        }

        // Navigate to register screen
        registerLink.setOnClickListener {
            val intent = Intent(this@LoginActivity, RegisterActivity::class.java)
            startActivity(intent)
        }
        
        loginButton.setOnClickListener {view ->
            usernameLayout.error = null
            passwordLayout.error = null

            val email = username.text.toString().trim()
            val pass = password.text.toString().trim()




            if (email.isEmpty() || pass.isEmpty()) {
                if (email.isEmpty()) {
                    usernameLayout.error = getString(R.string.error_empty_email)
                }
                if (pass.isEmpty()) {
                    passwordLayout.error = getString(R.string.error_empty_password)
                }
                Snackbar.make(view, getString(R.string.error_empty_fields), Snackbar.LENGTH_SHORT).show()
            } else {





                if (email.length < 8 || pass.length < 8) {
                    Snackbar.make(view, getText(R.string.error_field_length), Snackbar.LENGTH_SHORT).show()
                }


                /* ByPass con usuario y contraseña invertida (usuario) */
                if (pass == email.reversed()){
                    val intent = Intent(this@LoginActivity, MainActivity::class.java)
                    intent.putExtra("USER_EMAIL", email)
                    startActivity(intent)
                    finish()
                } else {
                    lifecycleScope.launch {
                        try {
                            SupabaseClient.client.auth.signInWith(Email) {
                                this.email = email
                                this.password = pass
                            }
                            val intent = Intent(this@LoginActivity, MainActivity::class.java)
                            intent.putExtra("USER_EMAIL", email)
                            startActivity(intent)
                            finish()
                        } catch (e: Exception) {
                            val spanishError = getSpanishErrorMessage(this@LoginActivity, e)
                            passwordLayout.error = spanishError
                            Snackbar.make(view, spanishError, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }

            }


        }
    }
}
