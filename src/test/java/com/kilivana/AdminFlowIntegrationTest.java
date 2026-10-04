package com.kilivana;

import com.kilivana.admin.api.AdminSignupRequest;
import com.kilivana.admin.service.AdminSignupService;
import com.kilivana.auth.api.AuthResponse;
import com.kilivana.auth.api.SignupRequest;
import com.kilivana.support.IntegrationTestSupport;
import com.kilivana.users.api.UpdateUserStatusRequest;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminFlowIntegrationTest extends IntegrationTestSupport {

    private static final String ADMIN_BOOTSTRAP_KEY = "test-admin-bootstrap-key";

    private AuthResponse adminSignup(String email) throws Exception {
        return readBody(mockMvc.perform(post("/api/v1/admins/signup")
                        .header(AdminSignupService.BOOTSTRAP_KEY_HEADER, ADMIN_BOOTSTRAP_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new AdminSignupRequest(email, "secretPass1", "System Root", "+254700000099"))))
                .andExpect(status().isOk())
                .andReturn(), AuthResponse.class);
    }

    @Test
    void adminSignupShouldProvisionAnAdminAccountWithAUsableToken() throws Exception {
        AuthResponse admin = adminSignup("root1@kilivana.example");

        assertThat(admin.role()).isEqualTo(UserRole.ADMIN);
        assertThat(admin.accessToken()).isNotBlank();

        mockMvc.perform(authorized(get("/api/v1/users/me"), admin.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void adminSignupWithoutBootstrapKeyShouldBeForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/admins/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new AdminSignupRequest("root2@kilivana.example", "secretPass1", "System Root", "+254700000098"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void marketplaceSignupShouldRejectAdminRole() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new SignupRequest(
                "fake-admin@example.com",
                "secretPass1",
                "Fake Admin",
                "+254700000098",
                "test_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25),
                "Nairobi",
                UserRole.ADMIN,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_CONFLICT"));
    }

    @Test
    void adminCanSuspendAnActivateAnotherUser() throws Exception {
        AuthResponse admin = adminSignup("root3@kilivana.example");

        AuthResponse supplier = signup(new SignupRequest(
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

        mockMvc.perform(authorized(patch("/api/v1/users/{userId}/status", supplier.userId()), admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new UpdateUserStatusRequest(UserStatus.SUSPENDED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        mockMvc.perform(authorized(patch("/api/v1/users/{userId}/status", supplier.userId()), admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new UpdateUserStatusRequest(UserStatus.ACTIVE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void nonAdminCannotSuspendAUser() throws Exception {
        AuthResponse supplier = signup(new SignupRequest(
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

        mockMvc.perform(authorized(patch("/api/v1/users/{userId}/status", supplier.userId()), supplier.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new UpdateUserStatusRequest(UserStatus.SUSPENDED))))
                .andExpect(status().isForbidden());
    }
}