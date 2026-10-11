package ru.seleznev.onnosubscriptions.domain;

import lombok.Getter;
import lombok.Setter;
import ru.seleznev.onnosubscriptions.service.SubscriptionLifecycle;
import ru.seleznev.onnosubscriptions.support.ApplicationBeans;
import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.lifecycle.BeforePostHandler;
import su.onno.lifecycle.Postable;
import su.onno.lifecycle.OnFillingHandler;
import su.onno.model.DocumentObject;
import su.onno.posting.PostingContext;
import su.onno.rules.BusinessRule;
import su.onno.rules.Validated;
import su.onno.types.Ref;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(name = "Subscriptions", title = "Подписки", numberPrefix = "SUB-", context = "Subscriptions")
@AccessControl(readRoles = "ADMIN", writeRoles = "ADMIN")
@Getter
@Setter
public class Subscription extends DocumentObject implements OnFillingHandler, BeforeWriteHandler,
        BeforePostHandler, Validated, Postable {
    @Attribute(displayName = "Клиент", required = true)
    private Ref<Customer> customer;

    @Attribute(displayName = "Статус", required = true)
    private SubscriptionStatus status = SubscriptionStatus.DRAFT;

    @Attribute(displayName = "Дата начала", required = true)
    private LocalDate startDate = LocalDate.now();

    @Attribute(displayName = "Дата окончания")
    private LocalDate endDate;

    @Attribute(displayName = "Итог", precision = 15, scale = 2)
    private BigDecimal total;

    @Attribute(displayName = "Причина отмены", length = 1000)
    private String cancellationReason;

    @org.springframework.data.annotation.Transient
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean cancellationCommand;

    public void cancelWithReason(String reason) {
        status = SubscriptionStatus.CANCELLED;
        cancellationReason = reason;
        cancellationCommand = true;
    }

    @TabularSection(name = "items")
    private List<SubscriptionLine> items = new ArrayList<>();

    public Subscription() {
        setDate(LocalDateTime.now().withNano(0));
    }

    @Override
    public void onFilling() {
        if (getDate() == null) setDate(LocalDateTime.now().withNano(0));
        if (startDate == null) startDate = LocalDate.now();
        if (status == null) status = SubscriptionStatus.DRAFT;
    }

    @Override
    public void beforeWrite() {
        if (cancellationCommand) return;
        ApplicationBeans.get(SubscriptionLifecycle.class).recalculate(this);
    }

    @Override
    public List<BusinessRule> rules() {
        if (cancellationCommand) return List.of(BusinessRule.onField("cancellationReason",
                "Укажите причину отмены (до 1000 символов)",
                () -> cancellationReason != null && !cancellationReason.isBlank() && cancellationReason.length() <= 1000));
        return ApplicationBeans.get(SubscriptionLifecycle.class).rules(this);
    }

    @Override
    public void beforePost() {
        ApplicationBeans.get(SubscriptionLifecycle.class).checkFunds(this);
    }

    @Override
    public void handlePosting(PostingContext context) {
        if (status == SubscriptionStatus.CANCELLED) return;
        context.movements(CustomerAccount.class).addExpense(movement -> {
            movement.setCustomer(customer);
            movement.setAmount(total);
        });
        var revenue = context.movements(Revenue.class);
        for (SubscriptionLine line : items) {
            revenue.addReceipt(movement -> {
                movement.setTariff(line.getTariff());
                movement.setCustomer(customer);
                movement.setAmount(line.getAmount());
                movement.setPeriods(line.getPeriods());
            });
        }
    }
}
