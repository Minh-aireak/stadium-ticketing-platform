package com.aireak.identity.adapter.in.web;

import com.aireak.identity.adapter.in.web.dto.LoginRequest;
import com.aireak.identity.adapter.in.web.dto.LoginResponse;
import com.aireak.identity.adapter.in.web.dto.RegisterRequest;
import com.aireak.identity.adapter.in.web.dto.RegisterResponse;
import com.aireak.identity.application.port.in.LoginUseCase;
import com.aireak.identity.application.port.in.RegisterAccountUseCase;
import com.aireak.identity.application.port.in.command.LoginCommand;
import com.aireak.identity.application.port.in.command.RegisterAccountCommand;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound web adapter — driving adapter for Identity bounded context.
 *
 * <p>Hexagonal rule: this class only translates HTTP ↔ use case commands.
 * No business logic. Delegates everything to use case ports.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RegisterAccountUseCase registerAccountUseCase;
    private final LoginUseCase loginUseCase;

    /**
     * POST /api/v1/auth/register
     * Registers a new account and triggers email verification.
     */
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        String accountId = registerAccountUseCase.execute(
                new RegisterAccountCommand(request.email(), request.password())
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RegisterResponse.of(accountId));
    }

    /**
     * POST /api/v1/auth/login
     * Authenticates and returns a Bearer JWT.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        String token = loginUseCase.execute(
                new LoginCommand(request.email(), request.password())
        );
        return ResponseEntity.ok(LoginResponse.bearer(token));
    }
}
