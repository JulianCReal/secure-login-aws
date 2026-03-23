package com.securelogin.controller;

import com.securelogin.dto.request.*;
import com.securelogin.dto.response.*;
import com.securelogin.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * POST /api/auth/register
     * Body: { "username":"juan", "email":"juan@example.com",
     *         "password":"pass1234", "fullName":"Juan García" }
     * 200: { "message": "User registered successfully." }
     * 400: { "password": "size must be between 8 and 72" }
     */
    @PostMapping("/register")
    public ResponseEntity<MessageResponse> register(
            @Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.ok(authService.register(req));
    }

    /**
     * POST /api/auth/login
     * Body: { "username":"juan", "password":"pass1234" }
     * 200: { "token":"eyJhbGci...", "tokenType":"Bearer", "expiresIn":86400000 }
     * 401: { "message": "Invalid username or password." }
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }
}
