package com.example.bluematrix

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SignInActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)

        val edtEmailLogin = findViewById<EditText>(R.id.edtEmailLogin)
        val edtPasswordLogin = findViewById<EditText>(R.id.edtPasswordLogin)
        val btnSignIn = findViewById<Button>(R.id.btnSignIn)
        val txtForgotPassword = findViewById<TextView>(R.id.txtForgotPassword)

        btnSignIn.setOnClickListener {
            val email = edtEmailLogin.text.toString().trim()
            val password = edtPasswordLogin.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Toast.makeText(this, "Signed in!", Toast.LENGTH_SHORT).show()

            val intent = Intent(this, VideoDownloaderActivity::class.java)
            startActivity(intent)
            finish()
        }

        txtForgotPassword.setOnClickListener {
            Toast.makeText(this, "Forgot password clicked", Toast.LENGTH_SHORT).show()
        }
    }
}
