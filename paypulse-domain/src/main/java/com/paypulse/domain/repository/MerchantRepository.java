package com.paypulse.domain.repository;

import com.paypulse.domain.model.Merchant;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface MerchantRepository extends Repository<Merchant, UUID> {

    Merchant save(Merchant merchant);

    Optional<Merchant> findById(UUID id);

    Optional<Merchant> findByIdAndActiveTrue(UUID id);
}
