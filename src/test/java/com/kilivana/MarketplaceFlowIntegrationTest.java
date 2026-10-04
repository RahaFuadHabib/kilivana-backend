package com.kilivana;

import com.kilivana.auth.api.AuthResponse;
import com.kilivana.auth.api.SignupRequest;
import com.kilivana.common.api.ApiError;
import com.kilivana.products.api.CreateProductRequest;
import com.kilivana.products.api.ProductResponse;
import com.kilivana.orders.api.CreateOrderRequest;
import com.kilivana.orders.api.OrderResponse;
import com.kilivana.orders.domain.OrderStatus;
import com.kilivana.payments.api.CreatePaymentRequest;
import com.kilivana.payments.api.PaymentResponse;
import com.kilivana.payments.domain.PaymentStatus;
import com.kilivana.support.IntegrationTestSupport;
import com.kilivana.users.domain.UserRole;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MarketplaceFlowIntegrationTest extends IntegrationTestSupport {

    @Test
    void supplierShouldOrderCropAndFarmerShouldVerifyPayment() throws Exception {
        AuthResponse farmer = signup(farmerSignup("farmer@example.com", "North Field", "Nakuru"));
        AuthResponse supplier = signup(supplierSignup("supplier@example.com", "Harvest Hub"));

        ProductResponse cropListing = createProduct(
                farmer.accessToken(),
                new CreateProductRequest(
                        "Maize",
                        "Dry maize ready for pickup",
                        "kg",
                        new BigDecimal("42.50"),
                        new BigDecimal("100.00"),
                        null,
                        null,
                        new BigDecimal("1.00")));

        OrderResponse order = createOrder(
                supplier.accessToken(),
                new CreateOrderRequest(cropListing.productId(), new BigDecimal("25.00"), "KES"));

        List<ProductResponse> listings = browseProducts();
        assertThat(listings).singleElement()
                .extracting(ProductResponse::availableQuantity)
                .isEqualTo(new BigDecimal("75.00"));

        PaymentResponse initiatedPayment = createPayment(
                supplier.accessToken(),
                new CreatePaymentRequest(order.orderId(), "MPESA", "crop-payment-1"));
        PaymentResponse verifiedPayment = verifyPayment(farmer.accessToken(), "crop-payment-1");
        PaymentResponse verifiedAgain = verifyPayment(farmer.accessToken(), "crop-payment-1");

        assertThat(initiatedPayment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(verifiedPayment.status()).isEqualTo(PaymentStatus.VERIFIED);
        assertThat(verifiedAgain.status()).isEqualTo(PaymentStatus.VERIFIED);
    }

    @Test
    void cancellationShouldRestoreReservedListingQuantity() throws Exception {
        AuthResponse farmer = signup(farmerSignup("farmer2@example.com", "Sunrise Farm", "Eldoret"));
        AuthResponse supplier = signup(supplierSignup("supplier2@example.com", "Fresh Chain"));

        ProductResponse cropListing = createProduct(
                farmer.accessToken(),
                new CreateProductRequest(
                        "Beans",
                        "Red beans harvest",
                        "kg",
                        new BigDecimal("30.00"),
                        new BigDecimal("40.00"),
                        null,
                        null,
                        new BigDecimal("1.00")));

        OrderResponse order = createOrder(
                supplier.accessToken(),
                new CreateOrderRequest(cropListing.productId(), new BigDecimal("10.00"), "KES"));

        OrderResponse cancelled = cancelOrder(supplier.accessToken(), order.orderId());

        List<ProductResponse> listings = browseProducts();
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(listings).singleElement()
                .extracting(ProductResponse::availableQuantity)
                .isEqualTo(new BigDecimal("40.00"));
    }

    @Test
    void duplicatePaymentKeyForDifferentOrdersShouldConflict() throws Exception {
        AuthResponse farmer = signup(farmerSignup("farmer3@example.com", "River Farm", "Kitale"));
        AuthResponse supplier = signup(supplierSignup("supplier3@example.com", "Market Link"));

        ProductResponse cropListing = createProduct(
                farmer.accessToken(),
                new CreateProductRequest(
                        "Coffee",
                        "Arabica beans",
                        "kg",
                        new BigDecimal("90.00"),
                        new BigDecimal("50.00"),
                        null,
                        null,
                        new BigDecimal("1.00")));

        OrderResponse firstOrder = createOrder(
                supplier.accessToken(),
                new CreateOrderRequest(cropListing.productId(), new BigDecimal("5.00"), "KES"));
        OrderResponse secondOrder = createOrder(
                supplier.accessToken(),
                new CreateOrderRequest(cropListing.productId(), new BigDecimal("3.00"), "KES"));

        createPayment(
                supplier.accessToken(),
                new CreatePaymentRequest(firstOrder.orderId(), "MPESA", "shared-key"));

        MvcResult result = mockMvc.perform(authorized(post("/api/v1/payments"), supplier.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new CreatePaymentRequest(secondOrder.orderId(), "MPESA", "shared-key"))))
                .andExpect(status().isConflict())
                .andReturn();

        ApiError error = readBody(result, ApiError.class);
        assertThat(error.code()).isEqualTo("BUSINESS_CONFLICT");
        assertThat(error.message()).contains("Idempotency key");
    }

    private SignupRequest farmerSignup(String email, String farmName, String farmLocation) {
        return new SignupRequest(
                email,
                "secretPass1",
                "Farmer User",
                "+254700000010",
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
                "+254700000020",
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
}
