package ru.seleznev.onnosubscriptions.domain;

import lombok.Getter;
import lombok.Setter;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.lifecycle.OnFillingHandler;
import su.onno.lifecycle.Postable;
import su.onno.model.DocumentObject;
import su.onno.posting.PostingContext;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;
import su.onno.types.Ref;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Document(name = "Payments", title = "Платежи", numberPrefix = "PAY-", context = "Subscriptions")
@AccessControl(readRoles = "ADMIN", writeRoles = "ADMIN")
@Getter
@Setter
public class Payment extends DocumentObject implements OnFillingHandler, Validated, Postable {

    @Attribute(displayName = "Клиент", required = true)
    private Ref<Customer> customer;

    @Attribute(displayName = "Сумма", required = true, precision = 15, scale = 2, min = 0.01)
    private BigDecimal amount = new BigDecimal("0.00");

    @Attribute(displayName = "Способ оплаты", required = true)
    private PaymentMethod paymentMethod = PaymentMethod.CARD;

    public Payment() {
        setDate(LocalDateTime.now().withNano(0));
    }

    @Override
    public void onFilling() {
        if (getDate() == null) {
            setDate(LocalDateTime.now().withNano(0));
        }
        if (paymentMethod == null) {
            paymentMethod = PaymentMethod.CARD;
        }
    }

    @Override
    public List<BusinessRule> rules() {
        return List.of(
                BusinessRule.onField("customer", "Выберите клиента", () -> customer != null),
                BusinessRule.onField("amount", "Сумма платежа должна быть больше нуля",
                        () -> amount != null && amount.signum() > 0),
                BusinessRule.onField("paymentMethod", "Выберите способ оплаты", () -> paymentMethod != null));
    }

    @Override
    public void handlePosting(PostingContext context) {
        context.movements(CustomerAccount.class).addReceipt(movement -> {
            movement.setCustomer(customer);
            movement.setAmount(amount);
        });
    }
}
