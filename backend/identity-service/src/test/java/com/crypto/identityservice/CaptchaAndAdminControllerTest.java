package com.crypto.identityservice;

import com.crypto.identityservice.api.controller.AdminController;
import com.crypto.identityservice.api.controller.AuthController;
import com.crypto.identityservice.api.dto.request.ContactMessageRequest;
import com.crypto.identityservice.api.dto.request.RegisterRequest;
import com.crypto.identityservice.api.dto.request.UpdateContactStatusRequest;
import com.crypto.identityservice.api.exception.IdentityExceptionHandler;
import com.crypto.identityservice.application.repository.IdentityRepository;
import com.crypto.identityservice.application.service.CaptchaService;
import com.crypto.identityservice.application.service.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class CaptchaAndAdminControllerTest {

    @Autowired
    AuthController authController;

    @Autowired
    AdminController adminController;

    @Autowired
    IdentityService identityService;

    @Autowired
    IdentityRepository identityRepository;

    @Autowired
    CaptchaService captchaService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IdentityExceptionHandler exceptionHandler;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminBootstrapExistsWithSingleAdminRoleEnforcedByDatabase() {
        var issued = identityService.login("admin.com", "cryptoadmin15", "127.0.0.1");
        assertNotNull(issued.accessToken());

        UUID adminId = jdbc.queryForObject(
                "SELECT id FROM users WHERE normalized_email = 'admin.com'",
                UUID.class
        );
        List<String> adminRoles = jdbc.queryForList(
                "SELECT role FROM user_roles WHERE user_id = ?",
                String.class,
                adminId
        );
        assertEquals(List.of("ADMIN"), adminRoles);

        // Normal kullanıcı kayıt edilir -> yalnızca USER rolü verilir ve ikinci ADMIN eklenemez
        UUID captchaId = UUID.randomUUID();
        captchaService.createChallengeForTest(captchaId, "7K9MP");
        String userEmail = "trader-" + UUID.randomUUID() + "@example.com";
        var created = authController.register(
                new RegisterRequest(userEmail, "VerySecurePass123!", "Trader", captchaId, "7k9mp"),
                "idem-" + UUID.randomUUID()
        );
        UUID userId = created.getBody().id();
        List<String> userRoles = jdbc.queryForList(
                "SELECT role FROM user_roles WHERE user_id = ?",
                String.class,
                userId
        );
        assertEquals(List.of("USER"), userRoles);

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'ADMIN')", userId)
        );
    }

    @Test
    void captchaRejectsWrongCodeEnforcesRetryLimitAndIsSingleUse() {
        UUID captchaId = UUID.randomUUID();
        var challenge = captchaService.createChallengeForTest(captchaId, "AB34C");
        assertTrue(challenge.imageDataUri().startsWith("data:image/svg+xml;base64,"));

        // Hatalı kod -> CAPTCHA_INVALID (HTTP 400)
        String email = "cap-" + UUID.randomUUID() + "@example.com";
        var wrongEx = assertThrows(IllegalArgumentException.class, () ->
                authController.register(
                        new RegisterRequest(email, "VerySecurePass123!", "Captcha User", captchaId, "WRONG"),
                        "idem-" + UUID.randomUUID()
                )
        );
        assertEquals("CAPTCHA_INVALID", wrongEx.getMessage());
        assertEquals(400, exceptionHandler.invalidArgument(wrongEx).getStatusCode().value());

        // Doğru kodla kayıt olur (büyük/küçük harf duyarsız)
        String idemKey = "idem-" + UUID.randomUUID();
        var ok = authController.register(
                new RegisterRequest(email, "VerySecurePass123!", "Captcha User", captchaId, " ab34c "),
                idemKey
        );
        assertEquals(201, ok.getStatusCode().value());

        // Aynı idempotency anahtarıyla tekrar deneme tüketilmiş captcha hatası vermez
        var idempotentReplay = authController.register(
                new RegisterRequest(email, "VerySecurePass123!", "Captcha User", captchaId, "WRONG"),
                idemKey
        );
        assertEquals(ok.getBody().id(), idempotentReplay.getBody().id());

        // Aynı captchaId başka kayıt için tekrar kullanılamaz
        UUID nextEmailId = UUID.randomUUID();
        var replayCaptcha = assertThrows(IllegalArgumentException.class, () ->
                authController.register(
                        new RegisterRequest("other-" + nextEmailId + "@example.com", "VerySecurePass123!", "Bot", captchaId, "AB34C"),
                        "idem-" + nextEmailId
                )
        );
        assertEquals("CAPTCHA_EXPIRED", replayCaptcha.getMessage());
        assertEquals(400, exceptionHandler.invalidArgument(replayCaptcha).getStatusCode().value());
    }

    @Test
    void contactSubmissionAdminOnlyOverviewAndLiveUserCountWorkTogether() {
        // Admin dışındaki roller /admin uçlarına erişemez
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        UUID.randomUUID().toString(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                )
        );
        assertThrows(AccessDeniedException.class, () -> adminController.overview());

        // İletişim formu gönderilir -> support@cryptolab.dev hedefine düşer
        var req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.15");
        var submitted = authController.submitContact(
                new ContactMessageRequest(
                        "Ahmet Yılmaz",
                        "ahmet@example.com",
                        "Destek Talebi",
                        "Sanal oturum açılışı hakkında bilgi almak istiyorum.",
                        null
                ),
                req
        );
        assertEquals(201, submitted.getStatusCode().value());
        assertEquals("support@cryptolab.dev", submitted.getBody().recipientEmail());
        UUID msgId = submitted.getBody().id();

        // Canlı kullanıcı giriş yapar -> liveUserCount artar
        UUID cap = UUID.randomUUID();
        captchaService.createChallengeForTest(cap, "W8M2N");
        String userEmail = "live-" + UUID.randomUUID() + "@example.com";
        authController.register(
                new RegisterRequest(userEmail, "VerySecurePass123!", "Live Trader", cap, "W8M2N"),
                "idem-live-" + cap
        );
        var loginIssued = identityService.login(userEmail, "VerySecurePass123!", "10.0.0.16");

        // ADMIN yetkisiyle kontrol
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "00000000-0000-4000-8000-00000000a001",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                )
        );

        var overviewDuringSession = adminController.overview();
        assertTrue(overviewDuringSession.liveUserCount() >= 1);
        assertTrue(overviewDuringSession.totalUserCount() >= 1);
        assertTrue(overviewDuringSession.messages().stream().anyMatch(m -> m.id().equals(msgId)));

        // Mesaj durumunu RESOLVED yap
        var updatedMsg = adminController.updateMessageStatus(msgId, new UpdateContactStatusRequest("RESOLVED"));
        assertEquals("RESOLVED", updatedMsg.status());
        assertNotNull(updatedMsg.readAt());

        // Kullanıcı çıkış yapar -> canlı oturum sayısı düşer
        identityService.logout(loginIssued.refreshToken());
        var overviewAfterLogout = adminController.overview();
        assertEquals(overviewDuringSession.liveUserCount() - 1, overviewAfterLogout.liveUserCount());
    }
}
