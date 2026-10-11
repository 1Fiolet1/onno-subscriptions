package ru.seleznev.onnosubscriptions.domain;

import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.AccessControl;
import su.onno.annotations.AccumulationRegister;
import su.onno.annotations.Dimension;
import su.onno.annotations.Resource;
import su.onno.model.AccumulationRecord;
import su.onno.model.AccumulationType;
import su.onno.types.Ref;

import java.math.BigDecimal;

@AccumulationRegister(name = "Revenue", title = "Выручка", type = AccumulationType.TURNOVER,
        context = "Subscriptions")
@AccessControl(readRoles = "ADMIN")
@Getter
@Setter
public class Revenue extends AccumulationRecord {
    @Dimension(displayName = "Тариф")
    private Ref<Tariff> tariff;

    @Dimension(displayName = "Клиент")
    private Ref<Customer> customer;

    @Resource(displayName = "Сумма", precision = 15, scale = 2)
    private BigDecimal amount;

    @Resource(displayName = "Периоды")
    private Integer periods;
}
