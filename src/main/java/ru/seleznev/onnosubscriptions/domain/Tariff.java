package ru.seleznev.onnosubscriptions.domain;

import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;

import java.math.BigDecimal;
import java.util.List;

@Catalog(name = "Tariffs", title = "Тарифы", codePrefix = "T-", context = "Subscriptions")
@AccessControl(readRoles = "ADMIN", writeRoles = "ADMIN")
@Getter
@Setter
public class Tariff extends CatalogObject implements Validated {

    @Attribute(displayName = "Цена за период", required = true, precision = 15, scale = 2, min = 0)
    private BigDecimal price = new BigDecimal("0.00");

    @Attribute(displayName = "Длительность периода, дней", required = true, min = 1)
    private Integer periodDays = 30;

    @Attribute(displayName = "Доступен для подключения", required = true)
    private boolean available = true;

    @Override
    public List<BusinessRule> rules() {
        return List.of(BusinessRule.onField("description", "Укажите название тарифа",
                () -> getDescription() != null && !getDescription().isBlank()));
    }
}
