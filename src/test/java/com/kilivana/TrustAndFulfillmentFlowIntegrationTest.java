package com.kilivana;

import com.kilivana.auth.api.AuthResponse;
import com.kilivana.auth.api.SignupRequest;
import com.kilivana.addresses.api.AddressResponse;
import com.kilivana.addresses.api.CreateAddressRequest;
import com.kilivana.logistics.api.CreateLogisticsJobRequest;
import com.kilivana.logistics.api.LogisticsJobResponse;
import com.kilivana.logistics.api.SubmitProofOfDeliveryRequest;
import com.kilivana.logistics.api.UpdateLogisticsJobStatusRequest;
import com.kilivana.logistics.domain.LogisticsJobStatus;
import com.kilivana.badges.api.AwardBadgeRequest;
import com.kilivana.badges.api.BadgeResponse;
import com.kilivana.badges.api.UserBadgeResponse;
import com.kilivana.inspection.api.CreateInspectionRequest;
import com.kilivana.inspection.api.InspectionResponse;
import com.kilivana.inspection.api.SubmitInspectionResultRequest;
import com.kilivana.inspection.api.UpdateInspectionStatusRequest;
import com.kilivana.inspection.domain.InspectionResult;
import com.kilivana.inspection.domain.InspectionStatus;
import com.kilivana.products.api.CreateProductRequest;
import com.kilivana.products.api.ProductResponse;
import com.kilivana.orders.api.CreateOrderRequest;
import com.kilivana.orders.api.OrderResponse;
import com.kilivana.orders.domain.OrderStatus;
import com.kilivana.support.IntegrationTestSupport;
import com.kilivana.users.domain.UserRole;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TrustAndFulfillmentFlowIntegrationTest extends IntegrationTestSupport {

    @Test
    void inspectorShouldAwardBadgeAndBadgesShouldBePubliclyVisible() throws Exception {
        AuthResponse farmer = signup(farmerSignup("farmer-badge@example.com", "Blue Ridge Farm", "Nyeri"));
        AuthResponse inspector = privilegedSignup(inspectorSignup("inspector-badge@example.com", "INS-001"));

        List<BadgeResponse> publicBadges = readBody(
                mockMvc.perform(get("/api/v1/badges"))
                        .andExpect(status().isOk())
                        .andReturn(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
        assertThat(publicBadges).extracting(BadgeResponse::code)
                .contains("QUALITY_HARVEST", "TRUSTED_SUPPLIER", "CERTIFIED_FARM");

        UserBadgeResponse awarded = readBody(
                mockMvc.perform(authorized(post("/api/v1/badges/award"), inspector.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(new AwardBadgeRequest(
                                        farmer.userId(),
                                        "CERTIFIED_FARM",
                                        "Farm verified on site"))))
                        .andExpect(status().isOk())
                        .andReturn(), UserBadgeResponse.class);

        assertThat(awarded.badgeCode()).isEqualTo("CERTIFIED_FARM");

        List<UserBadgeResponse> farmerBadges = readBody(
                mockMvc.perform(get("/api/v1/badges/user/{userId}", farmer.userId()))
                        .andExpect(status().isOk())
                        .andReturn(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
        assertThat(farmerBadges).extracting(UserBadgeResponse::badgeCode).containsExactly("CERTIFIED_FARM");

        mockMvc.perform(authorized(post("/api/v1/badges/award"), inspector.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new AwardBadgeRequest(
                                farmer.userId(),
                                "CERTIFIED_FARM",
                                "Duplicate award"))))
                .andExpect(status().isConflict());
    }

    @Test
    void passedInspectionShouldAutoAwardQualityHarvestBadge() throws Exception {
        AuthResponse farmer = signup(farmerSignup("farmer-inspect@example.com", "Sunny Acres", "Muranga"));
        AuthResponse inspector = privilegedSignup(inspectorSignup("inspector-inspect@example.com", "INS-002"));

        ProductResponse listing = createProduct(
                farmer.accessToken(),
                new CreateProductRequest(
                        "Avocados",
                        "Hass avocados",
                        "kg",
                        new BigDecimal("85.00"),
                        new BigDecimal("60.00"),
                        null,
                        null,
                        new BigDecimal("1.00")));

        InspectionResponse scheduled = readBody(
                mockMvc.perform(authorized(post("/api/v1/inspections"), inspector.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(new CreateInspectionRequest(
                                        listing.productId(),
                                        null,
                                        OffsetDateTime.now().plusDays(1)))))
                        .andExpect(status().isOk())
                        .andReturn(), InspectionResponse.class);

        InspectionResponse inProgress = updateInspectionStatus(
                inspector.accessToken(),
                scheduled.id(),
                new UpdateInspectionStatusRequest(InspectionStatus.IN_PROGRESS));
        assertThat(inProgress.status()).isEqualTo(InspectionStatus.IN_PROGRESS);

        InspectionResponse passed = submitInspectionResult(
                inspector.accessToken(),
                scheduled.id(),
                new SubmitInspectionResultRequest(
                        InspectionResult.APPROVED,
                        5,
                        "Produce meets quality standards",
                        OffsetDateTime.now().plusMonths(3)));
        assertThat(passed.status()).isEqualTo(InspectionStatus.PASSED);
        assertThat(passed.result()).isEqualTo(InspectionResult.APPROVED);
        assertThat(passed.rating()).isEqualTo(5);
        assertThat(passed.performedAt()).isNotNull();

        List<UserBadgeResponse> farmerBadges = readBody(
                mockMvc.perform(get("/api/v1/badges/user/{userId}", farmer.userId()))
                        .andExpect(status().isOk())
                        .andReturn(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
        assertThat(farmerBadges).extracting(UserBadgeResponse::badgeCode).contains("QUALITY_HARVEST");

        List<InspectionResponse> sellerView = readBody(
                mockMvc.perform(authorized(get("/api/v1/inspections?productId={id}", listing.productId()), farmer.accessToken()))
                        .andExpect(status().isOk())
                        .andReturn(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
        assertThat(sellerView).hasSize(1).allMatch(inspection -> inspection.status() == InspectionStatus.PASSED);
    }

    @Test
    void sellerShouldArrangeAndTrackDeliveryForConfirmedOrder() throws Exception {
        AuthResponse farmer = signup(farmerSignup("farmer-delivery@example.com", "Green Pastures", "Trans Nzoia"));
        AuthResponse supplier = signup(supplierSignup("supplier-delivery@example.com", "Kisumu Foods"));
        AuthResponse driver = privilegedSignup(driverSignup("driver-delivery@example.com"));

        ProductResponse listing = createProduct(
                farmer.accessToken(),
                new CreateProductRequest(
                        "Potatoes",
                        "Irish potatoes",
                        "kg",
                        new BigDecimal("40.00"),
                        new BigDecimal("200.00"),
                        null,
                        null,
                        new BigDecimal("1.00")));

        OrderResponse order = createOrder(
                supplier.accessToken(),
                new CreateOrderRequest(listing.productId(), new BigDecimal("50.00"), "KES"));

        OrderResponse confirmed = updateOrderStatus(farmer.accessToken(), order.orderId(), OrderStatus.CONFIRMED);
        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);

        AddressResponse pickup = createAddress(
                farmer.accessToken(),
                new CreateAddressRequest("Farm", "Green Pastures, Trans Nzoia", 1.0582, 34.9416, true));
        AddressResponse delivery = createAddress(
                supplier.accessToken(),
                new CreateAddressRequest("Shop", "Kisumu Foods, Kisumu", -0.0917, 34.7680, false));

        LogisticsJobResponse created = readBody(
                mockMvc.perform(authorized(post("/api/v1/logistics/jobs"), farmer.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(new CreateLogisticsJobRequest(
                                        order.orderId(),
                                        "Handle with care",
                                        OffsetDateTime.now().plusDays(2),
                                        pickup.addressId(),
                                        delivery.addressId()))))
                        .andExpect(status().isOk())
                        .andReturn(), LogisticsJobResponse.class);
        assertThat(created.status()).isEqualTo(LogisticsJobStatus.PENDING_ACCEPTANCE);

        LogisticsJobResponse accepted = updateJobStatus(driver.accessToken(), created.jobId(), LogisticsJobStatus.ACCEPTED);
        assertThat(accepted.driverId()).isNotNull();
        updateJobStatus(driver.accessToken(), created.jobId(), LogisticsJobStatus.AT_PICKUP);
        updateJobStatus(driver.accessToken(), created.jobId(), LogisticsJobStatus.PICKED_UP);
        updateJobStatus(driver.accessToken(), created.jobId(), LogisticsJobStatus.IN_TRANSIT);

        mockMvc.perform(authorized(post("/api/v1/logistics/jobs/{jobId}/proof-of-delivery", created.jobId()), driver.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new SubmitProofOfDeliveryRequest(
                                "pod.jpg", "image/jpeg", "Jane Otieno"))))
                .andExpect(status().isOk());

        LogisticsJobResponse delivered = updateJobStatus(driver.accessToken(), created.jobId(), LogisticsJobStatus.DELIVERED);
        assertThat(delivered.status()).isEqualTo(LogisticsJobStatus.DELIVERED);
        assertThat(delivered.podSubmittedAt()).isNotNull();

        LogisticsJobResponse buyerView = readBody(
                mockMvc.perform(authorized(get("/api/v1/logistics/jobs/order/{orderId}", order.orderId()), supplier.accessToken()))
                        .andExpect(status().isOk())
                        .andReturn(), LogisticsJobResponse.class);
        assertThat(buyerView.jobId()).isEqualTo(created.jobId());
        assertThat(buyerView.status()).isEqualTo(LogisticsJobStatus.DELIVERED);
    }

    private InspectionResponse updateInspectionStatus(
            String accessToken,
            java.util.UUID inspectionId,
            UpdateInspectionStatusRequest request) throws Exception {
        return readBody(mockMvc.perform(authorized(
                        patch("/api/v1/inspections/{id}/status", inspectionId), accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn(), InspectionResponse.class);
    }

    private InspectionResponse submitInspectionResult(
            String accessToken,
            java.util.UUID inspectionId,
            SubmitInspectionResultRequest request) throws Exception {
        return readBody(mockMvc.perform(authorized(
                        post("/api/v1/inspections/{id}/result", inspectionId), accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn(), InspectionResponse.class);
    }

    private LogisticsJobResponse updateJobStatus(
            String accessToken,
            java.util.UUID jobId,
            LogisticsJobStatus statusValue) throws Exception {
        return readBody(mockMvc.perform(authorized(
                        patch("/api/v1/logistics/jobs/{jobId}/status", jobId), accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new UpdateLogisticsJobStatusRequest(statusValue))))
                .andExpect(status().isOk())
                .andReturn(), LogisticsJobResponse.class);
    }

    private AddressResponse createAddress(String accessToken, CreateAddressRequest request) throws Exception {
        return readBody(mockMvc.perform(authorized(post("/api/v1/addresses"), accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn(), AddressResponse.class);
    }

    private SignupRequest farmerSignup(String email, String farmName, String farmLocation) {
        return new SignupRequest(
                email,
                "secretPass1",
                "Farmer User",
                "+254700000030",
                "test_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25),
                farmLocation,
                UserRole.FARMER,
                null,
                farmName,
                farmLocation,
                null,
                null,
                null,
                null,
                null);
    }

    private SignupRequest supplierSignup(String email, String businessName) {
        return new SignupRequest(
                email,
                "secretPass1",
                "Supplier User",
                "+254700000040",
                "test_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25),
                "Nairobi",
                UserRole.SUPPLIER,
                businessName,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private SignupRequest inspectorSignup(String email, String employeeCode) {
        return new SignupRequest(
                email,
                "secretPass1",
                "Inspector User",
                "+254700000550",
                "test_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25),
                "Nairobi",
                UserRole.INSPECTOR,
                null,
                null,
                null,
                employeeCode,
                null,
                null,
                null,
                null);
    }

    private SignupRequest driverSignup(String email) {
        return new SignupRequest(
                email,
                "secretPass1",
                "Driver User",
                "+254700000540",
                "test_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25),
                "Nairobi",
                UserRole.DRIVER,
                null,
                null,
                null,
                null,
                "KL-2026-DRIVER",
                "Pickup",
                "KDK 123K",
                "Western Kenya");
    }
}