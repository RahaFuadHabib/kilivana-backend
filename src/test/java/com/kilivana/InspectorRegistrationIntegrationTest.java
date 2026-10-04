package com.kilivana;

import com.kilivana.auth.api.*;
import com.kilivana.support.IntegrationTestSupport;
import com.kilivana.users.domain.*;
import com.kilivana.users.repository.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.test.context.TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true", "kilivana.security.docs.public=true"})
class InspectorRegistrationIntegrationTest extends IntegrationTestSupport {
    @Autowired UserRepository users;
    @Autowired InspectorProfileRepository profiles;

    static SignupRequest request(UserRole role, String code) {
        return new SignupRequest(
                "signup@example.com",
                "secretPass1",
                "Test User",
                "+254700000001",
                "test_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25),
                "Nairobi",
                role,
                "Business",
                "Farm",
                null,
                code,
                null,
                null,
                null,
                null);
    }

    @org.junit.jupiter.api.Test
    void openApiDescribesPublicRolesAndVerificationContract() throws Exception {
        var result = mockMvc.perform(get("/api-docs")).andExpect(status().isOk()).andReturn();
        var document = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        var schemas = document.path("components").path("schemas");
        assertThat(schemas.path("SignupRequest").path("properties").path("role").path("enum"))
                .isEqualTo(objectMapper.valueToTree(java.util.List.of("BUYER", "FARMER", "SUPPLIER", "INSPECTOR")));
        assertThat(schemas.path("SignupRequest").path("properties").path("employeeCode").path("maxLength").asInt()).isEqualTo(100);
        for (String schema : new String[]{"SignupRequest", "UpdateUserProfileRequest", "AuthResponse", "UserProfileResponse"}) {
            var username = schemas.path(schema).path("properties").path("username");
            assertThat(username.path("minLength").asInt()).isEqualTo(3);
            assertThat(username.path("maxLength").asInt()).isEqualTo(30);
            assertThat(username.path("pattern").asText()).isEqualTo("[a-z][a-z0-9_]{2,29}");
        }
        assertThat(schemas.path("AuthResponse").path("properties").has("verificationStatus")).isTrue();
        assertThat(document.path("paths").path("/api/v1/auth/signup").path("post").path("description").asText()).contains("PENDING");
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/inspector-openapi.json"), document.toPrettyString());
    }

    @org.junit.jupiter.api.Test
    void employeeCodeAcceptsOneHundredCharactersAndIsNotUnique() throws Exception {
        SignupRequest first = request(UserRole.INSPECTOR, "x".repeat(100));
        signup(first);
        signup(new SignupRequest(
                "second@example.com",
                first.password(),
                first.fullName(),
                "+254700000002",
                "test_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25),
                "Nairobi",
                first.role(),
                null,
                null,
                null,
                first.employeeCode(),
                null,
                null,
                null,
                null));
        assertThat(profiles.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"signup", "register"})
    void aliasesCreatePendingInspectorWithProfileAndUsableAuthentication(String alias) throws Exception {
        AuthResponse registered = readBody(mockMvc.perform(post("/api/v1/auth/" + alias)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request(UserRole.INSPECTOR, "  arbitrary code  "))))
                .andExpect(status().isOk()).andReturn(), AuthResponse.class);
        assertThat(registered.role()).isEqualTo(UserRole.INSPECTOR);
        assertThat(registered.verificationStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(registered.accessToken()).isNotBlank();
        assertThat(registered.refreshToken()).isNotBlank();
        User user = users.findById(registered.userId()).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getVerificationStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(profiles.findByUserId(user.getId()).orElseThrow().getEmployeeCode()).isEqualTo("arbitrary code");
        mockMvc.perform(authorized(get("/api/v1/auth/me"), registered.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verificationStatus").value("PENDING"));
        AuthResponse login = readBody(mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(new LoginRequest(user.getEmail(), "secretPass1"))))
                .andExpect(status().isOk()).andReturn(), AuthResponse.class);
        assertThat(login.verificationStatus()).isEqualTo(VerificationStatus.PENDING);
        AuthResponse refreshed = refresh(login.refreshToken());
        assertThat(refreshed.verificationStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        logout(refreshed.refreshToken());
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(new RefreshTokenRequest(refreshed.refreshToken()))))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"signup", "register"})
    void invalidInspectorCodesNeverCreateAnAccount(String alias) throws Exception {
        for (String code : new String[]{null, "", "   ", "x".repeat(101)}) {
            mockMvc.perform(post("/api/v1/auth/" + alias).contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(request(UserRole.INSPECTOR, code))))
                    .andExpect(status().is(code != null && code.length() > 100 ? 400 : 409));
            assertThat(users.count()).isZero();
            assertThat(profiles.count()).isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"BUYER", "FARMER", "SUPPLIER"})
    void existingPublicRolesDoNotRequireEmployeeCodeOrChangeVerification(UserRole role) throws Exception {
        AuthResponse response = signup(request(role, null));
        assertThat(response.role()).isEqualTo(role);
        assertThat(response.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(users.findById(response.userId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(profiles.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"signup", "register"})
    void driverAndAdminRemainUnavailable(String alias) throws Exception {
        for (UserRole role : new UserRole[]{UserRole.DRIVER, UserRole.ADMIN}) {
            mockMvc.perform(post("/api/v1/auth/" + alias).contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(request(role, "code"))))
                    .andExpect(status().isConflict());
        }
        assertThat(users.count()).isZero();
    }
}
