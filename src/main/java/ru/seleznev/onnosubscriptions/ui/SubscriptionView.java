package ru.seleznev.onnosubscriptions.ui;

import org.springframework.stereotype.Component;
import ru.seleznev.onnosubscriptions.domain.Customer;
import ru.seleznev.onnosubscriptions.domain.Subscription;
import ru.seleznev.onnosubscriptions.domain.SubscriptionLine;
import ru.seleznev.onnosubscriptions.domain.Tariff;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import ru.seleznev.onnosubscriptions.repository.SubscriptionRepository;
import su.onno.posting.PostingService;
import su.onno.validation.ValidationException;
import su.onno.ui.ListSpec;
import su.onno.ui.ActionSpec;
import su.onno.ui.ActionScope;
import su.onno.ui.ActionResult;
import su.onno.ui.ActionToast;
import su.onno.ui.InputType;
import ru.seleznev.onnosubscriptions.domain.SubscriptionStatus;
import ru.seleznev.onnosubscriptions.service.SubscriptionCancellation;

@Component
public class SubscriptionView implements EntityView<Subscription> {
    private final SubscriptionRepository documents;
    private final PostingService posting;
    private final SubscriptionCancellation cancellation;

    public SubscriptionView(SubscriptionCancellation cancellation, SubscriptionRepository documents, PostingService posting) {
        this.documents = documents;
        this.posting = posting;
        this.cancellation = cancellation;
    }

    @Override
    public void actions(ActionSpec actions) {
        actions.action("post-document").label("Провести").icon("check").scope(ActionScope.DETAIL).roles("ADMIN")
                .visibleWhen(row -> row.id() != null && !row.bool("_posted"))
                .enabledWhen(row -> !row.bool("_posted"))
                .handler(context -> {
                    var document = documents.findActiveById(context.id())
                            .orElseThrow(() -> new ValidationException("Документ не найден"));
                    posting.post(document);
                    return ActionResult.refresh(ActionToast.success("Документ проведён"));
                });
        for (var scope : new ActionScope[]{ActionScope.ROW, ActionScope.DETAIL}) {
            actions.action(scope == ActionScope.ROW ? "cancel-subscription-row" : "cancel-subscription")
                    .label("Отменить подписку").icon("ban").scope(scope).roles("ADMIN")
                    .visibleWhen(row -> row.enumValue(Subscription::getStatus, SubscriptionStatus.class) != SubscriptionStatus.CANCELLED)
                    .form(form -> {
                        form.title("Отмена подписки").description("Списание будет возвращено на лицевой счёт, выручка отменена.")
                                .submitLabel("Отменить подписку").cancelLabel("Назад");
                        form.input("reason").label("Причина отмены").type(InputType.TEXTAREA).required();
                    })
                    .handler(context -> {
                        cancellation.cancel(context.id(), context.input("reason"));
                        return ActionResult.refresh(ActionToast.success("Подписка отменена"));
                    });
        }
    }
    @Override
    public Class<Subscription> entity() {
        return Subscription.class;
    }

    @Override
    public void list(ListSpec<Subscription> list) {
        list.columns(Subscription::getCustomer, Subscription::getStatus, Subscription::getEndDate,
                        Subscription::getTotal, Subscription::isPosted)
                .label(Subscription::getCustomer, "Клиент")
                .label(Subscription::getStatus, "Статус")
                .label(Subscription::getEndDate, "Окончание")
                .label(Subscription::getTotal, "Итог")
                .label(Subscription::isPosted, "Проведение")
                .sortBy(Subscription::getDate, true);
        list.filter(Subscription::getStatus).label("Статус").multiOptions();
    }

    @Override
    public void fields(EntityConfigBuilder<Subscription> fields) {
        fields.icon("calendar-check");
        fields.action("post").hidden();
        fields.action("unpost").inMenu();
        fields.field(Subscription::getNumber).label("Номер").order(10).width("half");
        fields.field(Subscription::getDate).label("Дата документа").order(20).width("half")
                .format("dd.MM.yyyy HH:mm");
        fields.refField(Subscription::getCustomer).order(30).width("full").refSecondary(Customer::getEmail)
                .hint("Клиент, для которого оформляется подписка.");
        fields.field(Subscription::getStatus).order(40).width("half");
        fields.field(Subscription::getStartDate).order(50).width("half").format("dd.MM.yyyy")
                .hint("По умолчанию — сегодня. Срок отсчитывается от этой даты.");
        fields.field(Subscription::getEndDate).order(60).width("half").format("dd.MM.yyyy")
                .hint("Рассчитывается при записи: начало плюс максимальный срок среди строк.");
        fields.field(Subscription::getTotal).order(70).width("half").format("currency:RUB")
                .hint("Рассчитывается при записи как сумма всех строк.");
        fields.field(Subscription::getItems).label("Тарифы подписки").order(80).width("full")
                .hint("Добавьте тарифы и количество периодов. Суммы и окончание обновятся после записи.");
        fields.rowRefField(Subscription::getItems, SubscriptionLine::getTariff).order(10)
                .refSecondary(Tariff::getPrice).refFilter("available = true");
        fields.rowField(Subscription::getItems, SubscriptionLine::getPeriods).order(20).format("integer")
                .hint("Целое число периодов, минимум 1.");
        fields.rowField(Subscription::getItems, SubscriptionLine::getPrice).order(30).format("currency:RUB")
                .hint("Берётся из тарифа при оформлении и фиксируется в документе. При смене тарифа подставится новая цена.");
        fields.rowField(Subscription::getItems, SubscriptionLine::getAmount).order(40).format("currency:RUB")
                .hint("Цена за период × количество периодов. Пересчитывается при записи.");
        fields.field(Subscription::isPosted).label("Проведена").hideInForm();
        fields.field(Subscription::getCancellationReason).order(90).width("full").widget("textarea")
                .hint("Сохраняется при отмене подписки с причиной.");
    }
}
