package com.kilivana.auth.api;

import com.kilivana.auth.service.AuthService;
import com.kilivana.auth.service.PasswordResetService;
import com.kilivana.users.api.UserProfileResponse;
import com.kilivana.users.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final UserService userService;

    public AuthController(
            AuthService authService,
            PasswordResetService passwordResetService,
            UserService userService) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
        this.userService = userService;
    }

    @Operation(summary = "Register a public account", description = "Signup and register are aliases. Public roles: BUYER, FARMER, SUPPLIER, INSPECTOR. Inspectors require employeeCode and start ACTIVE with PENDING verification. Tokens allow authentication, but Inspector privileges require Admin verification.")
    @PostMapping({"/signup", "/register"})
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authService.signup(request));
    }

    @Operation(summary = "Login using email or Kenyan phone", description = "identifier accepts email or a Kenyan phone number. Username is not a login identifier.")
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authService.refresh(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ForgotPasswordResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return ResponseEntity.ok(passwordResetService.requestReset(request.email()));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.reset(request);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me() {
        return ResponseEntity.ok(userService.currentProfile());
    }
}
