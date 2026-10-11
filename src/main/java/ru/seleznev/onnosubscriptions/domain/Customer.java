package ru.seleznev.onnosubscriptions.domain;

import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;

import java.time.LocalDate;
import java.util.List;

@Catalog(name = "Customers", title = "Клиенты", codePrefix = "C-", context = "Subscriptions")
@AccessControl(readRoles = "ADMIN", writeRoles = "ADMIN")
@Getter
@Setter
public class Customer extends CatalogObject implements Validated {

    @Attribute(displayName = "Статус", required = true)
    private CustomerStatus status = CustomerStatus.ACTIVE;

    @Attribute(displayName = "Электронная почта", length = 254, email = true)
    private String email;

    @Attribute(displayName = "Телефон", length = 32)
    private String phone;

    @Attribute(displayName = "Дата регистрации", required = true)
    private LocalDate registrationDate = LocalDate.now();

    @Override
    public List<BusinessRule> rules() {
        return List.of(BusinessRule.onField("description", "Укажите имя клиента",
                () -> getDescription() != null && !getDescription().isBlank()));
    }
}
