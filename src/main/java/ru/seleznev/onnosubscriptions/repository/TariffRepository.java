package ru.seleznev.onnosubscriptions.repository;

import org.springframework.stereotype.Repository;
import ru.seleznev.onnosubscriptions.domain.Tariff;
import su.onno.repository.CatalogRepository;

@Repository
public interface TariffRepository extends CatalogRepository<Tariff> {
}
