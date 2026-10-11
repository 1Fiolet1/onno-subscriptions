package ru.seleznev.onnosubscriptions.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "PaymentMethods", title = "Способы оплаты")
public enum PaymentMethod {
    @EnumLabel("Карта")
    CARD,

    @EnumLabel("Наличные")
    CASH,

    @EnumLabel("Банковский перевод")
    BANK_TRANSFER
}
