package ru.seleznev.onnosubscriptions.ui;

import org.springframework.stereotype.Component;
import ru.seleznev.onnosubscriptions.domain.*;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;

@Component
public class SubscriptionsDashboard implements Page {
    @Override public String route() { return "/dashboard"; }

    @Override
    public void compose(PageBuilder page) {
        page.title("Обзор подписок").subtitle("Платежи и выручка за период; остатки на текущий момент.");
        page.widget("Период отчёта").type("timeRange").width("full").order(0)
                .config("presets", "7d,30d,90d,all").config("default", "30d");
        page.widget("Поступления за период").type("stat").document(Payment.class)
                .metricField(Payment::getAmount).dateField("_date").width("1/3").order(10)
                .config("metric", "sum").config("trend", "false").config("filter", "_posted = true")
                .config("currency", "RUB").config("locale", "ru-RU");
        page.widget("Выручка за период").type("stat").register(Revenue.class)
                .metricField(Revenue::getAmount).dateField("_period").width("1/3").order(20)
                .config("metric", "sum").config("trend", "false").config("currency", "RUB").config("locale", "ru-RU");
        page.widget("Остатки клиентов сейчас").type("count").register(CustomerAccount.class)
                .metricField(CustomerAccount::getAmount).width("1/3").order(30)
                .config("metric", "sum").config("currency", "RUB").config("locale", "ru-RU")
                .hint("Текущий остаток всех лицевых счетов; не ограничивается отчётным периодом.");
        page.widget("Выручка по тарифам").type("chart").register(Revenue.class)
                .metricField(Revenue::getAmount).dateField("_period").config("groupBy", "tariff_display")
                .width("1/2").order(40).config("kind", "bar").config("metric", "sum")
                .config("currency", "RUB").config("locale", "ru-RU");
        page.widget("Выручка по клиентам").type("chart").register(Revenue.class)
                .metricField(Revenue::getAmount).dateField("_period").config("groupBy", "customer_display")
                .width("1/2").order(50).config("kind", "bar").config("metric", "sum")
                .config("currency", "RUB").config("locale", "ru-RU");
        page.widget("Последние подписки").type("list").document(Subscription.class)
                .dateField("_date").titleField("_number").maxItems(5).width("1/2").order(60)
                .config("secondaryField", "customerDisplay").config("currency", "RUB").config("locale", "ru-RU");
        page.widget("Последние платежи").type("list").document(Payment.class)
                .dateField("_date").titleField("_number").maxItems(5).width("1/2").order(70)
                .config("secondaryField", "customerDisplay").config("currency", "RUB").config("locale", "ru-RU");
    }
}
