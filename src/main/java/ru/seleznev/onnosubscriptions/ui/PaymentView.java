package ru.seleznev.onnosubscriptions.ui;

import org.springframework.stereotype.Component;
import ru.seleznev.onnosubscriptions.domain.Customer;
import ru.seleznev.onnosubscriptions.domain.Payment;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import ru.seleznev.onnosubscriptions.repository.PaymentRepository;
import su.onno.posting.PostingService;
import su.onno.validation.ValidationException;
import su.onno.ui.ListSpec;
import su.onno.ui.ActionSpec;
import su.onno.ui.ActionScope;
import su.onno.ui.ActionResult;
import su.onno.ui.ActionToast;

@Component
public class PaymentView implements EntityView<Payment> {
    private final PaymentRepository documents;
    private final PostingService posting;

    public PaymentView(PaymentRepository documents, PostingService posting) {
        this.documents = documents;
        this.posting = posting;
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
    }

    @Override
    public Class<Payment> entity() {
        return Payment.class;
    }

    @Override
    public void list(ListSpec<Payment> list) {
        list.columns(Payment::getNumber, Payment::getDate, Payment::getCustomer,
                        Payment::getAmount, Payment::isPosted)
                .label(Payment::getNumber, "Номер")
                .label(Payment::getDate, "Дата")
                .label(Payment::getCustomer, "Клиент")
                .label(Payment::getAmount, "Сумма")
                .label(Payment::isPosted, "Проведён")
                .sortBy(Payment::getDate, true);
        list.filter(Payment::getDate).label("Дата платежа").dateRange();
    }

    @Override
    public void fields(EntityConfigBuilder<Payment> fields) {
        fields.icon("wallet");
        fields.field(Payment::getNumber).label("Номер").order(10).width("half")
                .hint("Если оставить пустым, номер присвоится автоматически.");
        fields.field(Payment::getDate).label("Дата").order(20).width("half").format("dd.MM.yyyy HH:mm")
                .hint("Дата платежа. Для нового документа подставляется текущая дата и время.");
        fields.refField(Payment::getCustomer).order(30).width("full").refSecondary(Customer::getEmail)
                .hint("Клиент, на чей лицевой счёт поступят деньги.");
        fields.field(Payment::getAmount).order(40).width("half").format("currency:RUB")
                .hint("Положительная сумма в рублях. Сохранение создаёт черновик; проведение пополняет баланс.");
        fields.field(Payment::getPaymentMethod).order(50).width("half");
        fields.field(Payment::isPosted).label("Проведён").hideInForm();
        fields.action("post").hidden();
        fields.action("unpost").inMenu();
    }
}
