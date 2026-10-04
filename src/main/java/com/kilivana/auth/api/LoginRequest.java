package com.kilivana.auth.api;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank @Size(max = 120) String identifier,
        @NotBlank String password) {
}
