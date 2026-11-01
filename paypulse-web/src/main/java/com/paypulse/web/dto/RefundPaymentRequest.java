package com.paypulse.web.dto;

public record RefundPaymentRequest(
    Long amount,
    String reason
) {}
