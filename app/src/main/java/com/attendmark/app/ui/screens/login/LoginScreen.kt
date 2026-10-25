package com.attendmark.app.ui.screens.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendmark.app.data.AuthRepository
import com.attendmark.app.data.AuthResult
import com.attendmark.app.data.UserPreferences
import com.attendmark.app.ui.theme.*
import kotlinx.coroutines.launch

enum class AuthTab {
    SIGN_IN,
    REGISTER
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onStudentLogin: () -> Unit,
    onFacultyLogin: () -> Unit = {},
    onEnrollmentNeeded: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { UserPreferences(context) }
    val authRepo = remember { AuthRepository() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var selectedTab by remember { mutableStateOf(AuthTab.SIGN_IN) }

    // Form fields
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val isFaceEnrolled by prefs.isFaceEnrolled.collectAsState(initial = false)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(PrimaryBlueDark, PrimaryBlue, PrimaryBlueLight)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // App Icon / Logo
            Text(
                text = "📋",
                fontSize = 56.sp,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "AttendMark",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White
            )

            Text(
                text = "Multi-Factor Attendance Verification",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Auth Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Mode Selector Tabs: Sign In vs Register
                    TabRow(
                        selectedTabIndex = selectedTab.ordinal,
                        containerColor = Color.Transparent,
                        contentColor = PrimaryBlue,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Tab(
                            selected = selectedTab == AuthTab.SIGN_IN,
                            onClick = {
                                selectedTab = AuthTab.SIGN_IN
                                errorMessage = null
                            },
                            enabled = !isLoading,
                            text = {
                                Text(
                                    "Sign In",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (selectedTab == AuthTab.SIGN_IN) PrimaryBlue else TextSecondary
                                )
                            }
                        )
                        Tab(
                            selected = selectedTab == AuthTab.REGISTER,
                            onClick = {
                                selectedTab = AuthTab.REGISTER
                                errorMessage = null
                            },
                            enabled = !isLoading,
                            text = {
                                Text(
                                    "Register",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (selectedTab == AuthTab.REGISTER) PrimaryBlue else TextSecondary
                                )
                            }
                        )
                    }

                    // Subtitle
                    Text(
                        text = if (selectedTab == AuthTab.SIGN_IN) {
                            "Sign in with your student credentials"
                        } else {
                            "Create student account (ID required)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    // Register-only: Name field
                    if (selectedTab == AuthTab.REGISTER) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = {
                                name = it
                                errorMessage = null
                            },
                            label = { Text("Full Name (Optional)") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = PrimaryBlue) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            ),
                            singleLine = true,
                            enabled = !isLoading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryBlue,
                                unfocusedBorderColor = Color.LightGray
                            )
                        )
                    }

                    // Email field
                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            errorMessage = null
                        },
                        label = {
                            Text(
                                if (selectedTab == AuthTab.REGISTER) "Student Email (e.g. 21cs01@univ.edu)"
                                else "Student Email"
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = PrimaryBlue) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryBlue,
                            unfocusedBorderColor = Color.LightGray
                        )
                    )

                    if (selectedTab == AuthTab.REGISTER) {
                        Text(
                            text = "First two characters must be digits representing your roll/year (e.g. 21, 24).",
                            style = MaterialTheme.typography.bodySmall,
                            color = PrimaryBlue,
                            fontSize = 11.sp
                        )
                    }

                    // Password field
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            errorMessage = null
                        },
                        label = {
                            Text(if (selectedTab == AuthTab.REGISTER) "Password (min. 6 chars)" else "Password")
                        },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = PrimaryBlue) },
                        trailingIcon = {
                            val icon = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(icon, contentDescription = "Toggle password visibility", tint = TextSecondary)
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = if (selectedTab == AuthTab.REGISTER) ImeAction.Next else ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            onDone = { focusManager.clearFocus() }
                        ),
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryBlue,
                            unfocusedBorderColor = Color.LightGray
                        )
                    )

                    // Register-only: Confirm Password field
                    if (selectedTab == AuthTab.REGISTER) {
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = {
                                confirmPassword = it
                                errorMessage = null
                            },
                            label = { Text("Confirm Password") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = PrimaryBlue) },
                            trailingIcon = {
                                val icon = if (confirmPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                                IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                    Icon(icon, contentDescription = "Toggle confirm password visibility", tint = TextSecondary)
                                }
                            },
                            visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { focusManager.clearFocus() }
                            ),
                            singleLine = true,
                            enabled = !isLoading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryBlue,
                                unfocusedBorderColor = Color.LightGray
                            )
                        )
                    }

                    // Error Message Banner
                    AnimatedVisibility(visible = errorMessage != null) {
                        errorMessage?.let { msg ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = AccentRed.copy(alpha = 0.12f)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = msg,
                                    color = AccentRed,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }

                    // Submit Button
                    Button(
                        onClick = {
                            val trimmedEmail = email.trim()
                            val exactPassword = password
                            val trimmedName = name.trim()

                            if (trimmedEmail.isBlank() || exactPassword.isEmpty()) {
                                errorMessage = "Please enter email and password"
                                return@Button
                            }

                            if (selectedTab == AuthTab.REGISTER) {
                                if (trimmedEmail.length < 2 || !trimmedEmail[0].isDigit() || !trimmedEmail[1].isDigit()) {
                                    errorMessage = "Student email must start with your 2-digit roll/year prefix (e.g. 21...)"
                                    return@Button
                                }
                                if (exactPassword.length < 6) {
                                    errorMessage = "Password must be at least 6 characters"
                                    return@Button
                                }
                                if (exactPassword != confirmPassword) {
                                    errorMessage = "Passwords do not match"
                                    return@Button
                                }

                                isLoading = true
                                errorMessage = null
                                scope.launch {
                                    when (val result = authRepo.register(trimmedEmail, exactPassword, confirmPassword, trimmedName)) {
                                        is AuthResult.Success -> {
                                            val user = result.user
                                            if (user.role == "faculty") {
                                                authRepo.logout()
                                                isLoading = false
                                                errorMessage = "Faculty account detected. Student registration is for students only. Please use the Faculty Web Portal at https://attendmark-c2f07.web.app"
                                            } else {
                                                prefs.saveLoginState(user.email, user.role, user.name, user.uid)
                                                isLoading = false
                                                if (isFaceEnrolled) {
                                                    onStudentLogin()
                                                } else {
                                                    onEnrollmentNeeded()
                                                }
                                            }
                                        }
                                        is AuthResult.Error -> {
                                            isLoading = false
                                            errorMessage = result.message
                                        }
                                    }
                                }
                            } else {
                                // SIGN_IN flow
                                isLoading = true
                                errorMessage = null
                                scope.launch {
                                    when (val result = authRepo.login(trimmedEmail, exactPassword)) {
                                        is AuthResult.Success -> {
                                            val user = result.user
                                            if (user.role == "faculty") {
                                                authRepo.logout()
                                                isLoading = false
                                                errorMessage = "Faculty account detected. Faculty members must use the Faculty Web Portal at https://attendmark-c2f07.web.app"
                                            } else {
                                                prefs.saveLoginState(user.email, user.role, user.name, user.uid)
                                                isLoading = false
                                                if (isFaceEnrolled) {
                                                    onStudentLogin()
                                                } else {
                                                    onEnrollmentNeeded()
                                                }
                                            }
                                        }
                                        is AuthResult.Error -> {
                                            isLoading = false
                                            errorMessage = result.message
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = if (selectedTab == AuthTab.SIGN_IN) "Sign In" else "Register as Student",
                                fontSize = 16.sp,
                                color = Color.White
                            )
                        }
                    }

                    // Faculty Portal note
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Faculty member?",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Text(
                            text = "Use the Faculty Web Portal at https://attendmark-c2f07.web.app",
                            style = MaterialTheme.typography.bodySmall,
                            color = PrimaryBlue,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
