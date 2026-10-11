package ru.seleznev.onnosubscriptions.ui;

import org.springframework.stereotype.Component;
import ru.seleznev.onnosubscriptions.domain.Customer;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

@Component
public class CustomerView implements EntityView<Customer> {
    @Override
    public Class<Customer> entity() {
        return Customer.class;
    }

    @Override
    public void list(ListSpec<Customer> list) {
        list.columns(Customer::getDescription, Customer::getStatus,
                        Customer::getEmail, Customer::getPhone, Customer::getRegistrationDate)
                .label(Customer::getDescription, "Клиент")
                .label(Customer::getStatus, "Статус")
                .label(Customer::getEmail, "Электронная почта")
                .label(Customer::getPhone, "Телефон")
                .label(Customer::getRegistrationDate, "Регистрация")
                .sortBy(Customer::getDescription);
        list.filter(Customer::getStatus).label("Статус").multiOptions();
    }

    @Override
    public void fields(EntityConfigBuilder<Customer> fields) {
        fields.icon("users");
        fields.field(Customer::getDescription).label("Имя клиента").order(10).width("full")
                .placeholder("Иван Иванов").hint("Имя человека или название организации.");
        fields.field(Customer::getCode).label("Код").order(20).width("half")
                .hint("Если оставить пустым, код присвоится автоматически.");
        fields.field(Customer::getStatus).order(30).width("half")
                .hint("Статус клиента в справочнике.");
        fields.field(Customer::getEmail).order(40).width("half")
                .placeholder("client@example.com").hint("Контактная почта; поле необязательное.");
        fields.field(Customer::getPhone).order(50).width("half")
                .placeholder("+7 900 000-00-00").hint("Контактный телефон; поле необязательное.");
        fields.field(Customer::getRegistrationDate).order(60).width("half").format("dd.MM.yyyy")
                .hint("При создании подставляется сегодняшняя дата.");
    }
}
