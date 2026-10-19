package com.crypto.simulationservice.api.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crypto.simulationservice.api.dto.request.SubmitOrderRequest;
import com.crypto.simulationservice.api.dto.response.AcceptedOrderResponse;
import com.crypto.simulationservice.api.mapper.OrderMapper;
import com.crypto.simulationservice.application.service.SubmitOrder;

import jakarta.validation.Valid;


@RestController
@RequestMapping("/api/v1/sim")
public class OrderController {
    
    private final SubmitOrder submit;
    private final OrderMapper mapper;

    public OrderController(SubmitOrder submit, OrderMapper mapper) {
        this.submit = submit;
        this.mapper = mapper;
    }
    @PostMapping("/orders")
    public ResponseEntity<AcceptedOrderResponse> submit(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody SubmitOrderRequest body) {
        // Kimlik istemcinin body/header DAN değil, doğrulanmış JWT'den gelir.
        var result = submit.execute(UUID.fromString(jwt.getSubject()), key, mapper.toCommand(body));
        // Service proxy'si dönünce commit bitmiştir. Controller SQL ve PnL hesabı yapmaz.
        return ResponseEntity.accepted().body(mapper.toResponse(result));
    }
}

