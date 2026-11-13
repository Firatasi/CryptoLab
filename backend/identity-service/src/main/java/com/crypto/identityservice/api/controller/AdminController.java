package com.crypto.identityservice.api.controller;

import com.crypto.identityservice.api.dto.request.UpdateContactStatusRequest;
import com.crypto.identityservice.application.service.AdminSupportService;
import com.crypto.identityservice.application.service.AdminSupportService.AdminOverview;
import com.crypto.identityservice.application.service.AdminSupportService.ContactSubmissionView;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminSupportService supportService;

    public AdminController(AdminSupportService supportService) {
        this.supportService = supportService;
    }

    @GetMapping("/overview")
    public AdminOverview overview() {
        return supportService.overview();
    }

    @PatchMapping(value = "/messages/{id}/status", consumes = "application/json")
    public ContactSubmissionView updateMessageStatus(
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateContactStatusRequest request
    ) {
        return supportService.updateStatus(id, request.status());
    }
}
