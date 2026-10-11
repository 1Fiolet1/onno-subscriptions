package ru.seleznev.onnosubscriptions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.seleznev.onnosubscriptions.domain.CustomerStatus;
import ru.seleznev.onnosubscriptions.domain.Customer;
import ru.seleznev.onnosubscriptions.domain.CustomerAccount;
import ru.seleznev.onnosubscriptions.domain.Payment;
import ru.seleznev.onnosubscriptions.domain.PaymentMethod;
import ru.seleznev.onnosubscriptions.domain.Subscription;
import ru.seleznev.onnosubscriptions.domain.SubscriptionLine;
import ru.seleznev.onnosubscriptions.domain.SubscriptionStatus;
import ru.seleznev.onnosubscriptions.domain.Tariff;
import ru.seleznev.onnosubscriptions.domain.Revenue;
import ru.seleznev.onnosubscriptions.repository.SubscriptionRepository;
import su.onno.metadata.MetadataRegistry;
import su.onno.posting.PostingService;
import su.onno.types.Ref;
import su.onno.validation.ValidationException;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import ru.seleznev.onnosubscriptions.jobs.SubscriptionStatusJob;
import ru.seleznev.onnosubscriptions.repository.SubscriptionStatusWriter;
import su.onno.jobs.BackgroundJobs;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OnnoSubscriptionsApplicationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("onno_test")
            .withUsername("onno_test")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("onno.auth.users[0].username", () -> "test-admin");
        registry.add("onno.auth.users[0].password", () -> "test-admin-password");
        registry.add("onno.auth.users[0].roles[0]", () -> "ADMIN");
        registry.add("onno.auth.session.remember-me.key", () -> "test-only-stable-remember-me-key");
        registry.add("org.jobrunr.background-job-server.enabled", () -> "false");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    MetadataRegistry metadata;

    @Autowired
    PostingService posting;

    @Autowired
    SubscriptionRepository subscriptions;

    @Autowired
    SubscriptionStatusWriter statusWriter;

    @Autowired
    BackgroundJobs backgroundJobs;

    @Autowired
    org.jobrunr.storage.StorageProvider jobStorage;

    @LocalServerPort
    int port;

    @Test
    void initializesOnnoSchemaInPostgreSql() {
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("PostgreSQL 17.");
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("onno_test");
        assertThat(jdbc.queryForObject("select count(*) from onno_schema_history", Long.class)).isPositive();
    }

    @Test
    void servesUiAndRussianConfiguration() throws Exception {
        try (HttpClient client = newClient()) {
            HttpResponse<String> page = get(client, "/");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.headers().firstValue("Content-Type").orElse("")).contains("text/html");
            assertThat(page.body()).contains("<html", "<script");

            HttpResponse<String> response = get(client, "/api/config");
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode config = mapper.readTree(response.body());
            assertThat(config.path("locale").asText()).isEqualTo("ru");
            assertThat(config.path("basePath").asText()).isEqualTo("/");
            assertThat(config.path("update").path("current").asText()).isEqualTo("3.4.1");
        }
    }

    @Test
    void rejectsAnonymousAccessToProtectedApi() throws Exception {
        try (HttpClient client = newClient()) {
            assertThat(get(client, "/api/divkit/shell").statusCode()).isEqualTo(401);
        }
    }

    @Test
    void authenticatesAdminAndKeepsSession() throws Exception {
        try (HttpClient client = newClient()) {
            assertThat(login(client, "wrong-password").statusCode()).isEqualTo(401);

            HttpResponse<String> response = login(client, "test-admin-password");
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode user = mapper.readTree(response.body());
            assertThat(user.path("authenticated").asBoolean()).isTrue();
            assertThat(user.path("roles").path(0).asText()).isEqualTo("ROLE_ADMIN");

            JsonNode me = mapper.readTree(get(client, "/api/auth/me").body());
            assertThat(me.path("authenticated").asBoolean()).isTrue();
            assertThat(me.path("username").asText()).isEqualTo("test-admin");

            HttpResponse<String> shell = get(client, "/api/divkit/shell");
            assertThat(shell.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(shell.body()).has("nav")).isTrue();
        }
    }

    @Test
    void exposesCatalogNavigationAndNewFormDefaults() throws Exception {
        try (HttpClient client = adminClient()) {
            JsonNode shell = mapper.readTree(get(client, "/api/divkit/shell").body());
            assertThat(shell.path("navigation").toString())
                    .contains("Работа с клиентами", "Настройка тарифов", "/catalogs/customers", "/catalogs/tariffs");

            JsonNode customer = newForm(client, "Customers").path("initial");
            assertThat(customer.path("registrationDate").asText()).isEqualTo(LocalDate.now().toString());
            assertThat(customer.path("status").asText()).isEqualTo(statusId(CustomerStatus.ACTIVE));
            assertThat(customer.path("statusDisplay").asText()).isEqualTo("Активен");

            JsonNode tariff = newForm(client, "Tariffs").path("initial");
            assertThat(tariff.path("periodDays").asInt()).isEqualTo(30);
            assertThat(tariff.path("available").asBoolean()).isTrue();
            assertThat(tariff.path("price").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    void createsAndUpdatesCustomerThroughGeneratedApi() throws Exception {
        try (HttpClient client = adminClient()) {
            Map<String, Object> body = customerBody("Тестовый клиент");
            body.put("email", "customer@example.com");
            body.put("phone", "+7 900 123-45-67");
            JsonNode created = create(client, "Customers", body);
            String path = "/api/catalogs/Customers/" + created.path("id").asText();
            assertThat(created.path("code").asText()).startsWith("C-");
            assertThat(created.path("statusDisplay").asText()).isEqualTo("Активен");

            HttpResponse<String> updated = mutate(client, "PUT", path, Map.of(
                    "email", "updated@example.com", "status", statusId(CustomerStatus.INACTIVE)));
            assertThat(updated.statusCode()).isEqualTo(200);
            JsonNode saved = mapper.readTree(get(client, path).body());
            assertThat(saved.path("email").asText()).isEqualTo("updated@example.com");
            assertThat(saved.path("phone").asText()).isEqualTo("+7 900 123-45-67");
            assertThat(saved.path("registrationDate").asText()).isEqualTo(body.get("registrationDate"));
            assertThat(saved.path("statusDisplay").asText()).isEqualTo("Неактивен");
        }
    }

    @Test
    void persistsTariffPriceAndAvailability() throws Exception {
        try (HttpClient client = adminClient()) {
            JsonNode created = create(client, "Tariffs", Map.of(
                    "description", "Тестовый тариф", "price", new BigDecimal("1234.56"),
                    "periodDays", 365, "available", true));
            String path = "/api/catalogs/Tariffs/" + created.path("id").asText();
            assertThat(created.path("code").asText()).startsWith("T-");
            assertThat(mutate(client, "PUT", path, Map.of("available", false)).statusCode()).isEqualTo(200);
            JsonNode saved = mapper.readTree(get(client, path).body());
            assertThat(saved.path("price").decimalValue()).isEqualByComparingTo("1234.56");
            assertThat(saved.path("periodDays").asInt()).isEqualTo(365);
            assertThat(saved.path("available").asBoolean()).isFalse();

            JsonNode free = create(client, "Tariffs", Map.of(
                    "description", "Бесплатный тариф", "price", 0, "periodDays", 1, "available", true));
            assertThat(free.path("price").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    void rejectsInvalidCatalogDataWithoutWritingRows() throws Exception {
        long customersBefore = jdbc.queryForObject("select count(*) from catalog_customers", Long.class);
        long tariffsBefore = jdbc.queryForObject("select count(*) from catalog_tariffs", Long.class);
        try (HttpClient client = adminClient()) {
            HttpResponse<String> emptyName = mutate(client, "POST", "/api/catalogs/Customers", customerBody(" "));
            assertThat(emptyName.statusCode()).isEqualTo(400);
            assertThat(mapper.readTree(emptyName.body()).path("fieldErrors").has("description")).isTrue();

            Map<String, Object> customer = customerBody("Клиент с ошибкой");
            customer.put("email", "not-an-email");
            assertThat(mutate(client, "POST", "/api/catalogs/Customers", customer).statusCode()).isEqualTo(400);

            Map<String, Object> tariff = new HashMap<>(Map.of(
                    "description", "Тариф с ошибкой", "price", -1, "periodDays", 0, "available", true));
            HttpResponse<String> invalid = mutate(client, "POST", "/api/catalogs/Tariffs", tariff);
            assertThat(invalid.statusCode()).isEqualTo(400);
            JsonNode errors = mapper.readTree(invalid.body()).path("fieldErrors");
            assertThat(errors.has("price")).isTrue();
            assertThat(errors.has("periodDays")).isTrue();

            tariff.put("price", 0);
            tariff.put("periodDays", 1);
            tariff.put("description", "");
            assertThat(mutate(client, "POST", "/api/catalogs/Tariffs", tariff).statusCode()).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("select count(*) from catalog_customers", Long.class)).isEqualTo(customersBefore);
        assertThat(jdbc.queryForObject("select count(*) from catalog_tariffs", Long.class)).isEqualTo(tariffsBefore);
    }

    @Test
    void exposesPaymentAndAccountNavigationAndDefaults() throws Exception {
        try (HttpClient client = adminClient()) {
            JsonNode shell = mapper.readTree(get(client, "/api/divkit/shell").body());
            assertThat(shell.path("navigation").toString())
                    .contains("Платежи", "Лицевые счета", "/documents/payments", "/registers/customer_account");
            HttpResponse<String> response = get(client, "/api/divkit/documents/Payments/new");
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode form = mapper.readTree(response.body()).findValue("form");
            assertThat(form).isNotNull();
            JsonNode initial = form.path("initial");
            assertThat(initial.path("amount").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(initial.path("paymentMethod").asText()).isEqualTo(paymentMethodId(PaymentMethod.CARD));
            assertThat(initial.path("paymentMethodDisplay").asText()).isEqualTo("Карта");
        }
    }

    @Test
    void creditsOnlySelectedCustomerAndPreviewDoesNotPersist() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Получатель платежа")).path("id").asText();
            String other = create(client, "Customers", customerBody("Другой клиент")).path("id").asText();
            JsonNode payment = createPayment(client, customer, "2500.50", PaymentMethod.BANK_TRANSFER);
            String id = payment.path("id").asText();
            String path = "/api/documents/Payments/" + id;
            assertThat(payment.path("number").asText()).startsWith("PAY-");
            assertThat(payment.path("posted").asBoolean()).isFalse();
            assertThat(payment.path("customerDisplay").asText()).isEqualTo("Получатель платежа");
            assertThat(payment.path("paymentMethodDisplay").asText()).isEqualTo("Банковский перевод");
            assertThat(LocalDateTime.parse(payment.path("date").asText()).toLocalDate()).isEqualTo(LocalDate.now());
            assertThat(balance(client, customer)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(movementCount(id)).isZero();

            HttpResponse<String> previewResponse = get(client, path + "/posting-preview");
            assertThat(previewResponse.statusCode()).isEqualTo(200);
            JsonNode registers = mapper.readTree(previewResponse.body()).path("registers");
            assertThat(registers.size()).isEqualTo(1);
            assertThat(registers.path(0).path("name").asText()).isEqualTo("CustomerAccount");
            assertThat(registers.path(0).path("movements").size()).isEqualTo(1);
            assertThat(balance(client, customer)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(movementCount(id)).isZero();

            assertThat(paymentCommand(client, id, "post").path("posted").asBoolean()).isTrue();
            assertThat(balance(client, customer)).isEqualByComparingTo("2500.50");
            assertThat(balance(client, other)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(movementCount(id)).isEqualTo(1);

            JsonNode second = createPayment(client, customer, "99.49", PaymentMethod.CASH);
            paymentCommand(client, second.path("id").asText(), "post");
            assertThat(balance(client, customer)).isEqualByComparingTo("2599.99");
            assertThat(balance(client, other)).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    void unpostsAndRepostsPaymentWithoutDuplicateMovements() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Перепроведение платежа")).path("id").asText();
            String other = create(client, "Customers", customerBody("Новый получатель")).path("id").asText();
            Map<String, Object> body = paymentBody(customer, "100.00", PaymentMethod.CARD);
            body.put("date", "2026-01-15T10:30:00");
            HttpResponse<String> created = mutate(client, "POST", "/api/documents/Payments", body);
            assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(200);
            String id = mapper.readTree(created.body()).path("id").asText();
            String path = "/api/documents/Payments/" + id;

            paymentCommand(client, id, "post");
            paymentCommand(client, id, "post");
            assertThat(balance(client, customer)).isEqualByComparingTo("100.00");
            assertThat(movementCount(id)).isEqualTo(1);
            assertThat(paymentCommand(client, id, "unpost").path("posted").asBoolean()).isFalse();
            assertThat(balance(client, customer)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(movementCount(id)).isZero();

            HttpResponse<String> changed = mutate(client, "PUT", path, Map.of(
                    "amount", new BigDecimal("150.25"), "customer", other));
            assertThat(changed.statusCode()).withFailMessage(changed.body()).isEqualTo(200);
            JsonNode posted = paymentCommand(client, id, "post");
            assertThat(LocalDateTime.parse(posted.path("date").asText()))
                    .isEqualTo(LocalDateTime.of(2026, 1, 15, 10, 30));
            assertThat(balance(client, customer)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(balance(client, other)).isEqualByComparingTo("150.25");
            assertThat(movementCount(id)).isEqualTo(1);
            paymentCommand(client, id, "post");
            assertThat(balance(client, other)).isEqualByComparingTo("150.25");
            assertThat(movementCount(id)).isEqualTo(1);
        }
    }

    @Test
    void failedCorePostingKeepsExistingPaymentAndBalance() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Защита проведённого платежа")).path("id").asText();
            JsonNode saved = createPayment(client, customer, "750.50", PaymentMethod.CARD);
            String id = saved.path("id").asText();
            paymentCommand(client, id, "post");
            Payment payment = new Payment();
            payment.setId(UUID.fromString(id));
            payment.setDate(LocalDateTime.parse(saved.path("date").asText()));
            payment.setCustomer(Ref.of(Customer.class, UUID.fromString(customer)));
            payment.setAmount(new BigDecimal("750.50"));
            payment.setPosted(true);
            assertThatThrownBy(() -> posting.post(payment))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("already posted");
            payment.setAmount(BigDecimal.ZERO);
            assertThatThrownBy(() -> posting.repost(payment)).isInstanceOf(ValidationException.class);
            JsonNode stored = mapper.readTree(get(client, "/api/documents/Payments/" + id).body());
            assertThat(stored.path("posted").asBoolean()).isTrue();
            assertThat(stored.path("amount").decimalValue()).isEqualByComparingTo("750.50");
            assertThat(balance(client, customer)).isEqualByComparingTo("750.50");
            assertThat(movementCount(id)).isEqualTo(1);
        }
    }

    @Test
    void rejectsInvalidPaymentsWithoutDocumentsOrMovements() throws Exception {
        String documents = metadata.getDocumentDescriptor(Payment.class).tableName();
        String movements = metadata.getRegisterDescriptor(CustomerAccount.class).tableName();
        long documentsBefore = jdbc.queryForObject("select count(*) from " + documents, Long.class);
        long movementsBefore = jdbc.queryForObject("select count(*) from " + movements, Long.class);
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Проверка платежа")).path("id").asText();
            Map<String, Object> body = paymentBody(customer, "100.00", PaymentMethod.CARD);
            body.remove("customer");
            HttpResponse<String> missing = mutate(client, "POST", "/api/documents/Payments", body);
            assertThat(missing.statusCode()).isEqualTo(400);
            assertThat(mapper.readTree(missing.body()).path("fieldErrors").has("customer")).isTrue();

            for (String amount : new String[]{"0.00", "-0.01"}) {
                HttpResponse<String> invalid = mutate(client, "POST", "/api/documents/Payments",
                        paymentBody(customer, amount, PaymentMethod.CARD));
                assertThat(invalid.statusCode()).withFailMessage(invalid.body()).isEqualTo(400);
                assertThat(mapper.readTree(invalid.body()).path("fieldErrors").has("amount")).isTrue();
            }
            body = paymentBody(customer, "100.00", PaymentMethod.CARD);
            body.remove("paymentMethod");
            HttpResponse<String> method = mutate(client, "POST", "/api/documents/Payments", body);
            assertThat(method.statusCode()).isEqualTo(400);
            assertThat(mapper.readTree(method.body()).path("fieldErrors").has("paymentMethod")).isTrue();
            assertThat(balance(client, customer)).isEqualByComparingTo(BigDecimal.ZERO);
        }
        assertThat(jdbc.queryForObject("select count(*) from " + documents, Long.class)).isEqualTo(documentsBefore);
        assertThat(jdbc.queryForObject("select count(*) from " + movements, Long.class)).isEqualTo(movementsBefore);
    }

    @Test
    void exposesSubscriptionNavigationAndNewFormDefaults() throws Exception {
        try (HttpClient client = adminClient()) {
            JsonNode shell = mapper.readTree(get(client, "/api/divkit/shell").body());
            assertThat(shell.path("navigation").toString()).contains("Подписки", "/documents/subscriptions");
            HttpResponse<String> response = get(client, "/api/divkit/documents/Subscriptions/new");
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode form = mapper.readTree(response.body()).findValue("form");
            assertThat(form).isNotNull();
            assertThat(form.path("initial").path("startDate").asText()).isEqualTo(LocalDate.now().toString());
            assertThat(form.path("initial").path("status").asText()).isEqualTo(subscriptionStatusId(SubscriptionStatus.DRAFT));
            assertThat(form.path("initial").path("statusDisplay").asText()).isEqualTo("Черновик");
        }
    }

    @Test
    void calculatesSubscriptionTotalAndMaximumDurationFromTariffs() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Подписка на несколько тарифов")).path("id").asText();
            String annual = createTariff(client, "Годовой", "1200.10", 365, true);
            String monthly = createTariff(client, "Месячный", "99.99", 30, true);
            Map<String, Object> first = subscriptionLine(annual, 1);
            first.put("price", new BigDecimal("0.01"));
            first.put("amount", 1);
            Map<String, Object> body = subscriptionBody(customer, LocalDate.of(2026, 1, 1),
                    List.of(first, subscriptionLine(monthly, 3)));
            body.put("total", 1);
            body.put("endDate", "1900-01-01");
            JsonNode saved = createSubscription(client, body);
            assertThat(saved.path("number").asText()).startsWith("SUB-");
            assertThat(saved.path("posted").asBoolean()).isFalse();
            assertThat(saved.path("statusDisplay").asText()).isEqualTo("Черновик");
            assertThat(LocalDateTime.parse(saved.path("date").asText()).toLocalDate()).isEqualTo(LocalDate.now());
            assertThat(saved.path("total").decimalValue()).isEqualByComparingTo("1500.07");
            assertThat(saved.path("endDate").asText()).isEqualTo("2027-01-01");
            assertThat(saved.path("items").size()).isEqualTo(2);
            assertThat(saved.path("items").path(0).path("price").decimalValue()).isEqualByComparingTo("1200.10");
            assertThat(saved.path("items").path(1).path("amount").decimalValue()).isEqualByComparingTo("299.97");
            assertThat(saved.path("items").path(1).path("tariffDisplay").asText()).isEqualTo("Месячный");
            assertThat(balance(client, customer)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(movementCount(saved.path("id").asText())).isZero();
            JsonNode reloaded = readSubscription(client, saved.path("id").asText());
            assertThat(reloaded).isEqualTo(saved);
        }
    }

    @Test
    void replacesSubscriptionRowsAndRecalculatesOnEverySave() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Пересчёт подписки")).path("id").asText();
            String annual = createTariff(client, "Годовой для пересчёта", "1000.00", 365, true);
            String monthly = createTariff(client, "Месячный для пересчёта", "99.99", 30, true);
            Map<String, Object> body = subscriptionBody(customer, LocalDate.of(2026, 1, 1),
                    List.of(subscriptionLine(annual, 1), subscriptionLine(monthly, 1)));
            body.put("date", "2025-12-31T12:30:00");
            String id = createSubscription(client, body).path("id").asText();
            String path = "/api/documents/Subscriptions/" + id;
            HttpResponse<String> changed = mutate(client, "PUT", path, Map.of(
                    "startDate", "2026-02-01", "items", List.of(subscriptionLine(monthly, 13)),
                    "total", 1, "endDate", "1900-01-01"));
            assertThat(changed.statusCode()).withFailMessage(changed.body()).isEqualTo(200);
            JsonNode saved = readSubscription(client, id);
            assertThat(saved.path("items").size()).isEqualTo(1);
            assertThat(saved.path("total").decimalValue()).isEqualByComparingTo("1299.87");
            assertThat(saved.path("endDate").asText()).isEqualTo(LocalDate.of(2026, 2, 1).plusDays(390).toString());
            assertThat(LocalDateTime.parse(saved.path("date").asText())).isEqualTo(LocalDateTime.of(2025, 12, 31, 12, 30));
            String rows = metadata.getDocumentDescriptor(Subscription.class).tabularSections().getFirst().tableName();
            assertThat(jdbc.queryForObject("select count(*) from " + rows + " where _parent_id = ?",
                    Long.class, UUID.fromString(id))).isEqualTo(1);

            assertThat(mutate(client, "PUT", path, Map.of("startDate", "2026-06-01")).statusCode()).isEqualTo(200);
            saved = readSubscription(client, id);
            assertThat(saved.path("endDate").asText()).isEqualTo(LocalDate.of(2026, 6, 1).plusDays(390).toString());
            assertThat(saved.path("total").decimalValue()).isEqualByComparingTo("1299.87");
            assertThat(saved.path("items").path(0).path("amount").decimalValue()).isEqualByComparingTo("1299.87");
        }
    }

    @Test
    void keepsQuotedPriceAndFillsPriceWhenTariffChanges() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Фиксация цены")).path("id").asText();
            String tariff = createTariff(client, "Первый прайс", "10.25", 30, true);
            String other = createTariff(client, "Другой тариф", "7.99", 7, true);
            String id = createSubscription(client, subscriptionBody(customer, LocalDate.of(2026, 1, 1),
                    List.of(subscriptionLine(tariff, 2)))).path("id").asText();
            assertThat(mutate(client, "PUT", "/api/catalogs/Tariffs/" + tariff,
                    Map.of("price", new BigDecimal("50.00"))).statusCode()).isEqualTo(200);
            String path = "/api/documents/Subscriptions/" + id;
            assertThat(mutate(client, "PUT", path, Map.of("startDate", "2026-02-01")).statusCode()).isEqualTo(200);
            JsonNode saved = readSubscription(client, id);
            assertThat(saved.path("total").decimalValue()).isEqualByComparingTo("20.50");
            assertThat(saved.path("items").path(0).path("price").decimalValue()).isEqualByComparingTo("10.25");
            Map<String, Object> line = subscriptionLine(tariff, 3);
            line.put("price", 0);
            assertThat(mutate(client, "PUT", path, Map.of("items", List.of(line))).statusCode()).isEqualTo(200);
            assertThat(readSubscription(client, id).path("total").decimalValue()).isEqualByComparingTo("30.75");
            line = subscriptionLine(other, 4);
            line.put("price", 0);
            assertThat(mutate(client, "PUT", path, Map.of("items", List.of(line))).statusCode()).isEqualTo(200);
            saved = readSubscription(client, id);
            assertThat(saved.path("total").decimalValue()).isEqualByComparingTo("31.96");
            assertThat(saved.path("items").path(0).path("price").decimalValue()).isEqualByComparingTo("7.99");
            assertThat(saved.path("endDate").asText()).isEqualTo("2026-03-01");
        }
    }

    @Test
    void rejectsInvalidSubscriptionsWithoutWritingDocumentsOrRows() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Проверки подписки")).path("id").asText();
            String tariff = createTariff(client, "Доступный для проверок", "100.00", 30, true);
            String unavailable = createTariff(client, "Недоступный", "100.00", 30, false);
            String deleted = createTariff(client, "Удалённый", "100.00", 30, true);
            assertThat(mutate(client, "DELETE", "/api/catalogs/Tariffs/" + deleted, Map.of()).statusCode()).isEqualTo(200);
            Map<String, Object> valid = subscriptionBody(customer, LocalDate.of(2026, 1, 1), List.of(subscriptionLine(tariff, 1)));
            String id = createSubscription(client, valid).path("id").asText();
            JsonNode before = readSubscription(client, id);
            String documents = metadata.getDocumentDescriptor(Subscription.class).tableName();
            String rows = metadata.getDocumentDescriptor(Subscription.class).tabularSections().getFirst().tableName();
            long documentsBefore = jdbc.queryForObject("select count(*) from " + documents, Long.class);
            long rowsBefore = jdbc.queryForObject("select count(*) from " + rows, Long.class);
            Map<String, Object> body = new HashMap<>(valid);
            body.remove("customer");
            assertSubscriptionRejected(client, body, "customer", null);
            body = new HashMap<>(valid);
            body.put("customer", UUID.randomUUID().toString());
            assertSubscriptionRejected(client, body, "customer", null);
            body = new HashMap<>(valid);
            body.put("items", List.of());
            assertSubscriptionRejected(client, body, null, "хотя бы одну строку");
            for (int periods : new int[]{0, -1}) {
                body = subscriptionBody(customer, LocalDate.of(2026, 1, 1), List.of(subscriptionLine(tariff, periods)));
                assertSubscriptionRejected(client, body, null, "число периодов должно быть больше нуля");
            }
            Map<String, Object> missingPeriods = subscriptionLine(tariff, 1);
            missingPeriods.put("periods", null);
            assertSubscriptionRejected(client, subscriptionBody(customer, LocalDate.of(2026, 1, 1),
                    List.of(missingPeriods)), null, "число периодов должно быть больше нуля");
            for (String invalidTariff : new String[]{unavailable, deleted, UUID.randomUUID().toString()}) {
                assertSubscriptionRejected(client, subscriptionBody(customer, LocalDate.of(2026, 1, 1),
                        List.of(subscriptionLine(invalidTariff, 1))), null, "выберите доступный тариф");
            }
            HttpResponse<String> invalidUpdate = mutate(client, "PUT", "/api/documents/Subscriptions/" + id,
                    Map.of("startDate", "2030-01-01", "items", List.of(subscriptionLine(tariff, 0))));
            assertThat(invalidUpdate.statusCode()).isEqualTo(400);
            assertThat(readSubscription(client, id)).isEqualTo(before);
            assertThat(jdbc.queryForObject("select count(*) from " + documents, Long.class)).isEqualTo(documentsBefore);
            assertThat(jdbc.queryForObject("select count(*) from " + rows, Long.class)).isEqualTo(rowsBefore);
        }
    }

    @Test
    void validatesSubscriptionWithoutPersistingIt() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Предварительная проверка")).path("id").asText();
            String tariff = createTariff(client, "Для предварительной проверки", "100.00", 30, true);
            String documents = metadata.getDocumentDescriptor(Subscription.class).tableName();
            long before = jdbc.queryForObject("select count(*) from " + documents, Long.class);
            HttpResponse<String> valid = mutate(client, "POST", "/api/documents/Subscriptions/validate",
                    subscriptionBody(customer, LocalDate.now(), List.of(subscriptionLine(tariff, 2))));
            assertThat(valid.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(valid.body()).path("valid").asBoolean()).isTrue();
            HttpResponse<String> invalid = mutate(client, "POST", "/api/documents/Subscriptions/validate",
                    subscriptionBody(customer, LocalDate.now(), List.of(subscriptionLine(tariff, 0))));
            assertThat(invalid.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(invalid.body()).path("valid").asBoolean()).isFalse();
            assertThat(mapper.readTree(invalid.body()).path("formErrors").toString()).contains("Строка 1");
            assertThat(jdbc.queryForObject("select count(*) from " + documents, Long.class)).isEqualTo(before);
        }
    }

    @Test
    void fillsAndRecalculatesSubscriptionThroughTypedRepository() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Репозиторий подписок")).path("id").asText();
            String tariff = createTariff(client, "Тариф для репозитория", "53.31", 7, true);
            Subscription subscription = new Subscription();
            subscription.setCustomer(Ref.of(Customer.class, UUID.fromString(customer)));
            subscription.setStartDate(null);
            subscription.setDate(null);
            subscription.setStatus(null);
            SubscriptionLine line = new SubscriptionLine();
            line.setTariff(Ref.of(Tariff.class, UUID.fromString(tariff)));
            line.setPeriods(2);
            subscription.setItems(List.of(line));
            Subscription saved = subscriptions.save(subscription);
            Subscription loaded = subscriptions.findActiveById(saved.getId()).orElseThrow();
            assertThat(loaded.getStartDate()).isEqualTo(LocalDate.now());
            assertThat(loaded.getDate().toLocalDate()).isEqualTo(LocalDate.now());
            assertThat(loaded.getStatus()).isEqualTo(SubscriptionStatus.DRAFT);
            assertThat(loaded.getNumber()).startsWith("SUB-");
            assertThat(loaded.getEndDate()).isEqualTo(LocalDate.now().plusDays(14));
            assertThat(loaded.getTotal()).isEqualByComparingTo("106.62");
            assertThat(loaded.getItems()).hasSize(1);
            assertThat(loaded.getItems().getFirst().getPrice()).isEqualByComparingTo("53.31");
            assertThat(loaded.getItems().getFirst().getLineNumber()).isEqualTo(1);
        }
    }

    @Test
    void acceptsFreeSubscriptionAndRejectsCalculationOverflow() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Границы расчётов")).path("id").asText();
            String free = createTariff(client, "Бесплатный для подписки", "0.00", 1, true);
            JsonNode saved = createSubscription(client, subscriptionBody(customer, LocalDate.now(),
                    List.of(subscriptionLine(free, 1))));
            assertThat(saved.path("total").isNumber()).isTrue();
            assertThat(saved.path("total").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(saved.path("endDate").asText()).isEqualTo(LocalDate.now().plusDays(1).toString());
            String expensive = createTariff(client, "Переполнение суммы", "9999999999999.99", 1, true);
            assertSubscriptionRejected(client, subscriptionBody(customer, LocalDate.now(),
                    List.of(subscriptionLine(expensive, 2))), "total", null);
            String longTariff = createTariff(client, "Переполнение даты", "1.00", Integer.MAX_VALUE, true);
            assertSubscriptionRejected(client, subscriptionBody(customer, LocalDate.now(),
                    List.of(subscriptionLine(longTariff, Integer.MAX_VALUE))), "endDate", null);
        }
    }

    private String createTariff(HttpClient client, String name, String price, int days, boolean available) throws Exception {
        return create(client, "Tariffs", Map.of("description", name, "price", new BigDecimal(price),
                "periodDays", days, "available", available)).path("id").asText();
    }

    private Map<String, Object> subscriptionLine(String tariff, int periods) {
        return new HashMap<>(Map.of("tariff", tariff, "periods", periods));
    }

    private Map<String, Object> subscriptionBody(String customer, LocalDate start, List<Map<String, Object>> items) {
        return new HashMap<>(Map.of("customer", customer, "startDate", start.toString(),
                "status", subscriptionStatusId(SubscriptionStatus.DRAFT), "items", items));
    }

    private String subscriptionStatusId(SubscriptionStatus status) {
        return metadata.getEnumerationDescriptor(SubscriptionStatus.class).values().stream()
                .filter(value -> value.name().equals(status.name())).findFirst().orElseThrow().id().toString();
    }

    private JsonNode createSubscription(HttpClient client, Map<String, Object> body) throws Exception {
        HttpResponse<String> response = mutate(client, "POST", "/api/documents/Subscriptions", body);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private JsonNode readSubscription(HttpClient client, String id) throws Exception {
        HttpResponse<String> response = get(client, "/api/documents/Subscriptions/" + id);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private void assertSubscriptionRejected(HttpClient client, Map<String, Object> body, String field, String message)
            throws Exception {
        HttpResponse<String> response = mutate(client, "POST", "/api/documents/Subscriptions", body);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(400);
        JsonNode errors = mapper.readTree(response.body());
        if (field != null) assertThat(errors.path("fieldErrors").has(field)).isTrue();
        if (message != null) assertThat(errors.path("formErrors").toString()).contains(message);
    }

    @Test
    void exposesRevenueAndSubscriptionPostingActions() throws Exception {
        try (HttpClient client = adminClient()) {
            JsonNode shell = mapper.readTree(get(client, "/api/divkit/shell").body());
            assertThat(shell.path("navigation").toString()).contains("Выручка", "/registers/revenue");
            assertThat(get(client, "/api/divkit/registers/Revenue").statusCode()).isEqualTo(200);
            JsonNode form = mapper.readTree(get(client, "/api/divkit/documents/Subscriptions/new").body());
            JsonNode meta = form.findValue("form").path("meta");
            assertThat(meta.path("postable").asBoolean()).isTrue();
            assertThat(meta.path("actions").path("post").asText()).isEqualTo("primary");
            assertThat(meta.path("actions").path("unpost").asText()).isEqualTo("menu");
        }
    }

    @Test
    void postsSubscriptionIntoAccountAndRevenueAndFiltersTurnoverByPeriod() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Проведение подписки")).path("id").asText();
            String other = create(client, "Customers", customerBody("Другой лицевой счёт")).path("id").asText();
            fund(client, customer, "500.00");
            String first = createTariff(client, "Первый оплаченный тариф", "120.10", 30, true);
            String second = createTariff(client, "Второй оплаченный тариф", "19.99", 7, true);
            Map<String, Object> body = subscriptionBody(customer, LocalDate.of(2026, 1, 10),
                    List.of(subscriptionLine(first, 2), subscriptionLine(second, 3)));
            body.put("date", "2026-01-10T12:00:00");
            String id = createSubscription(client, body).path("id").asText();
            HttpResponse<String> preview = get(client, "/api/documents/Subscriptions/" + id + "/posting-preview");
            assertThat(preview.statusCode()).withFailMessage(preview.body()).isEqualTo(200);
            assertThat(mapper.readTree(preview.body()).path("registers").size()).isEqualTo(2);
            assertThat(balance(client, customer)).isEqualByComparingTo("500.00");
            assertThat(movementCount(id)).isZero();
            assertThat(revenueMovementCount(id)).isZero();

            assertThat(subscriptionCommand(client, id, "post").path("posted").asBoolean()).isTrue();
            assertThat(balance(client, customer)).isEqualByComparingTo("199.83");
            assertThat(balance(client, other)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(movementCount(id)).isEqualTo(1);
            assertThat(revenueMovementCount(id)).isEqualTo(2);
            JsonNode revenue = revenue(client, customer);
            assertThat(revenue.size()).isEqualTo(2);
            for (JsonNode row : revenue) {
                boolean isFirst = row.path("tariff").asText().equals(first);
                assertThat(row.path("amount").decimalValue()).isEqualByComparingTo(isFirst ? "240.20" : "59.97");
                assertThat(row.path("periods").asInt()).isEqualTo(isFirst ? 2 : 3);
                assertThat(row.path("customer").asText()).isEqualTo(customer);
            }
            assertThat(revenue(client, other).size()).isZero();
            JsonNode outside = mapper.readTree(get(client, "/api/registers/Revenue/turnover"
                    + "?from=2026-02-01T00:00:00&to=2026-02-28T23:59:59&customer=" + customer).body());
            assertThat(outside.size()).isZero();
        }
    }

    @Test
    void rejectsInsufficientFundsWithoutPartialPostingAndAcceptsExactBalance() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Недостаточный баланс")).path("id").asText();
            String tariff = createTariff(client, "Тариф за сто", "100.00", 30, true);
            String id = createPostingSubscription(client, customer, tariff, 1);
            JsonNode before = readSubscription(client, id);
            fund(client, customer, "99.99");
            HttpResponse<String> rejected = mutate(client, "POST", "/api/documents/Subscriptions/" + id + "/post", Map.of());
            assertThat(rejected.statusCode()).withFailMessage(rejected.body()).isEqualTo(400);
            assertThat(rejected.body()).contains("Недостаточно средств");
            assertThat(readSubscription(client, id)).isEqualTo(before);
            assertThat(balance(client, customer)).isEqualByComparingTo("99.99");
            assertThat(movementCount(id)).isZero();
            assertThat(revenueMovementCount(id)).isZero();
            assertThat(revenue(client, customer).size()).isZero();
            fund(client, customer, "0.01");
            subscriptionCommand(client, id, "post");
            assertThat(balance(client, customer)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(revenue(client, customer).path(0).path("amount").decimalValue()).isEqualByComparingTo("100.00");
        }
    }

    @Test
    void unpostsAndRepostsSubscriptionWithoutDuplicateRevenueOrInactiveBalanceCredit() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Перепроведение подписки")).path("id").asText();
            fund(client, customer, "200.00");
            String tariff = createTariff(client, "Тариф перепроведения", "100.00", 30, true);
            String id = createPostingSubscription(client, customer, tariff, 1);
            subscriptionCommand(client, id, "post");
            subscriptionCommand(client, id, "post");
            assertThat(balance(client, customer)).isEqualByComparingTo("100.00");
            assertThat(movementCount(id)).isEqualTo(1);
            assertThat(revenueMovementCount(id)).isEqualTo(1);
            assertThat(revenue(client, customer).path(0).path("periods").asInt()).isEqualTo(1);
            Subscription changed = subscriptions.findActiveById(UUID.fromString(id)).orElseThrow();
            changed.getItems().getFirst().setPeriods(2);
            posting.repost(changed);
            assertThat(balance(client, customer)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(readSubscription(client, id).path("items").path(0).path("periods").asInt()).isEqualTo(2);
            assertThat(readSubscription(client, id).path("total").decimalValue()).isEqualByComparingTo("200.00");
            assertThat(revenue(client, customer).path(0).path("amount").decimalValue()).isEqualByComparingTo("200.00");
            assertThat(revenue(client, customer).path(0).path("periods").asInt()).isEqualTo(2);
            assertThat(movementCount(id)).isEqualTo(1);
            assertThat(revenueMovementCount(id)).isEqualTo(1);
            subscriptionCommand(client, id, "unpost");
            assertThat(balance(client, customer)).isEqualByComparingTo("200.00");
            assertThat(movementCount(id)).isZero();
            assertThat(revenueMovementCount(id)).isZero();
            assertThat(revenue(client, customer).size()).isZero();

            assertThat(mutate(client, "PUT", "/api/documents/Subscriptions/" + id,
                    Map.of("items", List.of(subscriptionLine(tariff, 3)))).statusCode()).isEqualTo(200);
            HttpResponse<String> rejected = mutate(client, "POST", "/api/documents/Subscriptions/" + id + "/post", Map.of());
            assertThat(rejected.statusCode()).withFailMessage(rejected.body()).isEqualTo(400);
            assertThat(rejected.body()).contains("Недостаточно средств");
            assertThat(balance(client, customer)).isEqualByComparingTo("200.00");
            assertThat(movementCount(id)).isZero();
            assertThat(revenueMovementCount(id)).isZero();
            assertThat(mutate(client, "PUT", "/api/documents/Subscriptions/" + id,
                    Map.of("items", List.of(subscriptionLine(tariff, 2)))).statusCode()).isEqualTo(200);
            subscriptionCommand(client, id, "post");
            subscriptionCommand(client, id, "post");
            assertThat(balance(client, customer)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(revenue(client, customer).path(0).path("amount").decimalValue()).isEqualByComparingTo("200.00");
            assertThat(revenue(client, customer).path(0).path("periods").asInt()).isEqualTo(2);
            assertThat(movementCount(id)).isEqualTo(1);
            assertThat(revenueMovementCount(id)).isEqualTo(1);
        }
    }

    @Test
    void cancelledSubscriptionCreatesNoMovementsAndRepostingCancellationReturnsFunds() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Отмена подписки")).path("id").asText();
            String tariff = createTariff(client, "Отменённый тариф", "100.00", 30, true);
            Map<String, Object> body = subscriptionBody(customer, LocalDate.of(2026, 1, 10),
                    List.of(subscriptionLine(tariff, 1)));
            body.put("status", subscriptionStatusId(SubscriptionStatus.CANCELLED));
            body.put("date", "2026-01-10T12:00:00");
            String id = createSubscription(client, body).path("id").asText();
            subscriptionCommand(client, id, "post");
            subscriptionCommand(client, id, "post");
            assertThat(movementCount(id)).isZero();
            assertThat(revenueMovementCount(id)).isZero();
            assertThat(balance(client, customer)).isEqualByComparingTo(BigDecimal.ZERO);
            subscriptionCommand(client, id, "unpost");
            assertThat(mutate(client, "PUT", "/api/documents/Subscriptions/" + id,
                    Map.of("status", subscriptionStatusId(SubscriptionStatus.DRAFT))).statusCode()).isEqualTo(200);
            fund(client, customer, "100.00");
            subscriptionCommand(client, id, "post");
            assertThat(balance(client, customer)).isEqualByComparingTo(BigDecimal.ZERO);
            Subscription cancelled = subscriptions.findActiveById(UUID.fromString(id)).orElseThrow();
            cancelled.setStatus(SubscriptionStatus.CANCELLED);
            posting.repost(cancelled);
            assertThat(readSubscription(client, id).path("statusDisplay").asText()).isEqualTo("Отменена");
            assertThat(balance(client, customer)).isEqualByComparingTo("100.00");
            assertThat(movementCount(id)).isZero();
            assertThat(revenueMovementCount(id)).isZero();
            assertThat(revenue(client, customer).size()).isZero();
        }
    }

    @Test
    void failedSubscriptionRepostPreservesDocumentBothRegistersAndCustomerIsolation() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Сохранность проведения")).path("id").asText();
            String other = create(client, "Customers", customerBody("Без денег")).path("id").asText();
            fund(client, customer, "200.00");
            String tariff = createTariff(client, "Защищённая подписка", "100.00", 30, true);
            String id = createPostingSubscription(client, customer, tariff, 1);
            subscriptionCommand(client, id, "post");
            JsonNode before = readSubscription(client, id);
            JsonNode revenueBefore = revenue(client, customer);
            Subscription document = subscriptions.findActiveById(UUID.fromString(id)).orElseThrow();
            assertThatThrownBy(() -> posting.post(document)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already posted");
            document.getItems().getFirst().setPeriods(3);
            assertThatThrownBy(() -> posting.repost(document)).isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Недостаточно средств");
            document.getItems().getFirst().setPeriods(1);
            document.setCustomer(Ref.of(Customer.class, UUID.fromString(other)));
            assertThatThrownBy(() -> posting.repost(document)).isInstanceOf(ValidationException.class);
            assertThat(readSubscription(client, id)).isEqualTo(before);
            assertThat(revenue(client, customer)).isEqualTo(revenueBefore);
            assertThat(balance(client, customer)).isEqualByComparingTo("100.00");
            assertThat(balance(client, other)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(movementCount(id)).isEqualTo(1);
            assertThat(revenueMovementCount(id)).isEqualTo(1);
        }
    }

    @Test
    void concurrentSubscriptionsCannotOverdrawOneCustomerAccount() throws Exception {
        try (HttpClient client = adminClient(); var workers = Executors.newFixedThreadPool(2)) {
            String customer = create(client, "Customers", customerBody("Одновременное списание")).path("id").asText();
            fund(client, customer, "100.00");
            String tariff = createTariff(client, "Конкурирующий тариф", "60.00", 30, true);
            String first = createPostingSubscription(client, customer, tariff, 1);
            String second = createPostingSubscription(client, customer, tariff, 1);
            Subscription a = subscriptions.findActiveById(UUID.fromString(first)).orElseThrow();
            Subscription b = subscriptions.findActiveById(UUID.fromString(second)).orElseThrow();
            CountDownLatch start = new CountDownLatch(1);
            var left = workers.submit(() -> tryConcurrentPosting(a, start));
            var right = workers.submit(() -> tryConcurrentPosting(b, start));
            start.countDown();
            assertThat(List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(balance(client, customer)).isEqualByComparingTo("40.00");
            assertThat(movementCount(first) + movementCount(second)).isEqualTo(1);
            assertThat(revenueMovementCount(first) + revenueMovementCount(second)).isEqualTo(1);
            assertThat(revenue(client, customer).path(0).path("amount").decimalValue()).isEqualByComparingTo("60.00");
            assertThat(readSubscription(client, first).path("posted").asBoolean()
                    ^ readSubscription(client, second).path("posted").asBoolean()).isTrue();
        }
    }

    @Test
    void postsFreeSubscriptionWithoutFundsAndCountsTariffPeriods() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Бесплатное подключение")).path("id").asText();
            String tariff = createTariff(client, "Бесплатный оплаченный тариф", "0.00", 7, true);
            String id = createPostingSubscription(client, customer, tariff, 2);
            subscriptionCommand(client, id, "post");
            assertThat(balance(client, customer)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(revenue(client, customer).path(0).path("amount").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(revenue(client, customer).path(0).path("periods").asInt()).isEqualTo(2);
            assertThat(revenueMovementCount(id)).isEqualTo(1);
        }
    }

    @Test
    void updatesSubscriptionStatusesAtDateBoundariesWithoutChangingFinancialData() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Регламентные статусы")).path("id").asText();
            String tariff = createTariff(client, "Срок для статусов", "100.00", 30, true);
            fund(client, customer, "100.00");
            String id = createPostingSubscription(client, customer, tariff, 1);
            subscriptionCommand(client, id, "post");
            JsonNode before = readSubscription(client, id);
            JsonNode turnover = revenue(client, customer);
            // Changing the catalog must not recalculate an already purchased subscription in the job.
            assertThat(mutate(client, "PUT", "/api/catalogs/Tariffs/" + tariff,
                    Map.of("available", false, "periodDays", 90, "price", "999.00")).statusCode()).isEqualTo(200);
            statusJob("2026-01-10T00:00:00Z").execute();
            assertThat(readSubscription(client, id).path("statusDisplay").asText()).isEqualTo("Активна");
            JsonNode active = readSubscription(client, id);
            statusJob("2026-02-08T20:59:59Z").execute(); // Still February 8 in Moscow.
            assertThat(readSubscription(client, id)).isEqualTo(active);
            statusJob("2026-02-08T21:00:00Z").execute(); // February 9 = start + 30 days.
            JsonNode expired = readSubscription(client, id);
            assertThat(expired.path("statusDisplay").asText()).isEqualTo("Истекла");
            statusJob("2026-02-09T12:00:00Z").execute();
            assertThat(readSubscription(client, id)).isEqualTo(expired);
            for (String field : List.of("date", "number", "customer", "startDate", "endDate", "total", "items", "posted")) {
                assertThat(expired.path(field)).as(field).isEqualTo(before.path(field));
            }
            assertThat(balance(client, customer)).isEqualByComparingTo("0.00");
            assertThat(revenue(client, customer)).isEqualTo(turnover);
            assertThat(movementCount(id)).isEqualTo(1);
            assertThat(revenueMovementCount(id)).isEqualTo(1);
        }
    }

    @Test
    void statusJobSkipsDraftCancelledDeletedAndFutureSubscriptions() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Исключения регламента")).path("id").asText();
            String tariff = createTariff(client, "Бесплатный регламент", "0.00", 30, true);
            String draft = createPostingSubscription(client, customer, tariff, 1);
            String deleted = createPostingSubscription(client, customer, tariff, 1);
            subscriptions.deleteById(UUID.fromString(deleted));
            var cancelledBody = subscriptionBody(customer, LocalDate.of(2026, 1, 10), List.of(subscriptionLine(tariff, 1)));
            cancelledBody.put("status", subscriptionStatusId(SubscriptionStatus.CANCELLED));
            String cancelled = createSubscription(client, cancelledBody).path("id").asText();
            subscriptionCommand(client, cancelled, "post");
            String future = createSubscription(client, subscriptionBody(customer, LocalDate.of(2026, 3, 1),
                    List.of(subscriptionLine(tariff, 1)))).path("id").asText();
            subscriptionCommand(client, future, "post");
            Subscription futureSnapshot = subscriptions.findActiveById(UUID.fromString(future)).orElseThrow();
            statusJob("2026-02-10T00:00:00Z").execute();
            assertThat(subscriptions.findById(UUID.fromString(draft)).orElseThrow().getStatus()).isEqualTo(SubscriptionStatus.DRAFT);
            assertThat(subscriptions.findById(UUID.fromString(deleted)).orElseThrow().getStatus()).isEqualTo(SubscriptionStatus.DRAFT);
            assertThat(readSubscription(client, cancelled).path("statusDisplay").asText()).isEqualTo("Отменена");
            assertThat(subscriptions.findActiveById(UUID.fromString(future)).orElseThrow().getVersion()).isEqualTo(futureSnapshot.getVersion());
            assertThat(readSubscription(client, future).path("statusDisplay").asText()).isEqualTo("Черновик");
        }
    }

    @Test
    void statusUpdateDoesNotOverwriteConcurrentCancellationOrUnposting() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Гонка статуса")).path("id").asText();
            String tariff = createTariff(client, "Нулевой тариф гонки", "0.00", 30, true);
            String id = createPostingSubscription(client, customer, tariff, 1);
            subscriptionCommand(client, id, "post");
            Subscription stale = subscriptions.findActiveById(UUID.fromString(id)).orElseThrow();
            Subscription cancelled = subscriptions.findActiveById(UUID.fromString(id)).orElseThrow();
            cancelled.setStatus(SubscriptionStatus.CANCELLED);
            posting.repost(cancelled);
            assertThat(statusWriter.updateIfUnchanged(stale, SubscriptionStatus.ACTIVE)).isFalse();
            assertThat(readSubscription(client, id).path("statusDisplay").asText()).isEqualTo("Отменена");
            String second = createPostingSubscription(client, customer, tariff, 1);
            subscriptionCommand(client, second, "post");
            Subscription unposted = subscriptions.findActiveById(UUID.fromString(second)).orElseThrow();
            subscriptionCommand(client, second, "unpost");
            assertThat(statusWriter.updateIfUnchanged(unposted, SubscriptionStatus.ACTIVE)).isFalse();
            assertThat(readSubscription(client, second).path("statusDisplay").asText()).isEqualTo("Черновик");
            String edited = createPostingSubscription(client, customer, tariff, 1);
            subscriptionCommand(client, edited, "post");
            Subscription previousDates = subscriptions.findActiveById(UUID.fromString(edited)).orElseThrow();
            Subscription newDates = subscriptions.findActiveById(UUID.fromString(edited)).orElseThrow();
            newDates.setStartDate(LocalDate.of(2026, 4, 1));
            posting.repost(newDates);
            assertThat(statusWriter.updateIfUnchanged(previousDates, SubscriptionStatus.EXPIRED)).isFalse();
            assertThat(readSubscription(client, edited).path("startDate").asText()).isEqualTo("2026-04-01");
            assertThat(readSubscription(client, edited).path("statusDisplay").asText()).isEqualTo("Черновик");
        }
    }

    private SubscriptionStatusJob statusJob(String instant) {
        return new SubscriptionStatusJob(subscriptions, statusWriter,
                Clock.fixed(Instant.parse(instant), ZoneId.of("Europe/Moscow")), backgroundJobs);
    }

    @Test
    void exposesDashboardAndCancellationFormsThroughGeneratedUi() throws Exception {
        try (HttpClient client = adminClient()) {
            var dashboard = get(client, "/api/divkit/dashboard");
            assertThat(dashboard.statusCode()).isEqualTo(200);
            assertThat(dashboard.body()).contains("Обзор подписок", "timeRange", "Выручка по тарифам", "Выручка по клиентам", "Последние подписки");
            assertThat(get(client, "/api/actions/documents/Subscriptions").body())
                    .contains("cancel-subscription-row", "Причина отмены", "textarea", "required");
            String customer = create(client, "Customers", customerBody("Форма отмены")).path("id").asText();
            String tariff = createTariff(client, "Форма отмены", "0.00", 30, true);
            String id = createPostingSubscription(client, customer, tariff, 1);
            assertThat(get(client, "/api/divkit/documents/Subscriptions/" + id).body())
                    .contains("cancel-subscription", "Отменить подписку", "Причина отмены");
        }
        try (HttpClient anonymous = newClient()) {
            assertThat(get(anonymous, "/api/divkit/dashboard").statusCode()).isEqualTo(401);
        }
    }

    @Test
    void cancellationWithReasonRefundsBothRegistersAndPreservesPurchaseSnapshot() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Отмена с причиной")).path("id").asText();
            String tariff = createTariff(client, "Отключён после покупки", "100.00", 30, true);
            fund(client, customer, "200.00");
            String id = createPostingSubscription(client, customer, tariff, 1);
            subscriptionCommand(client, id, "post");
            JsonNode before = readSubscription(client, id);
            assertThat(mutate(client, "PUT", "/api/catalogs/Tariffs/" + tariff,
                    Map.of("available", false, "price", "900.00", "periodDays", 90)).statusCode()).isEqualTo(200);
            var result = mutate(client, "POST", "/api/actions/documents/Subscriptions/cancel-subscription?id=" + id,
                    Map.of("inputs", Map.of("reason", "  Клиент отказался от сервиса  ")));
            assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(200);
            JsonNode cancelled = readSubscription(client, id);
            assertThat(cancelled.path("statusDisplay").asText()).isEqualTo("Отменена");
            assertThat(cancelled.path("cancellationReason").asText()).isEqualTo("Клиент отказался от сервиса");
            for (String field : List.of("items", "startDate", "endDate", "total", "date", "posted")) {
                assertThat(cancelled.path(field)).as(field).isEqualTo(before.path(field));
            }
            assertThat(balance(client, customer)).isEqualByComparingTo("200.00");
            assertThat(revenue(client, customer)).isEmpty();
            assertThat(movementCount(id)).isZero();
            assertThat(revenueMovementCount(id)).isZero();
            assertThat(mutate(client, "POST", "/api/actions/documents/Subscriptions/cancel-subscription-row?id=" + id,
                    Map.of("inputs", Map.of("reason", "Повторная отмена"))).statusCode()).isEqualTo(200);
            assertThat(readSubscription(client, id)).isEqualTo(cancelled);
            statusJob("2027-01-01T00:00:00Z").execute();
            assertThat(readSubscription(client, id)).isEqualTo(cancelled);
        }
    }

    @Test
    void rejectsEmptyCancellationReasonAndCancelsDraftWithoutFinancialMovements() throws Exception {
        try (HttpClient client = adminClient()) {
            String customer = create(client, "Customers", customerBody("Причина обязательна")).path("id").asText();
            String tariff = createTariff(client, "Черновик отмены", "100.00", 30, true);
            String id = createPostingSubscription(client, customer, tariff, 1);
            String action = "/api/actions/documents/Subscriptions/cancel-subscription-row?id=" + id;
            JsonNode before = readSubscription(client, id);
            for (String reason : List.of("", "   ", "x".repeat(1001))) {
                assertThat(mutate(client, "POST", action, Map.of("inputs", Map.of("reason", reason))).statusCode()).isEqualTo(400);
                assertThat(readSubscription(client, id)).isEqualTo(before);
            }
            assertThat(mutate(client, "POST", action, Map.of("inputs", Map.of("reason", "Ошибка оформления"))).statusCode()).isEqualTo(200);
            assertThat(readSubscription(client, id).path("posted").asBoolean()).isFalse();
            assertThat(readSubscription(client, id).path("statusDisplay").asText()).isEqualTo("Отменена");
            assertThat(balance(client, customer)).isEqualByComparingTo("0.00");
            assertThat(movementCount(id)).isZero();
            assertThat(revenueMovementCount(id)).isZero();
        }
    }

    @Test
    void registersHourlyBackgroundTaskAndEnqueuesItOnStartup() {
        var recurring = jobStorage.getRecurringJobs().stream()
                .filter(job -> job.getId().equals("subscription-statuses")).findFirst().orElseThrow();
        assertThat(recurring.getScheduleExpression()).isEqualTo("0 * * * *");
        assertThat(recurring.getJobDetails().getClassName()).isEqualTo(su.onno.jobs.BackgroundTask.class.getName());
        assertThat(recurring.getJobDetails().getMethodName()).isEqualTo("execute");
        assertThat(jobStorage.countJobs(org.jobrunr.jobs.states.StateName.ENQUEUED)).isPositive();
    }

    private boolean tryConcurrentPosting(Subscription document, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            posting.post(document);
            return true;
        } catch (ValidationException | IllegalStateException insufficientFunds) {
            return false;
        }
    }

    private String createPostingSubscription(HttpClient client, String customer, String tariff, int periods) throws Exception {
        Map<String, Object> body = subscriptionBody(customer, LocalDate.of(2026, 1, 10),
                List.of(subscriptionLine(tariff, periods)));
        body.put("date", "2026-01-10T12:00:00");
        return createSubscription(client, body).path("id").asText();
    }

    private void fund(HttpClient client, String customer, String amount) throws Exception {
        String id = createPayment(client, customer, amount, PaymentMethod.CARD).path("id").asText();
        paymentCommand(client, id, "post");
    }

    private JsonNode subscriptionCommand(HttpClient client, String id, String command) throws Exception {
        HttpResponse<String> response = mutate(client, "POST", "/api/documents/Subscriptions/" + id + "/" + command, Map.of());
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private JsonNode revenue(HttpClient client, String customer) throws Exception {
        HttpResponse<String> response = get(client, "/api/registers/Revenue/turnover"
                + "?from=2026-01-01T00:00:00&to=2026-12-31T23:59:59&customer=" + customer);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        JsonNode rows = mapper.readTree(response.body());
        assertThat(rows.isArray()).isTrue();
        return rows;
    }

    private long revenueMovementCount(String documentId) {
        return jdbc.queryForObject("select count(*) from " + metadata.getRegisterDescriptor(Revenue.class).tableName()
                + " where _document_ref = ? and _active = true", Long.class, UUID.fromString(documentId));
    }

    private JsonNode createPayment(HttpClient client, String customer, String amount, PaymentMethod method)
            throws Exception {
        HttpResponse<String> response = mutate(client, "POST", "/api/documents/Payments",
                paymentBody(customer, amount, method));
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private Map<String, Object> paymentBody(String customer, String amount, PaymentMethod method) {
        return new HashMap<>(Map.of("customer", customer, "amount", new BigDecimal(amount),
                "paymentMethod", paymentMethodId(method)));
    }

    private String paymentMethodId(PaymentMethod method) {
        return metadata.getEnumerationDescriptor(PaymentMethod.class).values().stream()
                .filter(value -> value.name().equals(method.name())).findFirst().orElseThrow().id().toString();
    }

    private JsonNode paymentCommand(HttpClient client, String id, String command) throws Exception {
        HttpResponse<String> response = mutate(client, "POST", "/api/documents/Payments/" + id + "/" + command, Map.of());
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private BigDecimal balance(HttpClient client, String customer) throws Exception {
        HttpResponse<String> response = get(client, "/api/registers/CustomerAccount/balance?customer=" + customer);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        JsonNode rows = mapper.readTree(response.body());
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.size()).isLessThanOrEqualTo(1);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.path(0).path("amount").decimalValue();
    }

    private long movementCount(String documentId) {
        return jdbc.queryForObject("select count(*) from " + metadata.getRegisterDescriptor(CustomerAccount.class).tableName()
                + " where _document_ref = ? and _active = true", Long.class, UUID.fromString(documentId));
    }

    private HttpClient adminClient() throws Exception {
        HttpClient client = newClient();
        assertThat(login(client, "test-admin-password").statusCode()).isEqualTo(200);
        return client;
    }

    private JsonNode newForm(HttpClient client, String catalog) throws Exception {
        HttpResponse<String> response = get(client, "/api/divkit/catalogs/" + catalog + "/new");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode form = mapper.readTree(response.body()).findValue("form");
        assertThat(form).isNotNull();
        return form;
    }

    private JsonNode create(HttpClient client, String catalog, Map<String, Object> body) throws Exception {
        HttpResponse<String> response = mutate(client, "POST", "/api/catalogs/" + catalog, body);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private Map<String, Object> customerBody(String name) {
        return new HashMap<>(Map.of("description", name, "status", statusId(CustomerStatus.ACTIVE),
                "registrationDate", LocalDate.now().toString()));
    }

    private String statusId(CustomerStatus status) {
        return metadata.getEnumerationDescriptor(CustomerStatus.class).values().stream()
                .filter(value -> value.name().equals(status.name())).findFirst().orElseThrow().id().toString();
    }

    private HttpResponse<String> mutate(HttpClient client, String method, String path,
                                         Map<String, Object> body) throws Exception {
        JsonNode csrf = mapper.readTree(get(client, "/api/auth/csrf").body());
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient newClient() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(HttpClient client, String password) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "username", "test-admin", "password", password, "remember", false));
        HttpRequest request = HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
