package com.kilivana.products.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Partial update: omitted or null fields preserve their current values, including categoryId.
 * Clearing a category or low-stock threshold is not represented by this request.
 */
public record UpdateProductRequest(
        @Size(max = 150) String title,
        @Size(max = 1000) String description,
        @Size(max = 30) String unit,
        @DecimalMin("0.01") BigDecimal unitPrice,
        @DecimalMin("0") BigDecimal availableQuantity,
        UUID categoryId,
        @DecimalMin("0") BigDecimal lowStockThreshold,
        @DecimalMin("0.01") BigDecimal minimumOrderQuantity) {
}
