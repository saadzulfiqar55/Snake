package com.saad.snake

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// ---------------------------------------------------------------------------
// AuthScreen
//
// Shown on first launch (or after sign-out). Two tabs: Log In / Sign Up.
// A "Play as Guest" button skips auth entirely.
// ---------------------------------------------------------------------------
@Composable
fun AuthScreen(onAuthDone: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }   // 0 = Login, 1 = Sign Up

    Box(Modifier.fillMaxSize()) {
        HomeBackground()
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "SNAKE",
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 4.sp
            )
            Spacer(Modifier.height(20.dp))

            // Constrain the form to a readable width on wide landscape screens
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxWidth(0.55f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TabRow(
                        selectedTabIndex = tab,
                        containerColor = Color.White.copy(alpha = 0.08f),
                        contentColor = Color.White
                    ) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }) {
                            Text("Log In", Modifier.padding(vertical = 12.dp), fontWeight = FontWeight.Bold)
                        }
                        Tab(selected = tab == 1, onClick = { tab = 1 }) {
                            Text("Sign Up", Modifier.padding(vertical = 12.dp), fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    if (tab == 0) {
                        LoginForm(onSuccess = onAuthDone)
                    } else {
                        SignUpForm(onSuccess = onAuthDone)
                    }

                    Spacer(Modifier.height(20.dp))

                    Text("— or —", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)

                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = onAuthDone,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text("Play as Guest", color = Color.White, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Log-in form
// ---------------------------------------------------------------------------
@Composable
private fun LoginForm(onSuccess: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    AuthTextField(value = email, label = "Email", onValueChange = { email = it; error = "" },
        keyboardType = KeyboardType.Email)
    Spacer(Modifier.height(10.dp))
    AuthTextField(value = password, label = "Password", onValueChange = { password = it; error = "" },
        password = true)

    if (error.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
    }

    Spacer(Modifier.height(16.dp))

    Button(
        onClick = {
            if (email.isBlank() || password.isBlank()) { error = "Please fill in all fields."; return@Button }
            loading = true
            scope.launch {
                try {
                    FirebaseAuth.getInstance()
                        .signInWithEmailAndPassword(email.trim(), password)
                        .await()
                    onSuccess()
                } catch (e: FirebaseAuthInvalidCredentialsException) {
                    error = "Incorrect email or password."
                } catch (e: Exception) {
                    error = e.message ?: "Login failed."
                } finally {
                    loading = false
                }
            }
        },
        enabled = !loading,
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
        else Text("Log In", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------
// Sign-up form
// ---------------------------------------------------------------------------
@Composable
private fun SignUpForm(onSuccess: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    AuthTextField(value = email, label = "Email", onValueChange = { email = it; error = "" },
        keyboardType = KeyboardType.Email)
    Spacer(Modifier.height(10.dp))
    AuthTextField(value = password, label = "Password", onValueChange = { password = it; error = "" },
        password = true)
    Spacer(Modifier.height(10.dp))
    AuthTextField(value = confirm, label = "Confirm Password", onValueChange = { confirm = it; error = "" },
        password = true)

    if (error.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
    }

    Spacer(Modifier.height(16.dp))

    Button(
        onClick = {
            when {
                email.isBlank() || password.isBlank() -> error = "Please fill in all fields."
                password != confirm -> error = "Passwords do not match."
                password.length < 6 -> error = "Password must be at least 6 characters."
                else -> {
                    loading = true
                    scope.launch {
                        try {
                            FirebaseAuth.getInstance()
                                .createUserWithEmailAndPassword(email.trim(), password)
                                .await()
                            onSuccess()
                        } catch (e: FirebaseAuthWeakPasswordException) {
                            error = "Password is too weak."
                        } catch (e: FirebaseAuthUserCollisionException) {
                            error = "An account with this email already exists."
                        } catch (e: Exception) {
                            error = e.message ?: "Sign-up failed."
                        } finally {
                            loading = false
                        }
                    }
                }
            }
        },
        enabled = !loading,
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
        else Text("Create Account", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------
// Shared text field styled for the dark overlay background
// ---------------------------------------------------------------------------
@Composable
private fun AuthTextField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = Color.White.copy(alpha = 0.7f)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Color.White,
            unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
            cursorColor = Color.White
        )
    )
}
