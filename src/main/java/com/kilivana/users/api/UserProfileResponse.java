package com.kilivana.users.api;

import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import com.kilivana.users.domain.VerificationStatus;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

public record UserProfileResponse(
        UUID userId,
        String email,
        @Schema(minLength = 3, maxLength = 30, pattern = "[a-z][a-z0-9_]{2,29}") String username,
        String region,
        String fullName,
        String phoneNumber,
        UserRole role,
        UserStatus status,
        VerificationStatus verificationStatus,
        String businessName,
        String farmName,
        String farmLocation,
        String employeeCode,
        String licenseNumber,
        String vehicleType,
        String vehiclePlate,
        String serviceArea) {
}
