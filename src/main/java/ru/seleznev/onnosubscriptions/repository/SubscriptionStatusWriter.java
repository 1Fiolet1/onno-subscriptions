package ru.seleznev.onnosubscriptions.repository;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.seleznev.onnosubscriptions.domain.Subscription;
import ru.seleznev.onnosubscriptions.domain.SubscriptionStatus;
import su.onno.metadata.MetadataRegistry;

import java.util.Map;
import java.util.UUID;

/** Changes only lifecycle status; aggregate save would recalculate dates and replace tariff rows. */
@Repository
public class SubscriptionStatusWriter {
    private final NamedParameterJdbcTemplate jdbc;
    private final MetadataRegistry metadata;

    public SubscriptionStatusWriter(NamedParameterJdbcTemplate jdbc, MetadataRegistry metadata) {
        this.jdbc = jdbc;
        this.metadata = metadata;
    }

    public boolean updateIfUnchanged(Subscription snapshot, SubscriptionStatus status) {
        var document = metadata.getDocumentDescriptor(Subscription.class);
        String column = column("status");
        // Version and status guards prevent overwriting a concurrent cancellation/edit/unposting.
        String sql = "UPDATE " + document.tableName() + " SET " + column
                + " = :status, _version = _version + 1 WHERE _id = :id AND _version = :version"
                + " AND " + column + " = :previous AND _posted = true AND _deletion_mark = false"
                + " AND " + column("startDate") + " = :start AND " + column("endDate") + " = :end";
        return jdbc.update(sql, Map.of("status", statusId(status), "id", snapshot.getId(),
                "version", snapshot.getVersion(), "previous", statusId(snapshot.getStatus()),
                "start", snapshot.getStartDate(), "end", snapshot.getEndDate())) == 1;
    }

    private String column(String field) {
        return metadata.getDocumentDescriptor(Subscription.class).attributes().stream()
                .filter(attribute -> attribute.fieldName().equals(field)).findFirst().orElseThrow().columnName();
    }

    private UUID statusId(SubscriptionStatus status) {
        return metadata.getEnumerationDescriptor(SubscriptionStatus.class).values().stream()
                .filter(value -> value.name().equals(status.name())).findFirst().orElseThrow().id();
    }
}
