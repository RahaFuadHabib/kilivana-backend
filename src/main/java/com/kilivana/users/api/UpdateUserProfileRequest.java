package com.kilivana.users.api;

import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import java.util.Locale;

public record UpdateUserProfileRequest(
        @Size(max = 100) String fullName,
        @Size(max = 40) String phoneNumber,
        @Schema(description = "Trimmed and lowercased using Locale.ROOT; globally unique; 3-30 characters starting with a lowercase ASCII letter, followed by lowercase ASCII letters, digits or underscore.")
        @Size(min = 3, max = 30) @Pattern(regexp = "[a-z][a-z0-9_]{2,29}") String username,
        @Size(max = 200) String region,
        @Size(max = 150) String businessName,
        @Size(max = 150) String farmName,
        @Schema(deprecated = true, description = "Farmer compatibility alias for region. If both are supplied, their trimmed values must match.")
        @Size(max = 200) String farmLocation,
        @Size(max = 100) String vehicleType,
        @Size(max = 50) String vehiclePlate,
        @Size(max = 150) String serviceArea) {
    public UpdateUserProfileRequest {
        username = username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }
}
