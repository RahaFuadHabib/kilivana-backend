package com.kilivana;

import com.kilivana.auth.api.AuthResponse;
import com.kilivana.support.IntegrationTestSupport;
import com.kilivana.users.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountIdentityIntegrationTest extends IntegrationTestSupport {
    @Autowired UserRepository users;

    private Map<String, Object> account(String email, String username, String phone, String role) {
        var body = new LinkedHashMap<String, Object>();
        body.put("email", email);
        body.put("username", username);
        body.put("phoneNumber", phone);
        body.put("password", "secretPass1");
        body.put("fullName", "Test Account");
        body.put("region", "  Nairobi  ");
        body.put("role", role);
        if (role.equals("FARMER")) body.put("farmName", "Green Farm");
        if (role.equals("SUPPLIER")) body.put("businessName", "Green Supply");
        if (role.equals("INSPECTOR")) body.put("employeeCode", "employee");
        return body;
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(body));
    }

    private AuthResponse create(Map<String, Object> body) throws Exception {
        return readBody(mockMvc.perform(json(post("/api/v1/auth/signup"), body))
                .andExpect(status().isOk()).andReturn(), AuthResponse.class);
    }

    @Test
    void canonicalSignupAndEmailOrPhoneLoginReturnSameAccount() throws Exception {
        AuthResponse created = create(account("buyer@example.com", "  Green_Buyer  ", "0712 345 678", "BUYER"));
        assertThat(created.username()).isEqualTo("green_buyer");
        assertThat(created.region()).isEqualTo("Nairobi");
        assertThat(created.phoneNumber()).isEqualTo("+254712345678");
        for (String identifier : new String[]{" BUYER@EXAMPLE.COM ", "0712345678", "+254712345678", "254712345678"}) {
            mockMvc.perform(json(post("/api/v1/auth/login"), Map.of("identifier", identifier, "password", "secretPass1")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(created.userId().toString()));
        }
        mockMvc.perform(authorized(get("/api/v1/auth/me"), created.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("green_buyer"))
                .andExpect(jsonPath("$.region").value("Nairobi"));
    }

    @Test
    void usernamesAndEquivalentPhonesAreUniqueAcrossRoles() throws Exception {
        create(account("buyer@example.com", "green_user", "0712345678", "BUYER"));
        mockMvc.perform(json(post("/api/v1/auth/signup"), account("supplier@example.com", " GREEN_USER ", "0722345678", "SUPPLIER")))
                .andExpect(status().isConflict());
        mockMvc.perform(json(post("/api/v1/auth/signup"), account("farmer@example.com", "green_farmer", "+254712345678", "FARMER")))
                .andExpect(status().isConflict());
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void everyPublicRoleRequiresRegion() throws Exception {
        for (String role : new String[]{"BUYER", "FARMER", "SUPPLIER", "INSPECTOR"}) {
            var body = account(role.toLowerCase() + "@example.com", "green_user", "0712345678", role);
            body.remove("region");
            body.put("farmLocation", "Nairobi");
            mockMvc.perform(json(post("/api/v1/auth/signup"), body)).andExpect(status().isBadRequest());
        }
        assertThat(users.count()).isZero();
    }

    @Test
    void invalidUsernameAndPhoneNeverCreateAccounts() throws Exception {
        for (String username : new String[]{"123farmer", "farmer-name", "farmer name", "abcdefghijklmnopqrstuvwxyz12345"}) {
            mockMvc.perform(json(post("/api/v1/auth/signup"), account("buyer@example.com", username, "0712345678", "BUYER")))
                    .andExpect(status().isBadRequest());
        }
        assertThat(users.count()).isZero();
    }

    @Test
    void rejectsNonKenyanAndInvalidPhoneAtSignup() throws Exception {
        for (String phone : new String[]{"+12025550123", "12345", "0712345678 ext 1", "call 0712345678"}) {
            mockMvc.perform(json(post("/api/v1/auth/signup"), account("buyer@example.com", "green_user", phone, "BUYER")))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void usernameUnknownIdentifierWrongPasswordAndLegacyEmailPayloadCannotLogin() throws Exception {
        create(account("buyer@example.com", "green_user", "0712345678", "BUYER"));
        for (String identifier : new String[]{"green_user", "missing@example.com", "0722345678"}) {
            mockMvc.perform(json(post("/api/v1/auth/login"), Map.of("identifier", identifier, "password", "secretPass1")))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(json(post("/api/v1/auth/login"), Map.of("identifier", "0712345678", "password", "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(json(post("/api/v1/auth/login"), Map.of("email", "buyer@example.com", "password", "secretPass1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void profileUpdatesNormalizeIdentifiersAndPreserveOmittedFields() throws Exception {
        AuthResponse created = create(account("buyer@example.com", "green_user", "0712345678", "BUYER"));
        mockMvc.perform(authorized(json(put("/api/v1/users/me"), Map.of("username", " Updated_User ",
                        "phoneNumber", "0722-345-678", "region", " Nakuru ")), created.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("updated_user"))
                .andExpect(jsonPath("$.phoneNumber").value("+254722345678"))
                .andExpect(jsonPath("$.region").value("Nakuru"));
        mockMvc.perform(authorized(json(put("/api/v1/users/me"), Map.of()), created.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("updated_user"));
        mockMvc.perform(json(post("/api/v1/auth/login"), Map.of("identifier", "0722345678", "password", "secretPass1")))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/api/v1/auth/login"), Map.of("identifier", "0712345678", "password", "secretPass1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void profileRejectsCollisionsAndRollsBackOtherChanges() throws Exception {
        create(account("first@example.com", "first_user", "0712345678", "BUYER"));
        AuthResponse second = create(account("second@example.com", "second_user", "0722345678", "BUYER"));
        for (Map<String, String> update : java.util.List.of(Map.of("username", "FIRST_USER", "region", "Nakuru"),
                Map.of("phoneNumber", "0712345678", "username", "changed_user"))) {
            mockMvc.perform(authorized(json(put("/api/v1/users/me"), update), second.accessToken()))
                    .andExpect(status().isConflict());
        }
        var user = users.findById(second.userId()).orElseThrow();
        assertThat(user.getUsername()).isEqualTo("second_user");
        assertThat(user.getRegion()).isEqualTo("Nairobi");
    }

    @Test
    void profileRejectsBlankValuesAndAllowsOwnCanonicalIdentifiers() throws Exception {
        AuthResponse created = create(account("buyer@example.com", "green_user", "0712345678", "BUYER"));
        mockMvc.perform(authorized(json(put("/api/v1/users/me"), Map.of("username", "GREEN_USER", "phoneNumber", "0712345678")), created.accessToken()))
                .andExpect(status().isOk());
        for (String field : new String[]{"username", "region", "phoneNumber", "fullName"}) {
            mockMvc.perform(authorized(json(put("/api/v1/users/me"), Map.of(field, "   ")), created.accessToken()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void farmerRegionReplacesLocationAndCompatibilityUpdatesStayInSync() throws Exception {
        AuthResponse created = create(account("farmer@example.com", "green_farmer", "0712345678", "FARMER"));
        mockMvc.perform(authorized(json(put("/api/v1/users/me"), Map.of("region", "Nakuru")), created.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.region").value("Nakuru"))
                .andExpect(jsonPath("$.farmLocation").value("Nakuru"));
        mockMvc.perform(authorized(json(put("/api/v1/users/me"), Map.of("farmLocation", "Kiambu")), created.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.region").value("Kiambu"))
                .andExpect(jsonPath("$.farmLocation").value("Kiambu"));
        mockMvc.perform(authorized(json(put("/api/v1/users/me"), Map.of("region", "Nakuru", "farmLocation", "Nairobi")), created.accessToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void farmerSignupRejectsConflictingLegacyLocation() throws Exception {
        var body = account("farmer@example.com", "green_farmer", "0712345678", "FARMER");
        body.put("farmLocation", "Kiambu");
        mockMvc.perform(json(post("/api/v1/auth/signup"), body)).andExpect(status().isBadRequest());
        body.put("farmLocation", "Nairobi");
        create(body);
    }

    @Test
    void suspendedAccountsCannotLoginByEitherIdentifier() throws Exception {
        AuthResponse created = create(account("buyer@example.com", "green_user", "0712345678", "BUYER"));
        var user = users.findById(created.userId()).orElseThrow();
        user.suspend();
        users.saveAndFlush(user);
        for (String identifier : new String[]{"buyer@example.com", "0712345678"}) {
            mockMvc.perform(json(post("/api/v1/auth/login"), Map.of("identifier", identifier, "password", "secretPass1")))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void pendingInspectorPhoneLoginKeepsVerificationState() throws Exception {
        create(account("inspector@example.com", "green_inspector", "0712345678", "INSPECTOR"));
        mockMvc.perform(json(post("/api/v1/auth/login"), Map.of("identifier", "0712345678", "password", "secretPass1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verificationStatus").value("PENDING"));
    }

    @Test
    void signupAndProfileValidateCanonicalThirtyCharacterBoundary() throws Exception {
        AuthResponse created = create(account("buyer@example.com", "  " + "A".repeat(30) + "  ", "0712345678", "BUYER"));
        assertThat(created.username()).isEqualTo("a".repeat(30));
        mockMvc.perform(authorized(json(put("/api/v1/users/me"), Map.of("username", "  AMA  ")), created.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("ama"));
        for (String username : new String[]{"A", "123farmer", "farmer-name", "farmer name", "abcdefghijklmnopqrstuvwxyz12345"}) {
            mockMvc.perform(authorized(json(put("/api/v1/users/me"), Map.of("username", username)), created.accessToken()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void driverVehicleFieldsUpdateIndependentlyAndRejectBlankValues() throws Exception {
        AuthResponse driver = privilegedSignup(new com.kilivana.auth.api.SignupRequest(
                "driver@example.com", "secretPass1", "Driver", "+254712345678", "green_driver", "Nairobi",
                com.kilivana.users.domain.UserRole.DRIVER, null, null, null, null, "license", "Truck", "KAA123A", "Nairobi"));
        mockMvc.perform(authorized(json(put("/api/v1/users/me"), Map.of("vehicleType", " Van ")), driver.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.vehicleType").value("Van"))
                .andExpect(jsonPath("$.vehiclePlate").value("KAA123A"));
        mockMvc.perform(authorized(json(put("/api/v1/users/me"), Map.of("vehiclePlate", " KBB456B ")), driver.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.vehicleType").value("Van"))
                .andExpect(jsonPath("$.vehiclePlate").value("KBB456B"));
        mockMvc.perform(authorized(json(put("/api/v1/users/me"), Map.of("serviceArea", " ")), driver.accessToken()))
                .andExpect(status().isBadRequest());
    }
}
