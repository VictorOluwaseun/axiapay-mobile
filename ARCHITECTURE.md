# AxiaPay Android architecture

This refactor keeps the current UI and illustrative data. It does not connect banks, billers, a production wallet ledger, background execution, durable offline storage, real SMS transport, or push notifications.

## Boundaries

The project remains one Gradle application module. Named packages provide explicit inward dependencies; `ArchitectureTest` guards those imports. Separate Gradle modules can be introduced when build isolation is useful, without changing these contracts.

- **Domain** contains value models, the repository contract, error types and reusable demo invariants. It imports neither Android nor Compose, JSON, concrete storage, presentation, or provider libraries. `Currency.NGN` is explicit; all monetary values are integer kobo. Outgoing activity is negative and incoming funding is positive.
- **Application** provides one named action or query per use case. `PreparePayment` validates and prepares raw input; `SubmitDemoPayment` revalidates it and asks the repository to atomically apply a domain transition. Other use cases cover completion, batch retry, plan status, visibility, reminders, notification read state, and observation. Sample provider/price choices live in the demo application catalog.
- **Data** owns illustrative fixtures, snapshot serialization and storage coordination. `DemoSessionRepository` serializes access to one session aggregate, invokes the supplied transition exactly once, saves before publishing, and leaves the observable snapshot unchanged if storage fails. It does not decide whether a payment is allowed or sufficient funds exist.
- **Presentation** owns immutable MVI state and typed intents. `AxiaViewModel` invokes use cases, handles explicit results, and exposes only a read-only `StateFlow`. `PaymentFormPresenter` is a pure form reducer; business validation is delegated to the application layer. `SavedNavigationStore` handles Android-specific restoration behind a small presentation port.
- **UI** renders state and emits intents. It may format values and own framework-only scroll, modal animation and native date-picker state. It cannot mutate the session or invoke a repository. Icons and human-readable labels are presentation details, mapped from domain types at this boundary.
- **Composition root** is Hilt, initialized by `AxiaPayApplication`. `MainActivity` is an `@AndroidEntryPoint` and retrieves its two `@HiltViewModel` owners from the default lifecycle-aware `ViewModelProvider`. Shared modules in `di` provide platform dependencies, presentation collaborators and plain application use cases. Flavor-specific modules select repositories and payment/SMS action implementations. No production hand-written ViewModel factory or service locator remains.

```text
User action → AxiaIntent → AxiaViewModel → application use case
                                             ↓
                                    domain transition/policy
                                             ↓
                                    SessionRepository contract
                                             ↓
                              data: atomic demo snapshot commit
                                             ↓
                           read-only state → lifecycle-aware Compose
```

## Flavor boundaries and backend migration

The single `environment` dimension contains dev, demo, staging and prod, each with a unique application ID. Debug/release remain build types, independent of environment and payment simulation. `BuildConfig.FLAVOR` identifies the selected environment at the composition boundary; it is not a business rule or an authentication mechanism. The resource namespace remains stable.

- `src/main/` contains the shared UI, domain/application contracts and current sample implementations.
- `src/demo/java/.../di/FlavorPaymentModule.kt` permanently selects demo repository and action bindings.
- `src/nonDemo/java/.../di/FlavorPaymentModule.kt` is added only to dev/staging/prod source sets. It currently selects exactly the same demo wiring; future integrations are selected here.
- Flavor resource overlays distinguish launcher labels without changing in-app screens.

For each future backend feature, introduce the required application/domain port and its backend adapter, then bind it in the non-demo composition path. Preserve the existing simulated implementation and bind it in the demo path. Do not silently fall back to sample balances/results on a backend failure. Authentication, payment execution and authoritative financial decisions must move to their backend operations together; replacing a session repository alone is not sufficient. Payment and plan actions now enter through narrow suspending application contracts in `PaymentActions.kt`. The flavor Hilt module binds these to the existing synchronous local use cases; future backend adapters can suspend without changing the View or intent contract. Preference and connectivity use cases remain concrete local actions. These contracts do not make the demo ledger authoritative.

Backend URLs, credentials, real SMS and release signing remain unconfigured. The non-demo Hilt module is a migration boundary, not a live backend implementation. Keep demo copy and data until each feature's real source is connected; share UI changes so demo remains representative instead of freezing a duplicate UI. The common Android regression suite currently runs against all four flavors; move sample-specific expectations into demo tests as backend behavior arrives, retaining shared behavior coverage.

## State ownership and delivery

The repository owns the session. The ViewModel owns navigation, forms, filters, queries and presentation errors. Its render state includes the repository's immutable session snapshot; Views have no write access. The form's logical request ID is allocated when opening the form and retained through edits, review, and restoration.

Confirmation is accepted only while showing a reviewed payment form. Successful completion replaces that form with a receipt identified by the payment ID only if the user has not navigated or edited the form since submission. The receipt is ordinary render state: recomposition, collection restart and activity recreation never submit an operation. Repository idempotency also handles a process interruption after committing a payment but before saving receipt navigation.

There is no one-shot payment/navigation event channel to replay. Errors are visible state, consumed by an explicit `DismissError` intent or successful navigation. They survive configuration changes in the ViewModel; process restoration restores form/session values and revalidates input, rather than replaying an error or action. The native date picker opens only from its button click and returns a date-selection intent.

Payment/plan/SMS work runs in `viewModelScope`. A main-thread intent entry point sets `isProcessing` before launching, admitting one action at a time. Duplicate taps during suspension cannot launch another operation. Progress is temporary render state and is not persisted. A navigation revision limits result consumption to the originating screen visit; late results still update repository state without replacing newer forms or drafts. Returning to an identical screen is a new visit, not permission to consume an older result.

Cancellation propagates from the action and the storage boundary. It is never converted to payment failure. Unexpected execution exceptions map to `OperationUnconfirmed`, retaining the cause without inventing a final failed payment. Future backend implementations must persist and reconcile uncertain outcomes under the same logical ID; ViewModel lifetime is not a durable execution queue. Application contracts require main-safe work: adapters move blocking I/O to injected dispatchers, while the existing SavedStateHandle-backed demo remains on the main thread. Do not move Android saved-state access onto an arbitrary I/O thread.

`DemoSessionRepository` defensively copies and seals payments, plans and SMS lists at load/seed/commit boundaries. Callers cannot mutate a published snapshot by retaining an input list or casting an exposed list to a mutable interface. The repository saves the owned snapshot before emitting it; failed storage cannot publish partial changes. No stability annotation is used to pretend arbitrary Kotlin collections are immutable.

The Activity collects state with `collectAsStateWithLifecycle`. Compose lifecycle changes do not create a new repository while the existing ViewModel is retained.

## Demo payment invariants

- An immediate payment either debits once and records success, or records a waiting request without a debit.
- Adding funds does not automatically execute waiting payments. `CompleteWaitingPayment` is the explicit recovery action and is idempotent for completed requests.
- Balance, receipt and plan changes form one atomic repository transition. Concurrent requests cannot spend the same available funds.
- A reused request ID with changed details returns a conflict. A matching request returns its existing receipt.
- The illustrative batch uses one stable ID for its only actionable recipient, Chidi. The two completed example rows are never resubmitted.
- Scheduling records a receipt and plan without debiting. Daily/weekly/monthly recurrence is anchored to the original start date; January 31 advances to February's last day and then March 31. Pause/resume changes a plan's enabled value explicitly.
- Storage failures retain their cause in the application result and show a safe UI message. They never turn an unsaved change into success.

These are **local demo simulation rules**, not authoritative financial controls. Real balances, authentication, authorization, pricing, reservations, settlement, server idempotency and reconciliation must be enforced by the backend. No authentication or real provider result is simulated as verified in this build.

## Offline simulation boundaries

`ObserveInternetConnection` reads the domain `InternetConnectionRepository` contract. Its Android adapter observes the app’s default network using `ConnectivityManager`, requiring both internet capability and Android validation. The ViewModel invokes `UpdateConnectionState` for changes, without executing any requests. `PrepareSmsPayment` validates the input and sample airtime/data eligibility, then stores an immutable `SmsRequest` alongside the session. `SendDemoSms` and `ReconcileDemoSms` apply pure `DemoSmsPolicy` transitions through the same repository; there is no real transport adapter yet. Only the composition root knows concrete storage implementations.

Transport (`Draft`, `SendFailed`, `Sent`) is separate from the payment ledger. A sent request with no ledger result remains uncertain. Sending, reopening the sheet, recreation and reconnecting cannot debit the wallet. Explicit simulated backend confirmation records one payment with the original ID. Matching confirmations replay the result, and ordinary online submission cannot bypass the SMS outbox. Insufficient funds keep the established explicit funding/completion rule.

A draft binds payment terms, SIM and deadline on review. Retries retain these terms and the ID, are limited to three attempts, and cannot start after the 15-minute demo deadline. A sent request can still reconcile after its deadline; expiry must not erase an uncertain outcome. Closing a sheet never cancels a sent request. Sample fees are zero, amounts are exact kobo and carrier SMS costs are outside the payment ceiling.

`NavigationState.draft` retains one unsubmitted form, including partial input, recurrence and SIM selection. It restores and revalidates without running a command. `SavedStateSessionStore` adds optional offline/cache/outbox fields to the existing snapshot format, so older demo snapshots still load. Outbox progress is committed before state publication. The storage lifetime is intentionally unchanged: activity/process saved-state restoration, not a production durable queue.

The ViewModel coordinates intents and the UI renders immutable state. The offline palette is a read-only composition local, not business state; all palette tokens resolve per composition so separate bottom-sheet and dialog windows inherit it. Pixel checks cover the home/navigation and SMS sheet. SMS composition/send permissions, encoding, authentication, gateway acknowledgements and backend status lookup remain separate integration work. SMS and backend replies remain explicitly simulated.

## Live connectivity lifecycle

`PlatformModule` provides `AndroidInternetConnectionRepository` using the application’s `ConnectivityManager`; the ViewModel and UI never access Android network APIs. Initial connectivity is read before the first frame. Automatic mode refreshes stale saved connection metadata; an explicit preview override is respected. Both paths leave form/navigation and payment data intact. One ViewModel-scoped flow subscribes to network events and releases its callback when the ViewModel is cleared; activity recreation reuses that owner. No polling, HTTP probes, new dependencies or SMS permissions are needed.

Registration, the initial snapshot, and callbacks are serialized on the main thread. The callback uses supplied capability values, not racy synchronous queries. `onAvailable` alone does not claim internet access. Losing validation or the current default switches offline; a late loss/capability callback for the previous default cannot override a new network. App-blocked networks are also treated as offline on versions delivering that callback. Identical values are coalesced. Monitoring errors retain their cause and show an explicit error with offline fallback in Automatic mode; manual previews keep their selected mode.

The system’s validation is a connectivity signal, not proof that a particular payment backend is reachable. See [Android network-state guidance](https://developer.android.com/develop/connectivity/network-ops/reading-network-state) and [NetworkCallback reference](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback). `ACCESS_NETWORK_STATE` is declared as a normal permission.

## Demo connection override

`ConnectionMode` defaults to Automatic. `SetConnectionMode` reads current real connectivity through the domain port and atomically saves the preference and effective offline state. `UpdateConnectionState` continues receiving real network events while manual mode is active. Both use `DemoConnectionPolicy` to resolve the effective mode and only timestamp entry into offline mode; neither submits, reconciles or changes financial state.

Profile and the connection/outbox panel render the same radio group and emit `AxiaIntent.SetConnectionMode`. A persistent banner labels forced modes as previews and provides a return path to Automatic. The preference is an optional saved-session field (older snapshots default to Automatic); it survives recreation with the existing session lifetime, not permanent storage. Force online enables demo operations without claiming real network access. No real SMS or backend functionality is added.

## Persistence and extension

`SavedStateSessionStore` keeps a versioned JSON snapshot in `SavedStateHandle`; `SavedNavigationStore` saves a separate presentation snapshot in the same handle. JSON objects remain inside adapters. Session history has the same Android saved-state lifetime as the original demo and resets with a fresh session. This is not durable financial storage and is not suitable for an unbounded history.

For production, introduce backend application ports and repositories for authoritative operations. Keep the Views and intent contracts stable where possible; adapt server responses into domain values. Model unknown provider outcomes explicitly and reconcile by request ID. Introduce a durable database/outbox and workers when offline, scheduled or bulk execution is implemented. Do not put those workflows in a ViewModel or grow the demo repository into a provider service.

## Verification

```sh
./gradlew verifyAllFlavors
# A smaller iteration loop for one flavor:
./gradlew :app:verifyDev
# Reformat all Kotlin source sets and build scripts with the pinned formatter:
./gradlew formatKotlin
# Requires a running, explicitly selected emulator:
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDevDebugAndroidTest
```

`verifyAllFlavors` includes formatting, unit tests, Android lint, debug/release APK assembly and instrumentation APK compilation for all four environments. Device execution is separate and must target an emulator. `.github/workflows/android.yml` runs each flavor's verification in a CI matrix with read-only permissions and pinned action commits. It does not deploy, sign release builds or run device tests. A first run requires network access to resolve the pinned tooling.

Run `./gradlew :app:compileDemoReleaseKotlin -PcomposeReports=true --rerun-tasks` to emit Compose compiler reports under `app/build/reports/compose/` and `compose-metrics/` for inspection of stability and skipping. Enable this property only for one compile task at a time: variants and test compilations share report filenames. These reports are diagnostics, not proof of frame-rate improvements; profile actual device behavior before optimizing. No experimental compiler flags or unverified stability promises are added.


Use-case tests cover exact amounts, invalid recipients/providers/quotes/dates, atomic failure recovery, idempotency, concurrent spending/funding, waiting-payment recovery, batch retry and recurring calendar boundaries. Installed-variant tests verify application IDs and launcher labels for each flavor. ViewModel tests cover review/confirm, duplicate intents, restoration, error consumption and preferences. Android tests exercise the existing UI and actual saved-state codecs, offline draft/SIM/outbox restoration, SMS failure/retry/reconnect, and greyscale rendering. Network tests cover unvalidated/captive-portal capability states, validation loss, Wi-Fi/cellular handover, app blocking, cold/restored connection state and subscription cleanup. Emulator tests disable and restore real radios while checking greyscale/colour restoration, open forms, saved drafts and pending SMS requests. Offline use-case tests verify no debit on transport, eligibility, retry limits, expiry, cross-channel duplicate rejection, concurrent reconciliation, funding follow-up and storage failure recovery.

All Kotlin sources and Gradle scripts are checked with ktfmt 0.65 (`org.jetbrains.kotlinx:ktfmt`) in Kotlin language style, resolved by Gradle rather than a machine-local installation. Suspension tests cover duplicate admission, cancellation, navigation changes and recovery; snapshot tests cover mutable aliases and atomic cancellation. Control-flow review uses the Kotlin syntax tree to verify no nested `if` expressions; guard clauses and named presentation values keep branching flat.

References: [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations), [UI state and events](https://developer.android.com/topic/architecture/ui-layer), [ViewModel saved state](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate).

Coroutine and compiler references: [Android coroutine practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices), [Compose compiler options](https://kotlinlang.org/docs/compose-compiler-options.html).

## Account-entry UI prototype

`AuthViewModel` independently owns welcome, sign-in, create-account, verification and completion state. Compose emits typed `AuthIntent` values; the ViewModel calls `PrepareDemoAccess` and `CompleteDemoAccess`. These use cases validate preview inputs and invoke the domain `DemoAccessRepository` contract. The data adapter `SimulatedAccessRepository` provides cancellable simulated latency, without storage or network side effects. Each flavor source set selects this adapter through `FlavorPaymentModule`; production authentication is not implemented by this demo contract.

`MainActivity` collects both account-preview and payment state with lifecycle awareness. Entering or reopening auth changes only the auth route. It neither resets the payment owner nor replays any payment command. The profile shortcut deliberately preserves the current dashboard navigation, including the open profile sheet, so returning from the preview restores the same context. Automatic connectivity continues to update the shared palette while auth is visible.

Only the demo-entry Boolean is saved. No phone, name, verification code, challenge, loading state or error is serialized. Configuration changes retain the ViewModel; process restoration of an unfinished form returns to Welcome without starting an operation. Code input never auto-submits. Duplicate continue/resend actions are rejected during work. Navigation cancels the owned job and invalidates late results by screen visit; cancellation propagates and unexpected failures retain their cause in transient state. No result creates an account, verifies phone ownership, issues a token, or authorizes a payment.

The auth forms use string resources, one paste-friendly code field, labelled inputs, explicit errors, accessible progress and a scrollable inset-aware layout. Device checks cover create-account/sign-in, code reset, input correction, recreation, greyscale, and returning to the funded wallet with an existing payment draft. Unit checks cover validation, leading zeros, overlapping taps, cancellation, failure/retry and non-sensitive saved state.

## Hilt component ownership

- `SingletonComponent`: `PlatformModule` provides the device-zone `Clock` and the application-context connectivity adapter. Sharing the adapter does not add a permanent subscription: its cold Flow still registers/releases one callback per observing ViewModel lifetime.
- `ViewModelComponent`: the compiled flavor's `FlavorPaymentModule` provides the session repository, simulated auth adapter, and payment/SMS action contracts. `SessionRepository` is `@ViewModelScoped`, so observation, funding, payments, plans and SMS all use one atomic snapshot within that owner; another ViewModel key receives a separate repository.
- `PaymentPresentationModule` provides a `@ViewModelScoped` `NavigationStore` with that component's `SavedStateHandle`, the form presenter and the existing UUID request generator. `PaymentUseCaseModule` and `AuthUseCaseModule` construct plain application classes through explicit parameters; neither domain nor application imports a DI framework.

Hilt supplies each ViewModel's handle from its owner and key. Existing session/navigation keys and JSON codecs are unchanged. Auth and payment owners stay independent. Configuration changes retain the owners, and saved-state restoration reconstructs the same snapshots without replaying commands. Mutable demo sessions must not move to `SingletonComponent` or `ActivityRetainedComponent` without a deliberate ownership and persistence change.

The two flavor modules intentionally list the same bindings today. This duplication is limited to environment selection; all policies, adapters and use cases remain shared. It allows non-demo bindings to change independently without removing demo actions or scattering flavor conditions through shared code. As each backend feature arrives, replace the relevant binding and its contracts deliberately; the current demo auth contract does not become real authentication automatically.

The catalog pins Hilt runtime/compiler/plugin 2.60.1 together and KSP 2.3.12. KSP runs with AGP 9.4.1 built-in Kotlin; no legacy kapt plugin or second Kotlin Android plugin is added. Compilation targets Java 17. The existing Compose compiler version remains unchanged. Compatibility is verified with all flavor debug/release APKs, generated graphs, unit tests and lint, plus installed-graph device flows. `HiltGraphTest` catches accidental unscoped or global session repositories by funding one owner and asserting another owner remains unchanged through auth navigation and Activity recreation.

References: [Hilt setup](https://dagger.dev/hilt/gradle-setup), [Hilt ViewModels](https://dagger.dev/hilt/view-model), [Hilt components](https://dagger.dev/hilt/components), [KSP releases](https://github.com/google/ksp/releases/tag/2.3.12).
