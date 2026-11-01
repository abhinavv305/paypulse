package com.paypulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.paypulse")
@EnableScheduling
@EnableJpaRepositories(basePackages = {
    "com.paypulse.domain.repository",
    "com.paypulse.idempotency.repository",
    "com.paypulse.outbox.repository",
    "com.paypulse.webhook.repository",
    "com.paypulse.reconciliation.repository"
})
public class PayPulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayPulseApplication.class, args);
    }
}
