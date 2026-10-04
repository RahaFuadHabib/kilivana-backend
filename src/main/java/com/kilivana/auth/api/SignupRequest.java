package com.kilivana.auth.api;

import com.kilivana.users.domain.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import java.util.Locale;

public record SignupRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 100) String fullName,
        @NotBlank @Size(max = 40) String phoneNumber,
        @Schema(description = "Globally unique; trimmed and lowercased using Locale.ROOT. 3-30 characters; first character is a lowercase ASCII letter, remaining characters are lowercase ASCII letters, digits or underscore. Not a login identifier.")
        @NotBlank @Size(min = 3, max = 30) @Pattern(regexp = "[a-z][a-z0-9_]{2,29}") String username,
        @Schema(description = "Required for every public role; trimmed, nonblank region of at most 200 characters.")
        @NotBlank @Size(max = 200) String region,
        @Schema(implementation = String.class, allowableValues = {"BUYER", "FARMER", "SUPPLIER", "INSPECTOR"},
                description = "Public role. Inspector accounts enter PENDING verification and require Admin approval for Inspector privileges.")
        @NotNull UserRole role,
        @Size(max = 150) String businessName,
        @Size(max = 150) String farmName,
        @Schema(deprecated = true, description = "Optional Farmer compatibility field; must match region when supplied.")
        @Size(max = 200) String farmLocation,
        @Schema(description = "Required and nonblank for INSPECTOR only; maximum 100 characters; trimmed before storage. No uniqueness or format requirement.")
        @Size(max = 100) String employeeCode,
        @Size(max = 100) String licenseNumber,
        @Size(max = 100) String vehicleType,
        @Size(max = 50) String vehiclePlate,
        @Size(max = 150) String serviceArea) {
    public SignupRequest {
        username = username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }
}
