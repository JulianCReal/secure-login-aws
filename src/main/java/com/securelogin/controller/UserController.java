package com.securelogin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    /**
     * GET /api/user/me
     * Requiere: Authorization: Bearer <token>
     * 200 OK  → token válido y sesión activa
     * 403     → token ausente, expirado o con firma inválida
     */
    @GetMapping("/me")
    public ResponseEntity<Void> getMe(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok().build();
    }
}
