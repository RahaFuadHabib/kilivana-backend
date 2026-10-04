package com.kilivana;

import com.kilivana.auth.api.*;
import com.kilivana.admin.api.AdminSignupRequest;
import com.kilivana.badges.api.AwardBadgeRequest;
import com.kilivana.inspection.api.*;
import com.kilivana.inspection.domain.*;
import com.kilivana.inspection.repository.VisitPhotoRepository;
import com.kilivana.inspection.repository.InspectionRepository;
import com.kilivana.products.api.*;
import com.kilivana.support.IntegrationTestSupport;
import com.kilivana.users.domain.*;
import com.kilivana.users.api.UpdateUserVerificationRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InspectorAuthorizationIntegrationTest extends IntegrationTestSupport {
    @Autowired VisitPhotoRepository photos;
    @Autowired InspectionRepository inspections;

    private SignupRequest request(String email, UserRole role) {
        return new SignupRequest(
                email,
                "secretPass1",
                "Test User",
                com.kilivana.support.TestAccounts.nextPhone(),
                "test_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25),
                "Nairobi",
                role,
                "Business",
                "Farm",
                null,
                "employee",
                null,
                null,
                null,
                null);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(body));
    }

    private void verifyAs(AuthResponse admin, AuthResponse inspector, VerificationStatus state) throws Exception {
        mockMvc.perform(authorized(json(patch("/api/v1/users/{id}/verification", inspector.userId()),
                new UpdateUserVerificationRequest(state)), admin.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verificationStatus").value(state.name()));
    }

    @Test
    void adminApprovalAndRevocationEnforceEveryInspectorCapabilityWithExistingTokens() throws Exception {
        AuthResponse farmer = signup(request("farmer@example.com", UserRole.FARMER));
        AuthResponse supplier = signup(request("supplier@example.com", UserRole.SUPPLIER));
        AuthResponse inspector = signup(request("inspector@example.com", UserRole.INSPECTOR));
        AuthResponse other = privilegedSignup(request("other@example.com", UserRole.INSPECTOR));
        AuthResponse admin = readBody(mockMvc.perform(json(post("/api/v1/admins/signup")
                .header("X-Admin-Bootstrap-Key", "test-admin-bootstrap-key"),
                new AdminSignupRequest("admin@example.com", "secretPass1", "Admin", "+254700000099")))
                .andExpect(status().isOk()).andReturn(), AuthResponse.class);
        ProductResponse product = createProduct(farmer.accessToken(), new CreateProductRequest(
                "Produce", "Fresh produce", "kg", BigDecimal.TEN, BigDecimal.TEN, null, null, BigDecimal.ONE));
        CreateInspectionRequest createInspection = new CreateInspectionRequest(product.productId(), null, OffsetDateTime.now().plusDays(1));
        InspectionResponse existing = readBody(mockMvc.perform(authorized(json(post("/api/v1/inspections"), createInspection), other.accessToken()))
                .andExpect(status().isOk()).andReturn(), InspectionResponse.class);
        SiteVisitResponse visit = readBody(mockMvc.perform(authorized(json(post("/api/v1/site-visits"),
                new CreateSiteVisitRequest(product.productId())), farmer.accessToken()))
                .andExpect(status().isOk()).andReturn(), SiteVisitResponse.class);
        // A real photo row lets read/content authorization run before media storage is accessed.
        VisitPhoto photo = photos.save(new VisitPhoto(inspections.findById(existing.id()).orElseThrow(),
                "authorization-test-photo", "photo.jpg", "image/jpeg", 1));

        for (AuthResponse nonAdmin : List.of(inspector, farmer, supplier, other)) {
            mockMvc.perform(authorized(json(patch("/api/v1/users/{id}/verification", inspector.userId()),
                    new UpdateUserVerificationRequest(VerificationStatus.VERIFIED)), nonAdmin.accessToken()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mockMvc.perform(json(patch("/api/v1/users/{id}/verification", inspector.userId()),
                new UpdateUserVerificationRequest(VerificationStatus.VERIFIED))).andExpect(status().isUnauthorized());

        mockMvc.perform(authorized(delete("/api/v1/products/{id}", product.productId()), farmer.accessToken()))
                .andExpect(status().isOk());
        for (VerificationStatus state : List.of(VerificationStatus.PENDING, VerificationStatus.UNVERIFIED)) {
            verifyAs(admin, inspector, state);
            assertDeniedCapabilities(inspector, product.productId(), existing.id(), visit.id(), photo.getId(), farmer.userId(), createInspection);
        }
        verifyAs(admin, inspector, VerificationStatus.VERIFIED);
        // The token was issued while PENDING: database state must govern both approval and revocation.
        mockMvc.perform(authorized(get("/api/v1/products/{id}", product.productId()), inspector.accessToken())).andExpect(status().isOk());
        mockMvc.perform(authorized(get("/api/v1/products/{id}/images", product.productId()), inspector.accessToken())).andExpect(status().isOk());
        AuthResponse login = readBody(mockMvc.perform(json(post("/api/v1/auth/login"),
                new LoginRequest("inspector@example.com", "secretPass1"))).andExpect(status().isOk()).andReturn(), AuthResponse.class);
        assertThat(login.verificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(refresh(inspector.refreshToken()).verificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        mockMvc.perform(authorized(get("/api/v1/auth/me"), inspector.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verificationStatus").value("VERIFIED"));

        // Verification does not waive ownership for results, status, reports or photo uploads.
        for (MockHttpServletRequestBuilder action : List.of(
                json(patch("/api/v1/inspections/{id}/status", existing.id()), new UpdateInspectionStatusRequest(InspectionStatus.IN_PROGRESS)),
                json(post("/api/v1/inspections/{id}/result", existing.id()), new SubmitInspectionResultRequest(InspectionResult.APPROVED, 5, "Good", null)),
                json(post("/api/v1/audit-reports/inspection/{id}", existing.id()), new CreateAuditReportRequest("Good", null)),
                multipart("/api/v1/inspections/{id}/photos", existing.id()).file(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1})))) {
            mockMvc.perform(authorized(action, inspector.accessToken())).andExpect(status().isForbidden());
        }
        InspectionResponse owned = readBody(mockMvc.perform(authorized(json(post("/api/v1/inspections"), createInspection), inspector.accessToken()))
                .andExpect(status().isOk()).andReturn(), InspectionResponse.class);
        mockMvc.perform(authorized(json(patch("/api/v1/inspections/{id}/status", owned.id()),
                new UpdateInspectionStatusRequest(InspectionStatus.IN_PROGRESS)), inspector.accessToken())).andExpect(status().isOk());
        mockMvc.perform(authorized(json(post("/api/v1/inspections/{id}/result", owned.id()),
                new SubmitInspectionResultRequest(InspectionResult.APPROVED, 5, "Good", null)), inspector.accessToken())).andExpect(status().isOk());
        AuditReportResponse report = readBody(mockMvc.perform(authorized(json(post("/api/v1/audit-reports/inspection/{id}", owned.id()),
                new CreateAuditReportRequest("Good", null)), inspector.accessToken())).andExpect(status().isOk()).andReturn(), AuditReportResponse.class);
        mockMvc.perform(authorized(json(post("/api/v1/site-visits/{id}/schedule", visit.id()),
                new ScheduleSiteVisitRequest(OffsetDateTime.now().plusDays(1))), inspector.accessToken())).andExpect(status().isOk());
        mockMvc.perform(authorized(post("/api/v1/site-visits/{id}/complete", visit.id()), inspector.accessToken())).andExpect(status().isOk());
        mockMvc.perform(authorized(json(post("/api/v1/badges/award"), new AwardBadgeRequest(farmer.userId(), "CERTIFIED_FARM", "Verified")),
                inspector.accessToken())).andExpect(status().isOk());
        for (AuthResponse actor : List.of(inspector, farmer, supplier)) {
            mockMvc.perform(authorized(get("/api/v1/audit-reports/{id}", report.id()), actor.accessToken())).andExpect(status().isOk());
            mockMvc.perform(authorized(get("/api/v1/inspections"), actor.accessToken())).andExpect(status().isOk());
        }
        mockMvc.perform(authorized(get("/api/v1/site-visits/{id}", visit.id()), farmer.accessToken())).andExpect(status().isOk());
        mockMvc.perform(authorized(get("/api/v1/inspections/{id}/photos", existing.id()), farmer.accessToken())).andExpect(status().isOk());
        mockMvc.perform(authorized(get("/api/v1/products/{id}", product.productId()), farmer.accessToken())).andExpect(status().isOk());
        verifyAs(admin, inspector, VerificationStatus.PENDING);
        assertDeniedCapabilities(inspector, product.productId(), owned.id(), visit.id(), photo.getId(), farmer.userId(), createInspection);
        mockMvc.perform(authorized(get("/api/v1/audit-reports/{id}", report.id()), inspector.accessToken())).andExpect(status().isForbidden());
    }

    private void assertDeniedCapabilities(AuthResponse inspector, UUID product, UUID inspection, UUID visit, UUID photo,
            UUID recipient, CreateInspectionRequest createInspection) throws Exception {
        for (MockHttpServletRequestBuilder action : List.of(
                json(post("/api/v1/inspections"), createInspection),
                get("/api/v1/inspections"), get("/api/v1/inspections").param("productId", product.toString()),
                get("/api/v1/inspections/{id}", inspection),
                json(patch("/api/v1/inspections/{id}/status", inspection), new UpdateInspectionStatusRequest(InspectionStatus.IN_PROGRESS)),
                json(post("/api/v1/inspections/{id}/result", inspection), new SubmitInspectionResultRequest(InspectionResult.APPROVED, 5, "Good", null)),
                get("/api/v1/site-visits"), get("/api/v1/site-visits/{id}", visit),
                json(post("/api/v1/site-visits/{id}/schedule", visit), new ScheduleSiteVisitRequest(OffsetDateTime.now().plusDays(1))),
                post("/api/v1/site-visits/{id}/complete", visit), post("/api/v1/site-visits/{id}/reject", visit),
                json(post("/api/v1/audit-reports/inspection/{id}", inspection), new CreateAuditReportRequest("Report", null)),
                get("/api/v1/audit-reports/inspection/{id}", inspection), get("/api/v1/audit-reports/{id}", UUID.randomUUID()),
                get("/api/v1/inspections/{id}/photos", inspection), get("/api/v1/photos/{id}", photo), get("/api/v1/photos/{id}/content", photo),
                multipart("/api/v1/inspections/{id}/photos", inspection).file(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1})),
                json(post("/api/v1/badges/award"), new AwardBadgeRequest(recipient, "CERTIFIED_FARM", "Note")))) {
            mockMvc.perform(authorized(action, inspector.accessToken()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        for (String suffix : List.of("", "/images")) {
            mockMvc.perform(authorized(get("/api/v1/products/" + product + suffix), inspector.accessToken()))
                    .andExpect(status().isNotFound());
        }
    }
}
