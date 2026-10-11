package ru.seleznev.onnosubscriptions.domain;

import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.Attribute;
import su.onno.model.TabularSectionRow;
import su.onno.types.Ref;

import java.math.BigDecimal;

@Getter
@Setter
public class SubscriptionLine extends TabularSectionRow {
    @Attribute(displayName = "Тариф", required = true)
    private Ref<Tariff> tariff;

    @Attribute(displayName = "Периодов", required = true, min = 1)
    private Integer periods = 1;

    @Attribute(displayName = "Цена за период", precision = 15, scale = 2)
    private BigDecimal price;

    @Attribute(displayName = "Сумма", precision = 15, scale = 2)
    private BigDecimal amount = new BigDecimal("0.00");
}
