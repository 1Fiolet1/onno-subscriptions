# Требования → реализация → проверка

В `OnnoSubscriptionsApplicationTests` — 36 интеграционных тестов.

| Требование | Реализация | Автоматизированная проверка |
| --- | --- | --- |
| Приложение запускается и создаёт схему Onno в PostgreSQL | Стартеры и `application.properties` | `initializesOnnoSchemaInPostgreSql` |
| Пользовательский интерфейс и авторизация | Onno UI/Auth, русская локаль | `servesUiAndRussianConfiguration`, `authenticatesAdminAndKeepsSession`, `rejectsAnonymousAccessToProtectedApi` |
| Клиент: статус, почта, телефон, дата регистрации | `domain/Customer.java` | `createsAndUpdatesCustomerThroughGeneratedApi` |
| Тариф: цена, длительность, доступность | `domain/Tariff.java` | `persistsTariffPriceAndAvailability` |
| Перечисления статуса клиента, статуса подписки, способа оплаты | `CustomerStatus`, `SubscriptionStatus`, `PaymentMethod`; таблицы создаёт Onno | Старт контекста со сканированием всех перечислений; статус клиента дополнительно проверяется при сохранении и чтении |
| EntityView, колонки, порядок и ширина полей, подсказки, формат денег и дат | `CustomerView`, `TariffView` | `exposesCatalogNavigationAndNewFormDefaults`; дополнительно визуальная проверка форм и списков с данными |
| Разделы бокового меню | `SubscriptionsLayout` | `exposesCatalogNavigationAndNewFormDefaults`; дополнительно визуальная проверка на широком экране |
| Создание клиента и тарифа из интерфейса | Генерируемые формы и API Onno | API-сохранение в двух тестах выше; дополнительно создание обоих объектов через браузер |
| Платёж: клиент, сумма, способ оплаты | `domain/Payment.java`, `PaymentMethod` | `creditsOnlySelectedCustomerAndPreviewDoesNotPersist`, `rejectsInvalidPaymentsWithoutDocumentsOrMovements` |
| Регистр остатков: измерение клиент, ресурс сумма | `domain/CustomerAccount.java`, тип `BALANCE` | `creditsOnlySelectedCustomerAndPreviewDoesNotPersist`; чтение остатка через генерируемый REST API |
| Проведение платежа пополняет лицевой счёт клиента | `Payment.handlePosting`, типизированный приход | `creditsOnlySelectedCustomerAndPreviewDoesNotPersist` |
| Отмена проведения удаляет вклад платежа, повторное проведение не удваивает сумму | Стандартный `PostingService` Onno | `unpostsAndRepostsPaymentWithoutDuplicateMovements` |
| Ошибка проведения не меняет прежние движения и остаток | Стандартный `PostingService` и `Payment.rules()` | `failedCorePostingKeepsExistingPaymentAndBalance` |
| Дата документа при создании, сохранение явно заданной даты | Конструктор `Payment`, `OnFillingHandler`, стандартный путь создания Onno | `creditsOnlySelectedCustomerAndPreviewDoesNotPersist`, `unpostsAndRepostsPaymentWithoutDuplicateMovements` |
| EntityView платежа и лицевого счёта, меню и форматирование | `PaymentView`, `CustomerAccountView`, `SubscriptionsLayout` | `exposesPaymentAndAccountNavigationAndDefaults`; дополнительно формы и таблицы на широком и узком экране |
| Подписка: клиент, статус, начало, окончание, итог; строки: тариф, периоды, цена, сумма | `domain/Subscription.java`, `domain/SubscriptionLine.java`, `@TabularSection` | `calculatesSubscriptionTotalAndMaximumDurationFromTariffs`, `replacesSubscriptionRowsAndRecalculatesOnEverySave` |
| Дата документа и начала при создании, статус по умолчанию | Конструктор и инициализаторы `Subscription`, `OnFillingHandler` | `exposesSubscriptionNavigationAndNewFormDefaults`, `fillsAndRecalculatesSubscriptionThroughTypedRepository` |
| Цена из тарифа и расчёт суммы каждой строки и итога при каждой записи | `Subscription.beforeWrite`, `SubscriptionLifecycle.recalculate` | `calculatesSubscriptionTotalAndMaximumDurationFromTariffs`, `replacesSubscriptionRowsAndRecalculatesOnEverySave` |
| Окончание = начало + максимум длительностей строк | `SubscriptionLifecycle.recalculate` | `calculatesSubscriptionTotalAndMaximumDurationFromTariffs`; годовой и месячный тарифы, точная итоговая дата |
| Обязательный клиент, минимум одна строка, периоды > 0, тариф доступен | `Subscription.rules`, `SubscriptionLifecycle.rules`, `Validated`/`BusinessRule` | `rejectsInvalidSubscriptionsWithoutWritingDocumentsOrRows`, `validatesSubscriptionWithoutPersistingIt` |
| Расчёты и правила работают через типизированный репозиторий | `SubscriptionRepository`, сервис Spring и `ApplicationBeans` | `fillsAndRecalculatesSubscriptionThroughTypedRepository` |
| EntityView подписки и строк, колонки, порядок, ширина, подсказки, форматирование, меню | `SubscriptionView`, `SubscriptionsLayout` | `exposesSubscriptionNavigationAndNewFormDefaults`; дополнительно создание и редактирование через браузер |
| Выручка: оборотный регистр, измерения тариф/клиент, ресурсы сумма/периоды | `domain/Revenue.java`, `TURNOVER` | `postsSubscriptionIntoAccountAndRevenueAndFiltersTurnoverByPeriod` |
| Проведение подписки списывает итог и признаёт выручку по строкам | `Subscription.handlePosting`, типизированные расход и приходы | `postsSubscriptionIntoAccountAndRevenueAndFiltersTurnoverByPeriod` |
| Нехватка средств запрещает проведение без частичной записи | `BeforePostHandler`, `SubscriptionLifecycle.checkFunds`, транзакционный запрет отрицательного остатка Onno | `rejectsInsufficientFundsWithoutPartialPostingAndAcceptsExactBalance`, `concurrentSubscriptionsCannotOverdrawOneCustomerAccount` |
| Отменённая подписка не создаёт движений | Проверка `CANCELLED` в `checkFunds` и `handlePosting` | `cancelledSubscriptionCreatesNoMovementsAndRepostingCancellationReturnsFunds` |
| Отмена и перепроведение подписки согласованно изменяют оба регистра, без дублей | Стандартный `PostingService` Onno, учёт прежнего активного списания при проверке средств | `unpostsAndRepostsSubscriptionWithoutDuplicateRevenueOrInactiveBalanceCredit`, `failedSubscriptionRepostPreservesDocumentBothRegistersAndCustomerIsolation` |
| Выручка доступна в интерфейсе, суммы и периоды форматируются, есть меню | `RevenueView`, `SubscriptionsLayout` | `exposesRevenueAndSubscriptionPostingActions`; дополнительно просмотр двух движений через браузер |
| Регламентное обновление статусов по датам | `SubscriptionStatusJob`, `@ScheduledJob`, `BackgroundTask`, часовой cron и фоновый запуск при старте | `registersHourlyBackgroundTaskAndEnqueuesItOnStartup` |
| Границы срока, московская дата, повторный запуск без финансовых изменений | `Clock`, `SubscriptionStatusWriter`, частичная запись в таблицу Onno | `updatesSubscriptionStatusesAtDateBoundariesWithoutChangingFinancialData` |
| Пропуск черновиков, отменённых, удалённых и будущих подписок | Отбор в `SubscriptionStatusJob` | `statusJobSkipsDraftCancelledDeletedAndFutureSubscriptions` |
| Одновременная отмена/снятие проведения не перезаписывается задачей | Условия по версии, статусу, проведению и пометке удаления | `statusUpdateDoesNotOverwriteConcurrentCancellationOrUnposting` |

## Дополнительная функциональность

| Требование | Реализация | Проверка |
| --- | --- | --- |
| Page: показатели за период, диаграммы, последние документы | `SubscriptionsDashboard`, меню «Обзор» | `exposesDashboardAndCancellationFormsThroughGeneratedUi`; браузер: пустой период 0 ₽, последние 30 дней 6 000,25 ₽ поступлений и 5 971,50 ₽ выручки, названия клиентов и тарифов |
| ActionSpec: отмена с причиной в списке и форме | `SubscriptionView`, `SubscriptionCancellation`, `Subscription.cancellationReason` | `exposesDashboardAndCancellationFormsThroughGeneratedUi`; браузер: модальная форма, кнопка недоступна до заполнения причины |
| Возврат средств и отмена выручки, сохранение условий покупки, повторная отмена | Штатное `PostingService.repost`, отменённая подписка без движений | `cancellationWithReasonRefundsBothRegistersAndPreservesPurchaseSnapshot` |
| Проверка причины и отмена черновика | Причина обязательна, до 1000 символов; черновик сохраняется без проведения | `rejectsEmptyCancellationReasonAndCancelsDraftWithoutFinancialMovements` |

Всего после этапов 8–9: 36 интеграционных тестов без ошибок и пропусков.
