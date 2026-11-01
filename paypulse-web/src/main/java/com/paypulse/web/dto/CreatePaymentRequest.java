package com.paypulse.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreatePaymentRequest(
    @NotNull(message = "amount is required")
    @Min(value = 1, message = "amount must be greater than 0")
    Long amount,

    @NotBlank(message = "currency is required")
    @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO code")
    String currency,

    String provider,

    String captureMethod,

    PaymentMethodDto paymentMethod,

    CustomerDto customer,

    String description
) {
    public record PaymentMethodDto(
        @NotBlank String type,
        String token
    ) {}

    public record CustomerDto(
        String email,
        String name
    ) {}
}
