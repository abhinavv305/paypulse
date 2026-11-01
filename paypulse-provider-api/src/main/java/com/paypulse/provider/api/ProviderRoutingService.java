package com.paypulse.provider.api;

import com.paypulse.provider.api.resilience.ResilientPaymentGateway;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ProviderRoutingService {

    private final Map<ProviderId, ResilientPaymentGateway> gateways = new EnumMap<>(ProviderId.class);

    public ProviderRoutingService(List<PaymentProviderGateway> gatewayList) {
        if (gatewayList != null) {
            for (PaymentProviderGateway gateway : gatewayList) {
                if (gateway instanceof ResilientPaymentGateway resilient) {
                    gateways.put(resilient.getProviderId(), resilient);
                } else {
                    gateways.put(gateway.getProviderId(), ResilientPaymentGateway.wrap(gateway));
                }
            }
        }
    }

    public PaymentProviderGateway getGateway(ProviderId providerId) {
        PaymentProviderGateway gateway = gateways.get(providerId);
        if (gateway == null) {
            throw new IllegalArgumentException("No payment gateway registered for provider: " + providerId);
        }
        return gateway;
    }

    public Optional<PaymentProviderGateway> findGateway(ProviderId providerId) {
        return Optional.ofNullable(gateways.get(providerId));
    }

    public Optional<ResilientPaymentGateway> findResilientGateway(ProviderId providerId) {
        return Optional.ofNullable(gateways.get(providerId));
    }
}
