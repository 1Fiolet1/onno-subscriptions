package ru.seleznev.onnosubscriptions.ui;

import org.springframework.stereotype.Component;
import ru.seleznev.onnosubscriptions.domain.CustomerAccount;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

@Component
public class CustomerAccountView implements EntityView<CustomerAccount> {
    @Override
    public Class<CustomerAccount> entity() {
        return CustomerAccount.class;
    }

    @Override
    public void list(ListSpec<CustomerAccount> list) {
        list.columns(CustomerAccount::getCustomer, CustomerAccount::getAmount)
                .label(CustomerAccount::getCustomer, "Клиент")
                .label(CustomerAccount::getAmount, "Сумма");
    }

    @Override
    public void fields(EntityConfigBuilder<CustomerAccount> fields) {
        fields.icon("landmark");
        fields.field(CustomerAccount::getCustomer).order(10).width("half");
        fields.field(CustomerAccount::getAmount).order(20).width("half").format("currency:RUB")
                .hint("Остаток доступных средств клиента. Меняется при проведении документов.");
        fields.field(CustomerAccount::getPeriod).label("Дата движения").format("dd.MM.yyyy HH:mm");
    }
}
