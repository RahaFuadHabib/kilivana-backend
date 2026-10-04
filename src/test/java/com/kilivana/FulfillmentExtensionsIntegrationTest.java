package com.kilivana;

import com.kilivana.auth.api.AuthResponse;
import com.kilivana.auth.api.SignupRequest;
import com.kilivana.addresses.api.AddressResponse;
import com.kilivana.addresses.api.CreateAddressRequest;
import com.kilivana.logistics.api.CreateLogisticsJobRequest;
import com.kilivana.logistics.api.LogisticsJobResponse;
import com.kilivana.logistics.api.TrackingEventRequest;
import com.kilivana.logistics.api.TrackingEventResponse;
import com.kilivana.logistics.domain.LogisticsJobStatus;
import com.kilivana.inspection.api.AuditReportResponse;
import com.kilivana.inspection.api.CreateAuditReportRequest;
import com.kilivana.inspection.api.CreateInspectionRequest;
import com.kilivana.inspection.api.CreateSiteVisitRequest;
import com.kilivana.inspection.api.InspectionResponse;
import com.kilivana.inspection.api.ScheduleSiteVisitRequest;
import com.kilivana.inspection.api.SiteVisitResponse;
import com.kilivana.inspection.api.SubmitInspectionResultRequest;
import com.kilivana.inspection.api.UpdateInspectionStatusRequest;
import com.kilivana.inspection.domain.InspectionResult;
import com.kilivana.inspection.domain.InspectionStatus;
import com.kilivana.inspection.domain.SiteVisitStatus;
import com.kilivana.products.api.CreateProductRequest;
import com.kilivana.products.api.ProductResponse;
import com.kilivana.messages.api.ContactMessageRequest;
import com.kilivana.messages.api.ContactMessageResponse;
import com.kilivana.messages.domain.ContactMessageStatus;
import com.kilivana.orders.api.CreateOrderRequest;
import com.kilivana.orders.api.OrderResponse;
import com.kilivana.orders.domain.OrderStatus;
import com.kilivana.payments.api.CreatePaymentRequest;
import com.kilivana.payments.api.PaymentResponse;
import com.kilivana.payments.domain.PaymentStatus;
import com.kilivana.paymentgateway.api.PaymentWebhookRequest;
import com.kilivana.paymentgateway.api.WebhookEventType;
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

class FulfillmentExtensionsIntegrationTest extends IntegrationTestSupport {

    @Test
    void contactMessageShouldBePubliclySubmittableAndAdminResolvable() throws Exception {
        ContactMessageResponse message = readBody(
                mockMvc.perform(post("/api/v1/contact-messages")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(new ContactMessageRequest(
                                        "Jane Doe",
                                        "jane@example.com",
                                        "Inquiry",
                                        "How does inspection work?"))))
                        .andExpect(status().isOk())
                        .andReturn(), ContactMessageResponse.class);

        assertThat(message.status()).isEqualTo(ContactMessageStatus.OPEN);

        mockMvc.perform(get("/api/v1/contact-messages")
                        .header("Authorization", "Bearer " + signup(new SignupRequest(
                "peer@example.com",
                "secretPass1",
                "Peer User",
                "+254700000060",
                "test_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25),
                "Nairobi",
                UserRole.BUYER,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null)).accessToken()))
                .andExpect(status().isForbidden());

        AuthResponse admin = adminSignup("admin-cm@kilivana.example");

        List<ContactMessageResponse> messages = readBody(
                mockMvc.perform(authorized(get("/api/v1/contact-messages"), admin.accessToken()))
                        .andExpect(status().isOk())
                        .andReturn(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
        assertThat(messages).extracting(ContactMessageResponse::subject).contains("Inquiry");

        ContactMessageResponse resolved = readBody(
                mockMvc.perform(authorized(patch("/api/v1/contact-messages/{messageId}/resolve", message.id()), admin.accessToken()))
                        .andExpect(status().isOk())
                        .andReturn(), ContactMessageResponse.class);
        assertThat(resolved.status()).isEqualTo(ContactMessageStatus.RESOLVED);
    }

    @Test
    void farmerShouldRequestAndInspectorScheduleAndCompleteSiteVisit() throws Exception {
        AuthResponse farmer = signup(farmerSignup("farmer-visit@example.com", "Nexus Farm", "Kitui"));
        AuthResponse inspector = privilegedSignup(inspectorSignup("inspector-visit@example.com", "INS-010"));

        ProductResponse listing = createProduct(
                farmer.accessToken(),
                new CreateProductRequest(
                        "Millet",
                        "Dry millet",
                        "kg",
                        new BigDecimal("38.00"),
                        new BigDecimal("80.00"),
                        null,
                        null,
                        new BigDecimal("1.00")));

        SiteVisitResponse requested = readBody(
                mockMvc.perform(authorized(post("/api/v1/site-visits"), farmer.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(new CreateSiteVisitRequest(listing.productId()))))
                        .andExpect(status().isOk())
                        .andReturn(), SiteVisitResponse.class);
        assertThat(requested.status()).isEqualTo(SiteVisitStatus.REQUESTED);

        SiteVisitResponse scheduled = readBody(
                mockMvc.perform(authorized(post("/api/v1/site-visits/{visitId}/schedule", requested.id()), inspector.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(
                                        new ScheduleSiteVisitRequest(OffsetDateTime.now().plusDays(3)))))
                        .andExpect(status().isOk())
                        .andReturn(), SiteVisitResponse.class);
        assertThat(scheduled.status()).isEqualTo(SiteVisitStatus.SCHEDULED);
        assertThat(scheduled.scheduledAt()).isNotNull();

        SiteVisitResponse completed = readBody(
                mockMvc.perform(authorized(post("/api/v1/site-visits/{visitId}/complete", requested.id()), inspector.accessToken()))
                        .andExpect(status().isOk())
                        .andReturn(), SiteVisitResponse.class);
        assertThat(completed.status()).isEqualTo(SiteVisitStatus.COMPLETED);

        List<SiteVisitResponse> farmerVisits = readBody(
                mockMvc.perform(authorized(get("/api/v1/site-visits"), farmer.accessToken()))
                        .andExpect(status().isOk())
                        .andReturn(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
        assertThat(farmerVisits).singleElement()
                .extracting(SiteVisitResponse::status)
                .isEqualTo(SiteVisitStatus.COMPLETED);
    }

    @Test
    void auditorShouldIssueAuditReportForConcludedInspection() throws Exception {
        AuthResponse farmer = signup(farmerSignup("farmer-audit@example.com", "Timber Farm", "Kakamega"));
        AuthResponse inspector = privilegedSignup(inspectorSignup("inspector-audit@example.com", "INS-011"));

        ProductResponse listing = createProduct(
                farmer.accessToken(),
                new CreateProductRequest(
                        "Tea",
                        "Green tea leaves",
                        "kg",
                        new BigDecimal("55.00"),
                        new BigDecimal("120.00"),
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

        mockMvc.perform(authorized(patch("/api/v1/inspections/{id}/status", scheduled.id()), inspector.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new UpdateInspectionStatusRequest(
                                InspectionStatus.IN_PROGRESS))))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(post("/api/v1/inspections/{id}/result", scheduled.id()), inspector.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new SubmitInspectionResultRequest(
                                InspectionResult.APPROVED, 5, "Fully compliant", null))))
                .andExpect(status().isOk());

        AuditReportResponse report = readBody(
                mockMvc.perform(authorized(post("/api/v1/audit-reports/inspection/{inspectionId}", scheduled.id()), inspector.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(new CreateAuditReportRequest(
                                        "Inspection passed and produce verified",
                                        "Re-certify annually"))))
                        .andExpect(status().isOk())
                        .andReturn(), AuditReportResponse.class);

        assertThat(report.reportNumber()).startsWith("AR-");
        assertThat(report.inspectionId()).isEqualTo(scheduled.id());
        assertThat(report.productId()).isEqualTo(listing.productId());

        AuditReportResponse farmerView = readBody(
                mockMvc.perform(authorized(get("/api/v1/audit-reports/inspection/{inspectionId}", scheduled.id()), farmer.accessToken()))
                        .andExpect(status().isOk())
                        .andReturn(), AuditReportResponse.class);
        assertThat(farmerView.reportNumber()).isEqualTo(report.reportNumber());
    }

    @Test
    void sellerShouldLogAndParticipantsViewTrackingEvents() throws Exception {
        AuthResponse farmer = signup(farmerSignup("farmer-log@example.com", "Coast Farm", "Kilifi"));
        AuthResponse supplier = signup(supplierSignup("supplier-log@example.com", "Mombasa Traders"));

        ProductResponse listing = createProduct(
                farmer.accessToken(),
                new CreateProductRequest(
                        "Mangoes",
                        "Fresh mangoes",
                        "kg",
                        new BigDecimal("60.00"),
                        new BigDecimal("90.00"),
                        null,
                        null,
                        new BigDecimal("1.00")));

        OrderResponse order = createOrder(
                supplier.accessToken(),
                new CreateOrderRequest(listing.productId(), new BigDecimal("20.00"), "KES"));
        updateOrderStatus(farmer.accessToken(), order.orderId(), OrderStatus.CONFIRMED);

        AddressResponse pickup = createAddress(
                farmer.accessToken(),
                new CreateAddressRequest("Farm", "Coast Farm, Kilifi", -3.5106, 39.9093, true));
        AddressResponse delivery = createAddress(
                supplier.accessToken(),
                new CreateAddressRequest("Shop", "Mombasa Traders, Mombasa", -4.0435, 39.6682, false));

        LogisticsJobResponse created = readBody(
                mockMvc.perform(authorized(post("/api/v1/logistics/jobs"), farmer.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(new CreateLogisticsJobRequest(
                                        order.orderId(),
                                        "Keep cold",
                                        OffsetDateTime.now().plusDays(1),
                                        pickup.addressId(),
                                        delivery.addressId()))))
                        .andExpect(status().isOk())
                        .andReturn(), LogisticsJobResponse.class);
        assertThat(created.status()).isEqualTo(LogisticsJobStatus.PENDING_ACCEPTANCE);

        TrackingEventResponse event = readBody(
                mockMvc.perform(authorized(post("/api/v1/logistics/jobs/{jobId}/tracking", created.jobId()), farmer.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(new TrackingEventRequest(
                                        LogisticsJobStatus.PENDING_ACCEPTANCE,
                                        -3.5106,
                                        39.9093,
                                        "Kilifi Depot",
                                        "Package awaiting driver pickup"))))
                        .andExpect(status().isOk())
                        .andReturn(), TrackingEventResponse.class);
        assertThat(event.status()).isEqualTo(LogisticsJobStatus.PENDING_ACCEPTANCE);
        assertThat(event.locationName()).isEqualTo("Kilifi Depot");

        List<TrackingEventResponse> buyerEvents = readBody(
                mockMvc.perform(authorized(get("/api/v1/logistics/jobs/{jobId}/tracking", created.jobId()), supplier.accessToken()))
                        .andExpect(status().isOk())
                        .andReturn(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
        assertThat(buyerEvents).singleElement()
                .extracting(TrackingEventResponse::status)
                .isEqualTo(LogisticsJobStatus.PENDING_ACCEPTANCE);
    }

    @Test
    void gatewayWebhookShouldVerifyPendingPayment() throws Exception {
        AuthResponse farmer = signup(farmerSignup("farmer-web@example.com", "Hill Farm", "Kericho"));
        AuthResponse supplier = signup(supplierSignup("supplier-web@example.com", "Western Foods"));

        ProductResponse listing = createProduct(
                farmer.accessToken(),
                new CreateProductRequest(
                        "Coffee",
                        "Robusta beans",
                        "kg",
                        new BigDecimal("95.00"),
                        new BigDecimal("30.00"),
                        null,
                        null,
                        new BigDecimal("1.00")));

        OrderResponse order = createOrder(
                supplier.accessToken(),
                new CreateOrderRequest(listing.productId(), new BigDecimal("10.00"), "KES"));

        PaymentResponse payment = createPayment(
                supplier.accessToken(),
                new CreatePaymentRequest(order.orderId(), "MPESA", "webhook-payment-1"));

        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.externalId()).isNotBlank();
        assertThat(payment.gatewayReference()).startsWith("MPESA-");

        mockMvc.perform(post("/api/v1/webhooks/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new PaymentWebhookRequest(
                                WebhookEventType.VERIFIED,
                                payment.externalId(),
                                payment.gatewayReference()))))
                .andExpect(status().isAccepted());

        List<PaymentResponse> outbound = readBody(
                mockMvc.perform(authorized(get("/api/v1/payments/outbound"), supplier.accessToken()))
                        .andExpect(status().isOk())
                        .andReturn(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
        assertThat(outbound).singleElement()
                .extracting(PaymentResponse::status)
                .isEqualTo(PaymentStatus.VERIFIED);
    }

    private AddressResponse createAddress(String accessToken, CreateAddressRequest request) throws Exception {
        return readBody(mockMvc.perform(authorized(post("/api/v1/addresses"), accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn(), AddressResponse.class);
    }

    private AuthResponse adminSignup(String email) throws Exception {
        return readBody(mockMvc.perform(post("/api/v1/admins/signup")
                        .header("X-Admin-Bootstrap-Key", "test-admin-bootstrap-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new com.kilivana.admin.api.AdminSignupRequest(email, "secretPass1", "Root", "+254700000099"))))
                .andExpect(status().isOk())
                .andReturn(), AuthResponse.class);
    }

    private SignupRequest farmerSignup(String email, String farmName, String farmLocation) {
        return new SignupRequest(
                email,
                "secretPass1",
                "Farmer User",
                "+254700000070",
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
                "+254700000080",
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
                "+254700000090",
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
}