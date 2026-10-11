package ru.seleznev.onnosubscriptions.ui;

import org.springframework.stereotype.Component;
import ru.seleznev.onnosubscriptions.domain.Revenue;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

@Component
public class RevenueView implements EntityView<Revenue> {
    @Override
    public Class<Revenue> entity() {
        return Revenue.class;
    }

    @Override
    public void list(ListSpec<Revenue> list) {
        list.columns(Revenue::getTariff, Revenue::getCustomer, Revenue::getAmount, Revenue::getPeriods)
                .label(Revenue::getTariff, "Тариф").label(Revenue::getCustomer, "Клиент")
                .label(Revenue::getAmount, "Выручка").label(Revenue::getPeriods, "Периоды");
    }

    @Override
    public void fields(EntityConfigBuilder<Revenue> fields) {
        fields.icon("chart-no-axes-combined");
        fields.field(Revenue::getTariff).order(10).width("half");
        fields.field(Revenue::getCustomer).order(20).width("half");
        fields.field(Revenue::getAmount).order(30).width("half").format("currency:RUB")
                .hint("Сумма строк проведённых подписок за выбранный период.");
        fields.field(Revenue::getPeriods).order(40).width("half").format("integer")
                .hint("Количество оплаченных периодов тарифа.");
        fields.field(Revenue::getPeriod).label("Дата движения").format("dd.MM.yyyy HH:mm");
    }
}
