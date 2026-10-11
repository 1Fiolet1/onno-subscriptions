package ru.seleznev.onnosubscriptions.repository;

import org.springframework.stereotype.Repository;
import ru.seleznev.onnosubscriptions.domain.Customer;
import su.onno.repository.CatalogRepository;

@Repository
public interface CustomerRepository extends CatalogRepository<Customer> {
}
