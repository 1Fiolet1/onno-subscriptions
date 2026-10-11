package ru.seleznev.onnosubscriptions.repository;

import org.springframework.stereotype.Repository;
import ru.seleznev.onnosubscriptions.domain.Subscription;
import su.onno.repository.DocumentRepository;

@Repository
public interface SubscriptionRepository extends DocumentRepository<Subscription> {
}
