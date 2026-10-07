package com.kilivana;

import com.kilivana.auth.api.SignupRequest;
import com.kilivana.products.api.*;
import com.kilivana.products.repository.ProductImageRepository;
import com.kilivana.support.IntegrationTestSupport;
import com.kilivana.support.TestAccounts;
import com.kilivana.users.domain.UserRole;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProductImageUploadIntegrationTest extends IntegrationTestSupport {
    @Autowired ProductImageRepository images;
    @Autowired com.kilivana.products.service.ProductImageService imageService;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.kilivana.media.config.MediaProperties mediaProperties;
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");

    SignupRequest farmer(String email) {
        return new SignupRequest(email, "secretPass1", "Farmer", TestAccounts.nextPhone(),
                "farmer_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                "Nakuru", UserRole.FARMER, null, "Farm", "Nakuru", null, null, null, null, null);
    }

    @Test void databaseRollbackRemovesFileAndMetadata() throws Exception {
        var owner = signup(farmer("photos-rollback@test.com"));
        var product = createProduct(owner.accessToken(), new CreateProductRequest("Beans", "Harvest",
                "kg", BigDecimal.TEN, BigDecimal.TEN, null, null, BigDecimal.ONE));
        var actor = new com.kilivana.security.AuthenticatedUser(product.sellerId(), "photos-rollback@test.com", "", UserRole.FARMER);
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                actor, null, actor.getAuthorities()));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        try {
            java.nio.file.Path path = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .execute(transaction -> {
                        var response = imageService.upload(product.productId(),
                                new MockMultipartFile("file", "client.png", "image/png", PNG));
                        var image = images.findById(response.imageId()).orElseThrow();
                        var storedPath = java.nio.file.Path.of(mediaProperties.effectiveRootDir()).resolve(image.getStorageKey());
                        assertThat(storedPath).exists();
                        transaction.setRollbackOnly();
                        return storedPath;
                    });
            assertThat(path).doesNotExist();
            assertThat(images.count()).isZero();
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    @Test void realUploadMetadataDeliveryOwnershipVisibilityAndLimit() throws Exception {
        var owner = signup(farmer("photos-owner@test.com"));
        var other = signup(farmer("photos-other@test.com"));
        var product = createProduct(owner.accessToken(), new CreateProductRequest("Maize", "Harvest",
                "bags", BigDecimal.TEN, BigDecimal.TEN, null, null, BigDecimal.ONE));
        String route = "/api/v1/products/" + product.productId() + "/images/upload";
        var file = new MockMultipartFile("file", "client.png", "image/png", PNG);
        mockMvc.perform(multipart(route).file(file)).andExpect(status().isUnauthorized());
        mockMvc.perform(authorized(multipart(route).file(file), other.accessToken())).andExpect(status().isForbidden());
        mockMvc.perform(authorized(multipart(route).file(new MockMultipartFile("file", "fake.png", "image/png",
                "not an image".getBytes())), owner.accessToken())).andExpect(status().isBadRequest());
        assertThat(images.count()).isZero();
        var response = readBody(mockMvc.perform(authorized(multipart(route).file(file), owner.accessToken()))
                .andExpect(status().isOk()).andReturn(), ProductImageResponse.class);
        var image = images.findById(response.imageId()).orElseThrow();
        assertThat(image.getStorageKey()).endsWith("/" + response.fileName());
        assertThat(image.getFileName()).isNotEqualTo("client.png");
        assertThat(image.getSizeBytes()).isEqualTo(PNG.length);
        assertThat(image.getContentType()).isEqualTo("image/png");
        assertThat(image.getUrl()).isEqualTo(response.url());
        mockMvc.perform(get(response.url())).andExpect(status().isOk())
                .andExpect(content().contentType("image/png")).andExpect(content().bytes(PNG));
        for (int i = 1; i < 9; i++) {
            mockMvc.perform(authorized(multipart(route).file(file), owner.accessToken())).andExpect(status().isOk());
        }
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<Integer> concurrentUpload = () -> {
            start.await();
            return mockMvc.perform(authorized(multipart(route).file(file), owner.accessToken()))
                    .andReturn().getResponse().getStatus();
        };
        try {
            var first = executor.submit(concurrentUpload);
            var second = executor.submit(concurrentUpload);
            start.countDown();
            assertThat(java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally { executor.shutdownNow(); }
        mockMvc.perform(authorized(multipart(route).file(file), owner.accessToken())).andExpect(status().isConflict());
        mockMvc.perform(authorized(post("/api/v1/products/" + product.productId() + "/images"), owner.accessToken())
                .contentType("application/json")
                .content("{\"fileName\":\"external.png\",\"contentType\":\"image/png\",\"sizeBytes\":8,\"url\":\"https://example.com/image.png\"}"))
                .andExpect(status().isConflict());
        assertThat(images.count()).isEqualTo(10);
        mockMvc.perform(authorized(delete("/api/v1/products/" + product.productId()), owner.accessToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get(response.url())).andExpect(status().isNotFound());
        mockMvc.perform(authorized(get(response.url()), other.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(authorized(get(response.url()), owner.accessToken())).andExpect(status().isOk());
    }
}
