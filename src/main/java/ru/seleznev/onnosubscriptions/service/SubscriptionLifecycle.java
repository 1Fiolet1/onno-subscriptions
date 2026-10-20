package ru.seleznev.onnosubscriptions.service;

import org.springframework.stereotype.Service;
import ru.seleznev.onnosubscriptions.domain.Subscription;
import ru.seleznev.onnosubscriptions.domain.SubscriptionStatus;
import ru.seleznev.onnosubscriptions.domain.CustomerAccount;
import ru.seleznev.onnosubscriptions.domain.SubscriptionLine;
import ru.seleznev.onnosubscriptions.domain.Tariff;
import ru.seleznev.onnosubscriptions.repository.CustomerRepository;
import ru.seleznev.onnosubscriptions.repository.CustomerAccountRepository;
import ru.seleznev.onnosubscriptions.repository.TariffRepository;
import ru.seleznev.onnosubscriptions.repository.SubscriptionRepository;
import su.onno.rules.BusinessRule;
import su.onno.types.Ref;
import su.onno.validation.ValidationException;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

@Service
public class SubscriptionLifecycle {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999.99");
    private final TariffRepository tariffs;
    private final CustomerRepository customers;
    private final SubscriptionRepository subscriptions;
    private final CustomerAccountRepository accounts;

    public SubscriptionLifecycle(TariffRepository tariffs, CustomerRepository customers,
                                 SubscriptionRepository subscriptions, CustomerAccountRepository accounts) {
        this.tariffs = tariffs;
        this.customers = customers;
        this.subscriptions = subscriptions;
        this.accounts = accounts;
    }

    public void checkFunds(Subscription subscription) {
        if (subscription.getStatus() == SubscriptionStatus.CANCELLED
                || subscription.getCustomer() == null || !validAmount(subscription.getTotal())) return;
        BigDecimal available = accounts.getBalance(filter -> filter
                        .where(CustomerAccount::getCustomer, subscription.getCustomer())).stream()
                .map(CustomerAccount::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (available.compareTo(subscription.getTotal()) < 0) {
            throw new ValidationException("Недостаточно средств на лицевом счёте клиента: доступно "
                    + available.toPlainString() + " ₽, требуется " + subscription.getTotal().toPlainString() + " ₽");
        }
    }

    public void recalculate(Subscription subscription) {
        BigDecimal total = new BigDecimal("0.00");
        long maxDays = 0;
        Map<Ref<Tariff>, BigDecimal> prices = savedPrices(subscription);
        if (subscription.getItems() != null) {
            for (SubscriptionLine line : subscription.getItems()) {
                if (line == null) continue;
                Optional<Tariff> tariff = tariff(line);
                BigDecimal price = line.getTariff() == null ? BigDecimal.ZERO
                        : prices.computeIfAbsent(line.getTariff(), ref -> tariff.map(Tariff::getPrice).orElse(BigDecimal.ZERO));
                line.setPrice(price);
                int periods = line.getPeriods() == null ? 0 : line.getPeriods();
                line.setAmount(line.getPrice().multiply(BigDecimal.valueOf(periods)));
                total = total.add(line.getAmount());
                if (tariff.isPresent() && tariff.get().getPeriodDays() != null && periods > 0) {
                    maxDays = Math.max(maxDays, (long) tariff.get().getPeriodDays() * periods);
                }
            }
        }
        subscription.setTotal(total);
        subscription.setEndDate(null);
        if (subscription.getStartDate() != null) {
            try {
                subscription.setEndDate(subscription.getStartDate().plusDays(maxDays));
            } catch (DateTimeException outOfRange) {
                // The business rule below returns a validation error instead of a server error.
            }
        }
    }

    public List<BusinessRule> rules(Subscription subscription) {
        List<BusinessRule> rules = new ArrayList<>(List.of(
                BusinessRule.onField("customer", "Выберите существующего клиента",
                        () -> subscription.getCustomer() != null
                                && customers.findActiveById(subscription.getCustomer().id()).isPresent()),
                BusinessRule.onField("status", "Укажите статус подписки", () -> subscription.getStatus() != null),
                BusinessRule.onField("startDate", "Укажите дату начала", () -> subscription.getStartDate() != null),
                new BusinessRule("items-required", "Добавьте хотя бы одну строку с тарифом",
                        () -> subscription.getItems() != null && !subscription.getItems().isEmpty()),
                BusinessRule.onField("endDate", "Не удалось рассчитать допустимую дату окончания",
                        () -> subscription.getEndDate() != null),
                BusinessRule.onField("total", "Итоговая сумма превышает допустимый диапазон",
                        () -> validAmount(subscription.getTotal()))));
        if (subscription.getItems() != null) {
            for (int i = 0; i < subscription.getItems().size(); i++) {
                SubscriptionLine line = subscription.getItems().get(i);
                String prefix = "Строка " + (i + 1) + ": ";
                int row = i + 1;
                rules.add(new BusinessRule("tariff-available-" + row, prefix + "выберите доступный тариф",
                        () -> tariff(line).filter(Tariff::isAvailable).isPresent()));
                rules.add(new BusinessRule("periods-positive-" + row, prefix + "число периодов должно быть больше нуля",
                        () -> line != null && line.getPeriods() != null && line.getPeriods() > 0));
                rules.add(new BusinessRule("amount-range-" + row, prefix + "сумма превышает допустимый диапазон",
                        () -> line != null && validAmount(line.getAmount())));
            }
        }
        return rules;
    }

    private Optional<Tariff> tariff(SubscriptionLine line) {
        return line == null || line.getTariff() == null
                ? Optional.empty() : tariffs.findActiveById(line.getTariff().id());
    }

    private Map<Ref<Tariff>, BigDecimal> savedPrices(Subscription subscription) {
        Map<Ref<Tariff>, BigDecimal> prices = new HashMap<>();
        if (subscription.getId() != null) {
            subscriptions.findActiveById(subscription.getId()).ifPresent(stored -> {
                if (stored.getItems() != null) {
                    for (SubscriptionLine line : stored.getItems()) {
                        if (line.getTariff() != null && line.getPrice() != null) {
                            prices.putIfAbsent(line.getTariff(), line.getPrice());
                        }
                    }
                }
            });
        }
        return prices;
    }

    private boolean validAmount(BigDecimal amount) {
        return amount != null && amount.signum() >= 0 && amount.compareTo(MAX_AMOUNT) <= 0;
    }
}
