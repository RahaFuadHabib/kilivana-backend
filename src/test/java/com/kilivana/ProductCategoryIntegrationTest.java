package com.kilivana;

import com.kilivana.auth.api.SignupRequest;
import com.kilivana.products.api.*;
import com.kilivana.support.IntegrationTestSupport;
import com.kilivana.support.TestAccounts;
import com.kilivana.users.domain.UserRole;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProductCategoryIntegrationTest extends IntegrationTestSupport {
    @Autowired JdbcTemplate jdbc;
    private static final UUID GRAINS = UUID.fromString("c1200000-0000-4000-8000-000000000001");
    private static final UUID FRUITS = UUID.fromString("c1200000-0000-4000-8000-000000000004");

    private SignupRequest account(UserRole role) {
        String key = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return new SignupRequest(key + "@test.com", "secretPass1", "Seller", TestAccounts.nextPhone(),
                "seller_" + key, "Nakuru", role, role == UserRole.SUPPLIER ? "Supplier" : null,
                role == UserRole.FARMER ? "Farm" : null, role == UserRole.FARMER ? "Nakuru" : null,
                null, null, null, null, null);
    }

    private CreateProductRequest request(UUID category) {
        return new CreateProductRequest("Harvest", "Fresh harvest", "kg", BigDecimal.TEN, BigDecimal.TEN,
                category, BigDecimal.valueOf(2), BigDecimal.ONE);
    }

    private UUID category(String sector, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into categories (id,version,created_at,updated_at,name,sector,active) values (?,0,now(),now(),?,?,?)",
                id, "Test " + id, sector, active);
        return id;
    }

    @Test void mineReturnsFruitsAfterAssignmentWithExistingImages() throws Exception {
        var actor = signup(account(UserRole.FARMER));
        var other = signup(account(UserRole.FARMER));
        createProduct(other.accessToken(), request(FRUITS));
        var product = createProduct(actor.accessToken(), request(null));
        String imageUrl = "/api/v1/products/" + product.productId() + "/images/example/content";
        mockMvc.perform(authorized(post("/api/v1/products/{id}/images", product.productId()), actor.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(new RegisterProductImageRequest(
                        "avocado.jpg", "image/jpeg", 189449, imageUrl))))
                .andExpect(status().isOk());
        mockMvc.perform(authorized(get("/api/v1/products/mine"), actor.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].categoryId").doesNotExist());
        mockMvc.perform(authorized(put("/api/v1/products/{id}", product.productId()), actor.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\":\"" + FRUITS + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(authorized(get("/api/v1/products/mine"), actor.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(product.productId().toString()))
                .andExpect(jsonPath("$[0].sellerId").value(actor.userId().toString()))
                .andExpect(jsonPath("$[0].categoryId").value(FRUITS.toString()))
                .andExpect(jsonPath("$[0].categoryName").value("Fruits"))
                .andExpect(jsonPath("$[0].sector").value("FARM_PRODUCE"))
                .andExpect(jsonPath("$[0].images[0].url").value(imageUrl))
                .andExpect(jsonPath("$[0].images[0].fileName").value("avocado.jpg"));
    }

    @Test void seededCategoriesAreAvailableThroughPublicEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/categories").param("sector", "FARM_PRODUCE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[*].sector", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("FARM_PRODUCE"))))
                .andExpect(jsonPath("$[*].active", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(true))));
    }

    @Test void farmerAcceptsMatchingCategoryAndLegacyNull() throws Exception {
        var actor = signup(account(UserRole.FARMER));
        assertThat(createProduct(actor.accessToken(), request(GRAINS)).categoryId()).isEqualTo(GRAINS);
        assertThat(createProduct(actor.accessToken(), request(null)).categoryId()).isNull();
    }

    @Test void invalidSelectionsReturnExistingClientErrorsOnCreateAndUpdate() throws Exception {
        var actor = signup(account(UserRole.FARMER));
        var product = createProduct(actor.accessToken(), request(GRAINS));
        UUID wrong = category("AGRI_INPUTS", true);
        UUID inactive = category("FARM_PRODUCE", false);
        UUID missing = UUID.randomUUID();
        try {
            for (UUID id : new UUID[] {wrong, inactive, missing}) {
                int expected = id.equals(missing) ? 404 : 409;
                String code = id.equals(missing) ? "NOT_FOUND" : "BUSINESS_CONFLICT";
                mockMvc.perform(authorized(post("/api/v1/products"), actor.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(request(id))))
                        .andExpect(status().is(expected)).andExpect(jsonPath("$.code").value(code));
                mockMvc.perform(authorized(put("/api/v1/products/{id}", product.productId()), actor.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\":\"" + id + "\"}"))
                        .andExpect(status().is(expected)).andExpect(jsonPath("$.code").value(code));
                assertThat(jdbc.queryForObject("select category_id from products where id=?", UUID.class, product.productId())).isEqualTo(GRAINS);
            }
        } finally { jdbc.update("delete from categories where id in (?,?)", wrong, inactive); }
    }

    @Test void supplierCannotSelectFarmCategoryButAcceptsInputCategory() throws Exception {
        var actor = signup(account(UserRole.SUPPLIER));
        UUID input = category("AGRI_INPUTS", true);
        try {
            var product = createProduct(actor.accessToken(), request(input));
            mockMvc.perform(authorized(post("/api/v1/products"), actor.accessToken())
                    .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(request(GRAINS))))
                    .andExpect(status().isConflict());
            mockMvc.perform(authorized(put("/api/v1/products/{id}", product.productId()), actor.accessToken())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\":\"" + GRAINS + "\"}"))
                    .andExpect(status().isConflict());
        } finally { jdbc.update("update products set category_id=null where category_id=?", input); jdbc.update("delete from categories where id=?", input); }
    }

    @Test void partialUpdatesPreserveOmittedAndNullFieldsAndValidateReplacement() throws Exception {
        var actor = signup(account(UserRole.FARMER));
        var original = createProduct(actor.accessToken(), request(GRAINS));
        String route = "/api/v1/products/" + original.productId();
        var updated = readBody(mockMvc.perform(authorized(put(route), actor.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"  New title  \"}"))
                .andExpect(status().isOk()).andReturn(), ProductResponse.class);
        assertThat(updated.name()).isEqualTo("New title");
        assertThat(updated.categoryId()).isEqualTo(GRAINS);
        assertThat(updated.description()).isEqualTo(original.description());
        assertThat(updated.unit()).isEqualTo(original.unit());
        assertThat(updated.unitPrice()).isEqualByComparingTo(original.unitPrice());
        assertThat(updated.availableQuantity()).isEqualByComparingTo(original.availableQuantity());
        assertThat(updated.lowStockThreshold()).isEqualByComparingTo(original.lowStockThreshold());
        assertThat(updated.minimumOrderQuantity()).isEqualByComparingTo(original.minimumOrderQuantity());
        var unchanged = readBody(mockMvc.perform(authorized(put(route), actor.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\":null,\"title\":null}"))
                .andExpect(status().isOk()).andReturn(), ProductResponse.class);
        assertThat(unchanged).isEqualTo(updated);
        var replaced = readBody(mockMvc.perform(authorized(put(route), actor.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\":\"" + FRUITS + "\"}"))
                .andExpect(status().isOk()).andReturn(), ProductResponse.class);
        assertThat(replaced.categoryId()).isEqualTo(FRUITS);
        var legacy = createProduct(actor.accessToken(), request(null));
        mockMvc.perform(authorized(put("/api/v1/products/{id}", legacy.productId()), actor.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.categoryId").doesNotExist());
    }
}
