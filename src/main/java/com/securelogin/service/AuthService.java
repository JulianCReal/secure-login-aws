package com.securelogin.service;

import com.securelogin.dto.request.*;
import com.securelogin.dto.response.*;
import com.securelogin.model.User;
import com.securelogin.repository.UserRepository;
import com.securelogin.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository      userRepository;
    private final PasswordEncoder     passwordEncoder;     // BCryptPasswordEncoder(12)
    private final AuthenticationManager authenticationManager;
    private final JwtUtils            jwtUtils;

    /**
     * Registra un nuevo usuario.
     * La contraseña se hashea con BCrypt ANTES de persistir.
     */
    @Transactional
    public MessageResponse register(RegisterRequest req) {
        if (userRepository.existsByUsername(req.getUsername()))
            throw new IllegalArgumentException("Username is already taken.");
        if (userRepository.existsByEmail(req.getEmail()))
            throw new IllegalArgumentException("Email is already registered.");

        User user = User.builder()
                .username(req.getUsername())
                .email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))  // ← BCrypt hash
                .fullName(req.getFullName())
                .build();

        userRepository.save(user);
        log.info("Nuevo usuario registrado: {}", user.getUsername());
        return new MessageResponse("User registered successfully.");
    }

    /**
     * Autentica al usuario, genera JWT y actualiza lastLogin.
     * Spring Security llama a BCrypt.matches() internamente.
     */
    @Transactional
    public AuthResponse login(LoginRequest req) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        req.getUsername(), req.getPassword()));

        SecurityContextHolder.getContext().setAuthentication(auth);

        UserDetails userDetails = (UserDetails) auth.getPrincipal();
        String token = jwtUtils.generateToken(userDetails);

        // Actualizar timestamp de último login
        userRepository.findByUsername(userDetails.getUsername()).ifPresent(user -> {
            user.setLastLogin(Instant.now());
            userRepository.save(user);
        });

        log.info("Login exitoso: {}", userDetails.getUsername());
        return new AuthResponse(token, jwtUtils.getExpirationMs());
    }
}
