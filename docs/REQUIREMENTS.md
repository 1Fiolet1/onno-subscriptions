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

Дополнительно `postsFreeSubscriptionWithoutFundsAndCountsTariffPeriods` проверяет проведение
бесплатной подписки без денег с учётом количества периодов в выручке.

Принятое уточнение для цены: в сохранённой подписке используется цена тарифа на момент
оформления, при смене тарифа — цена нового тарифа. Новая строка с уже сохранённым в подписке
тарифом использует его прежнюю цену. Проверка: `keepsQuotedPriceAndFillsPriceWhenTariffChanges`.
Дополнительно `acceptsFreeSubscriptionAndRejectsCalculationOverflow` проверяет нулевую сумму
и отклонение расчётов за пределами допустимого диапазона денежных значений и даты.

Дополнительные проверки качества справочников: `rejectsInvalidCatalogDataWithoutWritingRows`
проверяет обязательное имя/название, формат почты, неотрицательную цену, положительную
длительность периода и отсутствие новых строк при отклонённом сохранении.

Даты и значения по умолчанию проверены в новых формах. На этапе 2 через интерфейс созданы
явно тестовые записи «Тест UI — Иван Иванов» и «Тест UI — Месячный»; их сохранность проверена
после повторного запуска приложения. Автоматические тесты работают в отдельной временной базе.

На этапе 3 через браузер создан `PAY-00000000001` на 5 000,25 ₽ для того же тестового клиента.
Черновик не создаёт остаток, проведение даёт 5 000,25 ₽, отмена возвращает 0 ₽,
повторное проведение восстанавливает 5 000,25 ₽ и оставляет одно приходное движение.
После перезапуска JAR данные платежа, остаток и движение совпали с контрольным чтением.
Дефолт способа оплаты проверяется в данных новой формы и при сохранении; особенности
его отображения и встроенной даты описаны в [ONNO_KNOWN_LIMITATIONS.md](ONNO_KNOWN_LIMITATIONS.md).

На этапе 4 через браузер создана и изменена `SUB-00000000001`: две строки на 2 и 1 месячный
период, итог 5 971,50 ₽, начало 11.10.2026, окончание 10.12.2026. Срок — максимум 60 дней,
а не сумма 90 дней. После перезапуска JAR весь документ и строки совпали с контрольным чтением;
прежний платёж, остаток 5 000,25 ₽ и одно приходное движение не изменились.
Это результат этапа 4 до реализации финансовой логики.

На этапе 5 через браузер проверены отказ при нехватке средств, дополнительный платёж
`PAY-00000000002` на 1 000,00 ₽, проведение, отмена проведения и повторное проведение
`SUB-00000000001`. После проведения остаток 28,75 ₽, три активных движения лицевого счёта
(два прихода и расход), две строки выручки с итогом 5 971,50 ₽ и 3 периодами.
Отмена возвращает 6 000,25 ₽ и нулевую выручку; перепроведение не создаёт дублей.
После перезапуска JAR подписка, оба платежа, остаток, движения обоих регистров и обороты
совпали с контрольным чтением.

На этапе 6 настоящий обработчик JobRunr после запуска обновил `SUB-00000000001`
с «Черновик» на «Активна». Снимки до и после подтвердили совпадение всех остальных
данных документа и строк, платежей, остатка, движений и оборотов. В браузере проверены
статус, остаток 28,75 ₽ и фильтр выручки: вчера — 0 строк, текущий месяц — 2 строки.

Итоговая проверка этапа 7: оригинальное ТЗ сопоставлено со всеми строками таблицы,
чистая сборка прошла 33 теста без ошибок и пропусков. PostgreSQL и приложение перезапущены
с прежним volume; полный снимок бизнес-данных и контрольная сумма истории схемы сохранились.
Инструкции для сдачи: [SUBMISSION_CHECKLIST.md](SUBMISSION_CHECKLIST.md).
Публичный GitHub-репозиторий ещё не опубликован. Дополнительные пункты Page и ActionSpec
реализованы; VPS исключён по просьбе пользователя.

## Дополнительная функциональность

| Требование | Реализация | Проверка |
| --- | --- | --- |
| Page: показатели за период, диаграммы, последние документы | `SubscriptionsDashboard`, меню «Обзор» | `exposesDashboardAndCancellationFormsThroughGeneratedUi`; браузер: пустой период 0 ₽, последние 30 дней 6 000,25 ₽ поступлений и 5 971,50 ₽ выручки, названия клиентов и тарифов |
| ActionSpec: отмена с причиной в списке и форме | `SubscriptionView`, `SubscriptionCancellation`, `Subscription.cancellationReason` | `exposesDashboardAndCancellationFormsThroughGeneratedUi`; браузер: модальная форма, кнопка недоступна до заполнения причины |
| Возврат средств и отмена выручки, сохранение условий покупки, повторная отмена | Штатное `PostingService.repost`, отменённая подписка без движений | `cancellationWithReasonRefundsBothRegistersAndPreservesPurchaseSnapshot` |
| Проверка причины и отмена черновика | Причина обязательна, до 1000 символов; черновик сохраняется без проведения | `rejectsEmptyCancellationReasonAndCancelsDraftWithoutFinancialMovements` |

Всего после этапов 8–9: 36 интеграционных тестов без ошибок и пропусков.
