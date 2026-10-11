package ru.seleznev.onnosubscriptions.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.seleznev.onnosubscriptions.domain.SubscriptionStatus;
import ru.seleznev.onnosubscriptions.repository.SubscriptionRepository;
import ru.seleznev.onnosubscriptions.repository.SubscriptionStatusWriter;
import su.onno.annotations.ScheduledJob;
import su.onno.jobs.BackgroundJobs;
import su.onno.jobs.BackgroundTask;

import java.time.Clock;
import java.time.LocalDate;

@Component
@ScheduledJob(name = "subscription-statuses", cron = "0 * * * *")
public class SubscriptionStatusJob implements BackgroundTask {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionStatusJob.class);
    private final SubscriptionRepository subscriptions;
    private final SubscriptionStatusWriter writer;
    private final Clock clock;
    private final BackgroundJobs jobs;

    public SubscriptionStatusJob(SubscriptionRepository subscriptions, SubscriptionStatusWriter writer,
                                 Clock clock, BackgroundJobs jobs) {
        this.subscriptions = subscriptions;
        this.writer = writer;
        this.clock = clock;
        this.jobs = jobs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void enqueueInitialCheck() {
        jobs.enqueue(this);
    }

    @Override
    public void execute() {
        LocalDate today = LocalDate.now(clock);
        int updated = 0;
        for (var subscription : subscriptions.findAllActive()) {
            if (!subscription.isPosted() || subscription.getStatus() == SubscriptionStatus.CANCELLED
                    || subscription.getStartDate() == null || subscription.getEndDate() == null) continue;
            SubscriptionStatus status = today.isBefore(subscription.getStartDate()) ? SubscriptionStatus.DRAFT
                    : today.isBefore(subscription.getEndDate()) ? SubscriptionStatus.ACTIVE : SubscriptionStatus.EXPIRED;
            if (subscription.getStatus() != status && writer.updateIfUnchanged(subscription, status)) updated++;
        }
        log.info("Subscription statuses checked for {}: {} updated", today, updated);
    }
}
