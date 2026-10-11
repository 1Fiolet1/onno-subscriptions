package ru.seleznev.onnosubscriptions.ui;

import org.springframework.stereotype.Component;
import ru.seleznev.onnosubscriptions.domain.Tariff;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

@Component
public class TariffView implements EntityView<Tariff> {
    @Override
    public Class<Tariff> entity() {
        return Tariff.class;
    }

    @Override
    public void list(ListSpec<Tariff> list) {
        list.columns(Tariff::getCode, Tariff::getDescription, Tariff::getPrice,
                        Tariff::getPeriodDays, Tariff::isAvailable)
                .label(Tariff::getCode, "Код")
                .label(Tariff::getDescription, "Тариф")
                .label(Tariff::getPrice, "Цена за период")
                .label(Tariff::getPeriodDays, "Длительность, дней")
                .label(Tariff::isAvailable, "Доступен")
                .sortBy(Tariff::getDescription);
    }

    @Override
    public void fields(EntityConfigBuilder<Tariff> fields) {
        fields.icon("tags");
        fields.field(Tariff::getDescription).label("Название тарифа").order(10).width("full")
                .placeholder("Базовый").hint("Название, которое будет показано при выборе тарифа.");
        fields.field(Tariff::getCode).label("Код").order(20).width("half")
                .hint("Если оставить пустым, код присвоится автоматически.");
        fields.field(Tariff::getPrice).order(30).width("half").format("currency:RUB")
                .hint("Стоимость одного периода в рублях. Нулевая цена допустима.");
        fields.field(Tariff::getPeriodDays).order(40).width("half").format("integer")
                .hint("Количество дней в одном периоде, минимум 1.");
        fields.field(Tariff::isAvailable).order(50).width("half").widget("switch")
                .hint("Определяет, можно ли подключить тариф в новой подписке.");
    }
}
