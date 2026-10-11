package ru.seleznev.onnosubscriptions.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "CustomerStatuses", title = "Статусы клиентов")
public enum CustomerStatus {
    @EnumLabel(value = "Активен", color = "#059669")
    ACTIVE,

    @EnumLabel(value = "Неактивен", color = "#6B7280")
    INACTIVE
}
