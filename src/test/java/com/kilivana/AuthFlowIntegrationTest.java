package com.kilivana;

import com.kilivana.auth.api.AuthResponse;
import com.kilivana.auth.api.SignupRequest;
import com.kilivana.support.IntegrationTestSupport;
import com.kilivana.users.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowIntegrationTest extends IntegrationTestSupport {

    @Test
    void signupRefreshAndLogoutShouldRotateRefreshTokens() throws Exception {
        AuthResponse signup = signup(new SignupRequest(
                "supplier@example.com",
                "secretPass1",
                "Supplier User",
                "+254700000001",
                "test_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25),
                "Nairobi",
                UserRole.SUPPLIER,
                "Green Supply Ltd",
                null,
                null,
                null,
                null,
                null,
                null,
                null));

        AuthResponse refreshed = refresh(signup.refreshToken());

        assertThat(signup.userId()).isNotNull();
        assertThat(signup.role()).isEqualTo(UserRole.SUPPLIER);
        assertThat(signup.accessToken()).isNotBlank();
        assertThat(refreshed.refreshToken()).isNotEqualTo(signup.refreshToken());
        assertThat(refreshed.accessToken()).isNotEqualTo(signup.accessToken());

        logout(refreshed.refreshToken());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new com.kilivana.auth.api.RefreshTokenRequest(refreshed.refreshToken()))))
                .andExpect(status().isUnauthorized());
    }
}
