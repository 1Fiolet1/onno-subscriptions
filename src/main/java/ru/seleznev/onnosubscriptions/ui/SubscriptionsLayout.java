package ru.seleznev.onnosubscriptions.ui;

import org.springframework.stereotype.Component;
import ru.seleznev.onnosubscriptions.domain.Customer;
import ru.seleznev.onnosubscriptions.domain.CustomerAccount;
import ru.seleznev.onnosubscriptions.domain.Payment;
import ru.seleznev.onnosubscriptions.domain.Subscription;
import ru.seleznev.onnosubscriptions.domain.Revenue;
import ru.seleznev.onnosubscriptions.domain.Tariff;
import su.onno.ui.Layout;
import su.onno.ui.LayoutSpec;
import su.onno.ui.NavStyle;

@Component
public class SubscriptionsLayout implements Layout {
    @Override
    public void configure(LayoutSpec spec) {
        spec.shell().nav(NavStyle.SIDEBAR).brand("Учёт подписок");
        spec.section("Работа с клиентами").order(10).icon("users")
                .page("/dashboard", "Обзор", "layout-dashboard")
                .catalog(Customer.class).document(Subscription.class).document(Payment.class)
                .register(CustomerAccount.class, "landmark").register(Revenue.class, "chart-no-axes-combined");
        spec.section("Настройка тарифов").order(20).icon("tags").catalog(Tariff.class);
    }
}
