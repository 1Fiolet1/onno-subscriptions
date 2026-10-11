package ru.seleznev.onnosubscriptions.service;

import org.springframework.stereotype.Service;
import ru.seleznev.onnosubscriptions.domain.SubscriptionStatus;
import ru.seleznev.onnosubscriptions.repository.SubscriptionRepository;
import su.onno.posting.PostingService;
import su.onno.validation.ValidationException;

import java.util.UUID;

@Service
public class SubscriptionCancellation {
    private final SubscriptionRepository subscriptions;
    private final PostingService posting;

    public SubscriptionCancellation(SubscriptionRepository subscriptions, PostingService posting) {
        this.subscriptions = subscriptions;
        this.posting = posting;
    }

    public void cancel(UUID id, String reason) {
        if (id == null) throw new ValidationException("Выберите подписку");
        String text = reason == null ? "" : reason.strip();
        if (text.isBlank() || text.length() > 1000) {
            throw new ValidationException("Укажите причину отмены (до 1000 символов)");
        }
        var subscription = subscriptions.findActiveById(id)
                .orElseThrow(() -> new ValidationException("Подписка не найдена"));
        if (subscription.getStatus() == SubscriptionStatus.CANCELLED) return;
        // Cancellation preserves the purchase snapshot even if its tariff is now unavailable.
        subscription.cancelWithReason(text);
        if (subscription.isPosted()) posting.repost(subscription);
        else subscriptions.save(subscription);
    }
}
