# AxiaPay Android

A native Jetpack Compose app with authenticated wallets, API-backed airtime and data purchases and separate everyday payment previews.

## Run

Open this folder in Android Studio, select `devDebug` or `demoDebug` in **Build Variants**, and run the `app` configuration on an Android 8.0+ device or emulator. The existing project uses Android SDK 37, Gradle 9.6.0 and the Gradle-managed Java 25 toolchain.

```sh
./gradlew :app:assembleDevDebug
./gradlew :app:verifyDev
# Verify formatting, unit tests, lint and debug/release/test APKs for every flavor:
./gradlew verifyAllFlavors
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDemoDebugAndroidTest

# Build the two debug APKs together.
./gradlew :app:assembleDebug
```

## Environments

| Flavor | Application ID | Launcher name | Long-term purpose |
| --- | --- | --- | --- |
| `dev` | `com.kesofty.axiapay.dev` | AxiaPay Dev | Development backend integrations |
| `demo` | `com.kesofty.axiapay.demo` | AxiaPay Demo | Permanent sample experience |

**Authentication, account/Home wallet balances and immediate single airtime and data purchases use the API in dev; demo keeps simulated authentication, balances and purchases.** Other payments, schedules and SMS remain labelled local previews.

The two apps can be installed together, each with a separate Android sandbox, saved session, drafts and preferences. Kotlin packages and the resource namespace remain `com.kesofty.axiapay`.

Each flavor has `Debug` and `Release` variants, such as `demoDebug` and `devRelease`. A flavor uses the same application ID for both build types, so debug and release of that same flavor do not install side by side. Flavor selects the environment; build type controls debugging and optimization. Release signing has not been configured.

Debug APKs are written to `app/build/outputs/apk/<flavor>/debug/`. Replace `Dev` in the commands above with `Demo` for the demo flavor. Use explicit flavor task names for unit tests, lint and device tests.

Hilt modules in `app/src/demo/java/` select the demo repositories and payment/SMS actions. Separate modules in `app/src/nonDemo/java/` are compiled into dev only. `FlavorAuthenticationModule` selects real account access, while `FlavorPaymentModule` retains payment simulations. Shared modules provide platform adapters, presentation collaborators and framework-independent use cases. This keeps one UI and sample implementation while giving future backend integrations a separate binding boundary. Environment selection stays at composition; the dev API URL is configured independently of demo. Credentials never belong in Gradle properties or source.

Source-set and variant conventions follow the [Android build-variant guide](https://developer.android.com/build/build-variants).

## Explore

- **Home:** wallet balance visibility, funding and sending, eight service shortcuts, upcoming plan and recent activity.
- **Payments:** searchable service directory, airtime/data/electricity/TV forms, batch-payment preview and SMS outbox.
- **Activity:** money-in, money-out and pending filters; individual receipts; completion of waiting payments after adding demo funds.
- **Plans:** create a dated one-time, daily, weekly or monthly payment; preview the next three dates; pause/resume plans; and change the demo reminder preference.
- **Notifications and profile:** sample renewal notices, waiting-payment prompts, and balance/reminder controls.

Airtime, data, electricity, TV, wallet funding and wallet/bank transfers have interactive form → review → receipt flows. Fields are validated locally. Payment providers, recipients, prices and simulated payment balances are illustrative. Non-demo account/Home balances and immediate single airtime and data purchases use the API. Other service, funding, transfer, scheduled and bulk forms remain previews. `ACCESS_NETWORK_STATE` observes Android connectivity without a runtime permission prompt. Authentication and wallet lookup send API requests; SMS remains simulated.

Education, insurance, airtime conversion and merchant payments have service previews. Offline mode has an interactive SMS simulation; internet detection is real, while SMS transport and durable outbox storage are not implemented. Scheduled execution, push notifications and actual airtime/data monitoring are not implemented.

Recurring plans keep their original calendar anchor: a plan starting on 31 January previews 28/29 February and 31 March. The chosen frequency is included in payment review, receipts and Plans, and survives activity recreation. Creating a recurring plan does not debit the wallet or launch a background payment.

The demo repository stores immutable session snapshots with integer NGN kobo. Payment use cases avoid debiting insufficient-funds requests and complete waiting requests only on an explicit action. Stable request IDs prevent repeated confirmation, restoration, or bulk retry from charging the same logical request twice. App-private atomic checkpoints restore the session, form and navigation after Android recreation. `SavedStateHandle` retains only small checkpoint identities; these files are demo snapshots, not a financial ledger.

## Offline SMS preview

Offline mode now follows Android’s validated internet connection automatically. Disconnect Wi-Fi and mobile data, or connect to a network without validated internet: the same screens, icons and controls become greyscale. When validated internet returns, the original colours return automatically. Automatic is the default. A persistent offline or manual-preview banner opens the outbox and connection controls.

**Temporary toggle:** tap **Home avatar → Connection preview**, or **Manage** in the banner. Choose **Force offline** for greyscale and the SMS demo, **Force online** for the coloured online demo, or **Automatic** to immediately follow the current real connection again. Forced modes are clearly labelled and persist through recreation for this demo session. They do not change device radios, send messages, or submit pending payments.

Open the SMS card at the bottom of Payments or **Home avatar → Open SMS outbox** to inspect pending requests. Automatic and manual mode changes preserve open form fields, saved drafts, request IDs, selected SIMs, plans and pending payment states. Reconnecting never sends an SMS, resubmits a payment or automatically confirms a result.

1. Open Airtime or Data, fill the familiar form, then review the payment and select a sample SIM.
2. **Review SMS request** saves the immutable request in the session outbox. Check the recipient/product, NGN maximum debit including the demo payment fee, gateway route and 15-minute send deadline.
3. **Send demo SMS** simulates transport only. **Try a failed-send scenario** demonstrates a failed attempt and an explicit retry with the same request ID, terms and SIM (maximum three attempts).
4. A sent request remains **Awaiting confirmation**. Activity and the outbox retain it across recreation. Reconnect, then **Check demo status**, or use **Simulate backend reply** offline. Only this explicit simulated backend result changes demo wallet funds. An insufficient-funds result waits for explicit completion after funding.

Other services and new schedules can be saved as a form draft and resumed after reconnecting. Existing schedules remain intact; changing accepted plans, funding, transfers and bulk execution need online mode, while payment execution still uses demo data. Force online only enables that demo path and does not provide real internet access. One in-progress form draft is retained, alongside the SMS outbox; both use private checkpoints tied to the existing task/session identity, not a production durable outbox.

No SMS is sent and no SMS permission is requested. No gateway phone number, wire format, enrollment/security scheme or verified reply channel is invented. The route and SIMs are sample data; carrier SMS charges are separate from the demo payment limit. The backend must authorize and settle real payments. Missing acknowledgement is not a failed payment, and reusing an SMS request through the online submission path is rejected in favour of reconciliation.

To verify automatic mode on an emulator, disable **both** Wi-Fi and mobile data (one can be a fallback for the other), then restore either connection. Merely having Wi-Fi turned on is not sufficient: Android must report validated internet. Android tests make these radio changes only on emulators and restore the prior settings afterward. Existing saved-state session limits still apply; this change does not add disk persistence.

## Architecture

The app follows Clean Architecture, MVVM with MVI, and Hilt dependency injection. See [ARCHITECTURE.md](ARCHITECTURE.md) for boundaries, request/effect delivery, persistence, and extension guidance.

| Package | Responsibility |
| --- | --- |
| `domain` | Android-independent payment models, exact-amount and recurrence policies, demo payment transitions, repository contract, explicit errors |
| `application` | Validation/preparation, submission, waiting-payment completion, plan/preferences actions, bulk validation/queue/run/retry, and observing the session |
| `data` | Original demo fixtures, atomic session repository and private checkpoint adapters |
| `presentation` | Immutable screen/form state, typed intents, form reducer, ViewModel and navigation restoration |
| `ui` | Stateless Compose screens, sheets, formatting, icons and theme |
| `di` | Hilt modules for platform dependencies, use cases, presentation collaborators and flavor bindings |

`ArchitectureTest` guards dependency direction and prevents mutable application state from returning to Views. Unit tests cover use cases, concurrency, persistence failure recovery, and MVI transitions. Device tests cover user flows, recreation, and snapshot serialization. Run `./gradlew formatKotlin` to apply the pinned ktfmt 0.65 style. `checkKotlinFormat` checks all Kotlin source sets (including shared non-demo wiring and Android tests) and Gradle scripts. `verifyAllFlavors` runs this check, unit tests, lint, debug/release builds and instrumentation APK compilation. Device test execution remains a separate emulator command. The GitHub Actions workflow runs a verification matrix for dev/demo without publishing or signing releases.

Payment actions use suspending application contracts and ViewModel-owned coroutines. An in-flight action shows progress and rejects repeated submissions; later results update the session without taking over a newly opened screen. Cancellation is not reported as payment failure, and restored state cannot replay a command. Repository snapshots own and protect their lists against external mutation. Both flavors still use the same demo operations.

Run `./gradlew :app:compileDemoReleaseKotlin -PcomposeReports=true --rerun-tasks` to generate Compose compiler stability/skipping reports in `app/build/reports/compose/` and `compose-metrics/`. Use them alongside device profiling; they do not replace runtime performance measurements.

## Visual system

Online: deep pine (#103F38), mint (#C5F2D8), soft white (#F6F8F7), peach (#FBE8D8), lavender (#ECE8F7), and ink (#173D35). Bundled variable Manrope typography uses explicit weight axes. The wallet is the principal visual anchor; quiet service tiles and unboxed transaction rows keep the rest readable. Vector icons and the launcher mark are drawn locally. The Manrope license is bundled in `app/src/main/assets/licenses`.

Offline: palette tokens and inline accents resolve to their greyscale luminance, including dialogs, controls and the native date picker. Selected and enabled controls retain contrast; typography and layout stay unchanged.

`ui/AxiaApp.kt` contains the four main screens, `ui/PaymentSheet.kt` renders payment forms/reviews, `ui/OfflineSheets.kt` renders connection/outbox/SMS states, `ui/Sheets.kt` contains the other sheets, `ui/components` contains the shared controls and vector icons, and `ui/theme` defines the palette and typography. `AxiaHomePreview` provides an Android Studio Compose preview.

## Account access

The existing phone/email and password/code selectors now drive all four real auth flows in **dev**:

- Signup with a password requests a signup challenge, then verifies it to create the account/session.
- Signup without a password requests and verifies the same contact-bound signup proof.
- Password sign-in exchanges the registered contact and exact password for a bearer session.
- Code sign-in requests a login-purpose challenge and verifies its ID/code without registering unknown contacts.

Local Nigerian numbers such as `080…` are sent as `+234…`; international E.164 numbers are accepted. Emails are trimmed/lowercased while preserving dots and plus aliases. New passwords use the API's 8–128 Unicode-code-point rule. Existing-password sign-in accepts 1–128 code points. Whitespace and case are never modified. The API accepts no signup name, so that field remains demo-only. Client checks improve feedback; server checks remain authoritative.

`AuthViewModel` calls the framework-independent `AccountAccess` application operations. The backend workflow uses `AuthenticationRepository` and `AuthenticationSessionStore`. The HTTP adapter sends only the published DTO fields, keeps challenge purpose/identity and leading code zeros, observes server expiry/resend timing and `Retry-After`, rejects malformed responses, and disables automatic retries, redirects and caching. Network failures never become demo success. A lost successful redemption response needs a fresh sign-in; the app does not replay it automatically.

Resending replaces the challenge ID. For password signup, the current API requires the password on each signup request; the app returns to the populated form and asks for the password again instead of retaining it after entering verification. Code-only signup and code login resend without a password. No code is auto-submitted.

Successful real sign-in, signup verification and stored-session restoration open Home directly with the verified account-owned wallet. **Profile → Account & sign out** opens account details; Back returns to Home. Other payment tools retain their independent, labelled previews. Tokens never enter UI state or `SavedStateHandle`. The token, expiry and normalized API identity are atomically encrypted with AES-GCM using Android Keystore and stored under `noBackupFilesDir`. API identity changes, expiry, unreadable ciphertext or lost keys require fresh access; no plaintext fallback is used. Startup/foreground checks first read Room snapshots bound to the unexpired protected session, then revalidate `/auth/me`. A connectivity failure keeps that account's saved dashboard visible; a cache miss retains explicit recovery. Sign-out removes local access after server revocation; failure remains retryable. Cleanup compares the original protected record so a late logout or invalid-session response cannot delete a newer login. Temporary Keystore/IO failures retain ciphertext for explicit retry. There is no refresh-token endpoint or client refresh loop.

Only the preview-entry navigation Boolean is saved. Form contacts, names, passwords, challenges and codes stay in memory across configuration changes, and unfinished forms restart at Welcome after process restoration. Auth state and the payment owner's drafts/pending requests remain independent. The offline greyscale palette and temporary connection overrides are preserved. Real code delivery, password reset and biometric/device enrollment are separate backend capabilities; this change integrates the four existing entry flows and bearer-session lifecycle, not fingerprint or recovery screens.

### Local development

Start the existing local API and its development services from the backend repository. The dev flavor defaults to `http://10.0.2.2:4000/v1/` for the Android emulator. Its network policy permits HTTP only for emulator/loopback hosts; public HTTP, user-supplied credentials in URLs, query strings, fragments and redirecting auth endpoints are rejected.

Local email and phone codes are captured in **Mailpit at http://127.0.0.1:58025**. Phone delivery appears as mail to `sms+<international digits>@sms.axiapay.test`; no SMS is sent to a handset. Use the latest code for the corresponding challenge.

`BuildConfig.API_BASE_URL` configures the shared API transport for authentication, wallets, purchases and public configuration. `BuildConfig.API_ALLOW_LOCAL_HTTP` permits the existing loopback/emulator HTTP policy in dev only. Demo retains separate simulated bindings.

Override a base URL, including the `/v1/` prefix and trailing slash, through untracked user Gradle properties or `-P`:

```sh
./gradlew :app:assembleDevDebug -Paxiapay.dev.apiBaseUrl=http://10.0.2.2:4000/v1/
```

For an explicitly selected USB device, use `adb -s <serial> reverse tcp:4000 tcp:4000` and a dev override of `http://127.0.0.1:4000/v1/`. The application IDs remain distinct; credentials cannot cross installed flavors.

The demo flavor retains its original name field, eight-character sample-password policy, any-six-digits verification and simulated completion. **Explore demo** never establishes a real identity. No backend outage is hidden by switching to that simulation.

### Auth verification

`verifyAllFlavors` checks all debug/release/test APKs, generated Hilt graphs, lint, formatting and unit suites. The new unit tests exercise all contacts/methods, HTTP shapes, Unicode/exact passwords, leading zeros, purpose-bound proof, cooldowns, storage/revocation failure, cancellation, stale results, API isolation and session recovery. Android storage tests use isolated real Keystore records, test tampering/recreation and verify network policy.

Run the real local API UI check only on an explicitly selected dev emulator without an existing auth session:

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e runLocalAuth true \
  -e class com.kesofty.axiapay.ServerAuthFlowUiTest,com.kesofty.axiapay.AuthenticationSessionStorageTest,com.kesofty.axiapay.HiltGraphTest \
  com.kesofty.axiapay.dev.test/androidx.test.runner.AndroidJUnitRunner
```

This opt-in test creates synthetic local accounts through the real API, reads only matching Mailpit messages, checks phone/email signup with/without passwords, both sign-in methods, Activity recreation, and server-confirmed logout. It honors the real resend cooldown and never logs credentials/codes. Ordinary demo UI checks remain on the demo APK; non-demo instrumented APK compilation does not imply real-server test execution.

HTTP dependencies are pinned to OkHttp/OkHttp Coroutines **5.3.0**, whose published sources use coroutines 1.10.2 and Kotlin 2.2.x, compatible with this app's existing Kotlin/coroutines toolchain. JSON uses the Android platform implementation, with the pinned reference JSON library only in JVM tests. No additional DI or serialization plugin is required. References: [OkHttp 5.3.0 sources](https://github.com/lysine-dev/okhttp/tree/parent-5.3.0), [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [Android no-backup storage](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir()).

## Hilt wiring

`AxiaPayApplication` owns the Hilt application component. `MainActivity` is an Android entry point and obtains `@HiltViewModel` owners through its default `ViewModelProvider`. The hand-written production factories have been removed. Domain/application classes remain plain Kotlin; shared modules provide them without DI annotations. Unit tests continue to construct controlled collaborators directly.

The clock and application-context connectivity adapter are application-scoped. Each payment ViewModel receives one `@ViewModelScoped` session repository and navigation store with checkpoint identities in its own Hilt-supplied `SavedStateHandle`. Auth retains its separate owner and handle. No mutable wallet session becomes a singleton.

Hilt runtime/compiler/plugin **2.60.1** and KSP **2.3.12** are pinned in the version catalog. The project keeps AGP 9.4.1 built-in Kotlin and the existing Compose compiler plugin; Java/Kotlin bytecode target 17 is used for the Hilt toolchain. `verifyAllFlavors` also compiles the generated Hilt graphs. `HiltGraphTest` exercises repository sharing within an owner, isolation across owners, and recreation against each installed flavor's actual graph, with the ordinary Android test runner. No separate Hilt test graph or test-only bindings are needed for these regression flows.

Reference: [Hilt Gradle setup](https://dagger.dev/hilt/gradle-setup), [Hilt ViewModel scopes](https://dagger.dev/hilt/view-model), [AGP built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin).


## Bulk payment preview

Payments opens a service picker for Airtime, Data, Electricity or TV. Each quick list contains one payment type: enter a recipient, tap **+ Add another recipient**, and continue on the same screen. The next row reuses the provider and amount or plan, while identifiers stay blank. No batch name or separate setup is required. Inline edits update the running total; review includes the final entered row and ignores only an unused new row. Immediate individual payment forms offer the same add-recipient action, carrying the current form into the matching list. Try demo list adds four same-service examples. Each list supports up to 1,000 recipients. Data and TV quotes are illustrative, with no provider purchase. Review shows the demo wallet reservation and offers funding when needed. The optional failure scenario declines the last recipient once, releases its reservation and enables a separately reviewed failed-only retry. Activity’s Batches filter opens saved per-recipient progress, including two sample histories.

Independent service drafts, inline editor input, logical request IDs, reservations and outcomes use private, atomic demo checkpoints with small identities in SavedStateHandle. Leaving the screen does not interrupt an active run. Cancellation or losing internet retains queued/processing rows; restoration and reconnection never submit or resume them automatically. Use Resume demo explicitly. Bulk SMS, durable background execution and live providers are not implemented. History is bounded to 20 batches for this local demo; it is not a production storage or throughput limit. Both flavors retain this simulation through their existing Hilt composition boundary.

Named reusable groups and group membership are deferred product work; quick bulk payment does not create a group.


## Import bulk recipients

In the **demo flavor only**, the import screen includes **Download CSV sample** and **Download Excel sample**. The standard files have three synthetic recipients for the selected service. **Download 1,000 recipients · CSV** generates a full-size synthetic list on the phone; airtime uses ₦100 per row so a fresh demo wallet can cover it. Data and TV samples retain their catalog prices and may require explicit demo funding. Android's Save dialog lets you put it in Downloads or another folder, then choose it for import. Samples are bundled in the demo APK and work offline. Downloading never adds recipients or submits a payment. Cancellation and save failures leave the current draft and preview intact. Dev contains neither this capability nor its sample assets.

Choose a service in Payments → Bulk payments, then **Import list**. CSV, modern Excel (`.xlsx`) and Excel 97–2003 (`.xls`) are read locally through Android’s document picker; no storage permission or upload is required. Excel uses the first worksheet in tab order. Files are limited to 2 MB, 1,000 recipient rows plus the header, 16 columns and 256 characters per cell; XLSX expansion is bounded to 8 MB. Password protection and formulas are unsupported.

Required headings (case-insensitive; column order is flexible):

| Service | Columns |
| --- | --- |
| Airtime | `recipient`, `provider`, `amount` |
| Data / TV | `recipient`, `provider`, `product` |
| Electricity | `recipient`, `provider`, `product`, `amount` |

`name` is optional; `phone`, `meter`, `smartcard`, `network`, `plan` and `account_type` are accepted aliases. Optional `service` must match the chosen list. Use the demo provider/product choices shown in the import guide. Amounts are naira decimals without currency symbols or thousands separators; supplied fixed-product amounts must match the demo quote. CSV supports comma separators, quoted fields, UTF-8 and BOM-marked UTF-16. In Excel, enter account identifiers as **Text** including the leading zero: numeric identifiers are flagged instead of guessed.

The preview shows source row numbers, valid entries, duplicates and actionable errors. Select the valid rows to add; invalid and unselected rows are excluded explicitly. Selection must fit the existing 1,000-recipient list, including unfinished input. Imports preserve manual recipients and the current editor, and never submit a payment. Local files can be imported offline; cloud-only documents may need downloading first. Rotation retains the preview; process recreation retains the draft and returns to recipient entry without reopening a file grant. The original file and preview are not persisted or uploaded.


## Large-batch verification

Recipient entry has a pinned search field, compact lazy rows with stable IDs, multi-selection and confirmed removal. Search never changes the full-list total. Imports have ready/needs-attention/selected filters and explicit select-available/clear actions. Review and progress can search the full batch; every recipient retains its outcome and attempt identity. Large demos advance in groups of up to 25, with atomic reservation/refund checkpoints rather than a save per recipient.

`BulkScaleTest` covers all four services at 1,000 rows, overflow, exact totals, last-row editing and interrupted/resumed execution. `RecipientDocumentParserTest` exercises 1,000 and 1,001 rows in CSV, XLSX and XLS. `BulkScaleDeviceTest` covers the full Hilt/UI/import/offline/retry path and asserts the lazy viewport does not compose the whole list. `BulkCheckpointTest` exercises large draft/reservation restoration, small saved-state payloads, write failure, corrupt-read recovery and cancellation during commit.

The JVM preparation probe measured 1,000 rows at approximately 458 ms before linear duplicate checks and 10 ms afterward on this development host. These are diagnostic CPU measurements, not phone frame-rate guarantees. Device checks use an explicitly selected emulator; production throughput still depends on future backend and provider integrations.


### Signed-in wallet lookup

For reproducible verification with the current toolchain, use `./gradlew verifyAllFlavors --max-workers=1`; this avoids the observed Kotlin lint analyzer crash during concurrent flavor analysis. Device checks passed on the API 36 emulator. The installed Espresso version failed before app interaction on the API 37 image, so use API 36 for these checks.

Non-demo authentication now fetches `GET /v1/wallets/me?currency=NGN` with the newly issued bearer after protected session persistence, for password login, code login and successful signup verification. Stored-session restoration also reloads the wallet. The account screen displays its server wallet ID, status and exact available/reserved/total balances. HTTP transport is shared with authentication; it remains bounded, cancellable and configured to disable HTTP caching, redirects and automatic retries. Successful display responses are persisted separately in Room.

A missing wallet (404), network outage or malformed response produces an explicit wallet state while keeping the verified account signed in. **Retry wallet** rechecks the stored session and repeats the GET; it never replays signup, login or code redemption and never calls create-wallet. A wallet 401 removes only the captured invalid session. API-scope checks, credential-store comparisons, cancellation and ViewModel visit ownership prevent old results crossing a newer session or navigation. Wallet values and bearer credentials are excluded from saved UI state.

The real wallet is separate from the payment preview, which still uses its own demo ledger. The demo flavor remains fully simulated and receives no wallet HTTP repository. The API's Redis caching policy stays server-side; the mobile wallet is display data and never authorizes spending.

`HttpWalletRepositoryTest` and `AccountWalletTest` cover the GET/header contract, exact large amounts, failed lookup, no create fallback, login/verification ordering, protected persistence failure, API/session isolation, retries and late cancellation. The opt-in `ServerAuthFlowUiTest` now asserts the real NGN wallet after every supported login/signup flow and Activity recreation.


### Home wallet source

The non-demo Home dashboard now renders the authenticated account's NGN wallet available balance. It receives an immutable projection from `AuthViewModel`; the demo payment ledger never supplies or mutates that amount. Successful authentication and stored-session restoration open Home directly without an account confirmation step. Account details remain available through Profile. Home uses the demo card's shared layout, naira amount styling and Add money / Send money buttons while displaying only the API wallet balance. These buttons open the existing labelled funding/transfer previews; their simulated funds never update the real balance. Refresh remains available in account details, with an inline retry for failed Home wallet reads. Balance hiding, the offline greyscale palette and verified-wallet restoration are preserved. Loading, sign-in, missing-wallet and network-failure states contain no sample or invented zero balance.

Home, Payments, Activity and Plans share their layout across wallet sources. Home keeps the demo section spacing; compact Preview labels identify the simulated plans and recent activity beside their headings. At large font sizes or compact widths, navigation uses two columns so every tab label stays readable; wallet actions, activity details and plan summaries reflow without splitting their labels or overlapping amounts. `DashboardDesignUiTest` compares Home section placement and the other tabs across demo/server wallet fixtures and exercises 200 percent text and offline rendering without using a customer session.

The demo flavor keeps its original sample balance, funding/transfer controls and saved-state simulation. Other payment, plan and activity tools are still labelled previews while their integrations are pending. UI suites that assert simulated wallet amounts run only in the demo flavor; real Home coverage uses account-owned wallet fixtures and the opt-in dev API flow. A real wallet balance is display data, and all spending remains server-authoritative. `HomeWalletStateTest`, `AccountWalletTest`, `HomeWalletUiTest` and the opt-in real auth flow cover selection, isolation, refresh, logout/account changes, restoration, failure recovery, privacy and accessibility.


### Single airtime and data purchases

In dev, sign in, open Home and choose **Airtime** (also available in Payments). The app reads the caller's NGN wallet and the API's airtime catalogue. Choose the recipient's network explicitly, enter the phone number and amount, then **Review payment**. The server quote supplies the recipient, principal, fee, total and expiry. **Pay** submits that exact quote with one saved request key. An unavailable catalogue/adapter, missing wallet or expired quote stays explicit; sample prices and balances never replace API failures. The API deployment must publish airtime products and enable a fulfilment adapter for purchases to be available.

All calls send the captured signed-in bearer. The flow uses `GET /wallets/me?currency=NGN`, `GET /service-catalog/products?serviceType=airtime&currency=NGN&limit=25`, `POST /payments/quotes`, and the explicit `POST /payments` with only `quoteId` and `requestKey` (paths relative to `/v1`). No mobile pricing or displayed balance authorizes spending. Server responses distinguish awaiting funds, reserved, pending, succeeded and failed. Wallet display refreshes after submission/recovery/status checks.

A lost response stays **Payment status unconfirmed**. **Check status** reads `GET /payments/by-request-key`; **Recover this payment** explicitly calls the existing payment's reconcile endpoint. Retry/resume retains the original quote and key. No timer, reconnect, recreation or process restoration submits a payment. Reopen Airtime or Data → **Saved payments** to retrieve receipts or check unfinished requests, including during wallet/catalogue outages. Creating another purchase is an explicit action with an unfinished-request warning.

Before submission, a small atomic journal under `noBackupFilesDir` stores only account/API scope, original quote/request IDs, creation time and acknowledgement. It stores no token, phone, amount or quote body. Corruption/storage failure blocks new submissions; unfinished entries are never pruned. Journal commits use checked file fsync, same-directory atomic replacement and directory fsync. Every retry reconfirms durability, including when a prior rename succeeded before directory sync failed. The app supplies its existing private no-backup directory; unsupported or failed commits never fall back to an unchecked write. The journal retains up to 200 references and may prune acknowledged receipts at capacity. Clearing app data/uninstalling removes these device references; there is no account-wide payment-history endpoint integrated yet. Transient entry/review details remain in memory. This journal is not an outbox and never schedules purchases.

The demo flavor retains its original simulated airtime, funding, offline/SMS, scheduled and bulk flows. Non-demo immediate airtime requires internet and never uses the SMS simulation. Real activity is visible in the Airtime/Data saved-payment flow; the general Activity screen still shows its labelled preview.

Run `./gradlew verifyAllFlavors --max-workers=1` for all flavors. `WalletPurchasesTest`, `PreparePurchaseTest`, `HttpPurchaseRepositoryTest` and `PurchaseViewModelTest` cover exact input, bearer/wallet ownership, HTTP bodies, duplicate taps, expiry, storage and uncertain outcomes, cancellation, stale results and explicit recovery. On an explicitly selected API 36 emulator, run dev `PurchaseApiUiTest,PurchaseRequestStorageTest,HiltGraphTest`, and demo `AxiaUiTest,HiltGraphTest` through AndroidJUnitRunner. The purchase API UI tests use an isolated MockWebServer, real HTTP adapters and isolated atomic files, never a live funded wallet.


### API data plans and recovery compatibility

In dev, **Home → Data** and **Payments → Data** use the same protected NGN wallet and purchase workflow as airtime. The catalogue request uses `serviceType=data`; network and product choices come exclusively from the API. Choose the recipient's network explicitly, including for ported numbers, then a published plan. Data plans have a fixed server price, required allowance and validity; incomplete or malformed API terms are rejected. They have no editable amount and their quote POST contains only `walletId`, `productId` and `recipientPhoneNumber`. The quote screen shows the exact principal, fee, total, recipient, network, allowance and plan validity. Plan validity is separate from the short quote expiry. Changing networks clears the previous plan.

The app honors `isSubmissionAvailable` and never inserts sample plans, prices or balances after a catalogue/wallet/API failure. Data uses the same explicit confirmation, original request-key retry, read-only status lookup, explicit reconciliation, session/account ownership guards and wallet-refresh effects as airtime. Pending or unconfirmed progress never becomes a successful data receipt. Scheduled/bulk data, the general Activity view and SMS remain labelled previews; demo keeps its sample plans and simulated wallet.

The shared implementation lives under `domain/purchases`, `application/purchases`, `data/purchases`, `presentation/purchases` and `ui/purchases`. The optional Hilt feature remains absent in demo. A shared journal keeps the legacy `airtime-requests-v1.json` filename to preserve installed users' recovery records. Version-one rows read as airtime; the next atomic save/acknowledgement writes version two with a service discriminator, retaining every original key/quote/owner/API scope. Airtime and data history are filtered by service, and mismatched service responses cannot become receipts or trigger reconciliation. There is no automatic purchase during migration, restoration or reconnect.

`DataPurchasesTest`, the expanded `HttpPurchaseRepositoryTest`, `PurchaseApiUiTest`, `PurchaseRequestStorageTest` and `HiltGraphTest` cover plan metadata, exact fixed-price payloads, network/service/account isolation, stale responses, duplicate confirmation, lost-response recovery, journal migration, offline and large-font rendering. Dev device tests use an isolated HTTP fixture and real adapters/storage; they never purchase airtime or data with real funds. `AxiaUiTest` also verifies demo data plan selection, local receipt, simulated debit and recreation. Use `./gradlew verifyAllFlavors --max-workers=1` and explicitly select the API 36 test emulator for device checks.


### Purchase review regression fixes

Saved-request progress can retain display terms only from that same request; missing quotes never borrow another payment or unsubmitted review. Expiry tracking continues during confirmation IO and after a pre-submit failure while the review remains visible. No timer submits a payment.

`PurchaseJournalCommitTest` injects file sync, rename and directory sync failures through real journal/application operations, proving that no purchase POST occurs before a successful durable retry and that earlier references survive. It also covers acknowledgement retries and checked legacy-backup recovery. `PurchaseRecoveryRegressionTest` covers quote isolation, same-request display recovery and expiry through failed/suspended confirmation. Android storage/API UI suites exercise the actual checked commit adapter, legacy backup/staging recovery and a post-rename sync failure followed by explicit same-key retry, using isolated HTTP fixtures only.


### Electricity payments

In dev, **Home → Electricity** and **Payments → Electricity** use the signed-in account's NGN wallet. Select an API catalogue provider, prepaid/postpaid mode and product, enter the exact meter/account number, then **Verify meter**. Verification calls `POST /v1/payments/account-validations` with only `productId` and `accountNumber`; leading zeros are preserved. The response supplies the customer, service address and expiry. Changing provider, product, mode or meter clears verification. Starting re-verification also clears the previous capability, so an invalid or unconfirmed response cannot leave stale verified terms usable. A separate delivery phone is canonicalized for the quote. No demo provider ID or customer name establishes verification.

**Review payment** sends the owned wallet ID, product ID, account validation ID, exact string minor units and delivery contact to `POST /v1/payments/quotes`. It never sends the meter as `recipientPhoneNumber`. The review shows the verified customer/mode, principal, fee, total and expiry. **Pay** durably records one original quote/request reference before the shared purchase POST. The API must publish electricity products and enable its adapter; unavailable verification, pricing or submission remains explicit without a simulated fallback.

Financial success and receipt availability remain distinct. Prepaid receipts display the API's ordered key-change/recharge tokens and exact optional kWh text. Postpaid shows its receipt reference without inventing a token. A successful payment awaiting fulfilment retains an unfinished reference; **Recover token / receipt** reconciles the existing payment, never creates another purchase. Reopening, reconnecting and process restoration only discover saved references. No meter/customer/contact, quote body or electricity token enters the journal or saved UI state. The existing journal path and version-one airtime/version-two service records remain compatible; version two now includes `electricity` alongside airtime/data.

The shared feature is now named `purchases` (`WalletPurchases`, `PurchaseViewModel`, `HttpPurchaseRepository`) because its consent/recovery responsibility spans phone and electricity services. Demo has no live workflow binding and retains its provider picker, local ledger, receipts and simulations. Scheduled/bulk electricity remains a labelled preview. `ElectricityPurchasesTest` and HTTP/device suites cover verification correlation/expiry/owner isolation, invalidation on edits, duplicate/late actions, uncertain/storage outcomes, delayed receipt recovery, both meter modes, journal migration/privacy, offline and large-font entry. Run `./gradlew verifyAllFlavors --max-workers=1`, then the dev `PurchaseApiUiTest,PurchaseRequestStorageTest,HiltGraphTest` and demo `AxiaUiTest,ProviderLogoUiTest,HiltGraphTest` suites on the explicitly selected API 36 emulator. These tests use isolated fixtures and no real funds.

### HTTP request correlation

Every non-demo API call sends one new `X-Request-Id` in the format `Manufacturer-model-timestampMs-UUID`, including repeated reads and explicit retries. Hilt supplies Android's actual `Build.MANUFACTURER` and `Build.MODEL`; each public label is normalized to at most 24 ASCII characters, with `Android`/`Unknown` fallbacks. The timestamp is Unix epoch milliseconds. No device serial, contact or credential is included. The API validates and echoes the reference on success and errors; missing or unsafe values receive a server UUID. IDs correlate diagnostics and never replace payment request keys or authorize an operation.

Dev HTTP log prefixes use that same ID, and safe echoed references remain available through error mapping. Credential/body redaction and the separate demo bindings remain in place. `ApiRequestIdGeneratorTest`, `ApiHttpClientRequestIdTest` and logging tests cover bounds, unknown/unsafe build labels, concurrent uniqueness, repeated reads, response references and preservation of the original payment key.

## API remote configuration

Dev consumes eight independent authentication switches: email, phone, password and passwordless OTP for signup and login from `GET /v1/config`. Android needs no Firebase SDK or credentials. The app refreshes in the background on account-form entry and foregrounding. Before its first successful config read, email with password or one-time code remains available for signup and login. Config loading/failure never replaces the form with an options error. Once settings have been fetched, the UI keeps those switches during refresh failures, including explicitly disabled methods. A disabled-method rejection refreshes the options without replaying authentication. Demo retains its independent all-enabled simulation.

For each operation, `authentication.signup` and `authentication.login` expose `emailEnabled`, `phoneEnabled`, `passwordEnabled` and `otpEnabled`. An enabled contact and an enabled credential are both required. A selector appears only when it offers two choices; no contact or no credential choice shows explicit unavailability. Password signup always needs its contact-verification code, even if passwordless OTP signup is disabled. Policy refresh preserves entered passwords and issued proof; an affected draft requires an explicit method change, and re-enabling never submits automatically. Older contact-only snapshots preserve their contact settings and both original credential choices; partially supplied new credential fields are rejected.

The settings and version are saved together per API environment. On cold start, `LoadRemoteConfiguration` publishes a validated saved snapshot before making its conditional network request. Saved switches remain available across offline restarts, including explicit disabled values. A matching `304` keeps the snapshot; a valid `200` atomically replaces it. Refresh failures preserve the last saved policy and remain explicit in state. Missing, corrupt or foreign cache records use the email-only client default until a valid online response is persisted. Client defaults never receive a remote version or enter that cache. Saved settings control display choices; every authentication operation still requires the API and its current policy. See [ARCHITECTURE.md](ARCHITECTURE.md#api-remote-configuration) for ownership and failure rules.

Run `./gradlew verifyAllFlavors --max-workers=1`. `RemoteConfigRepositoryTest` and `AuthenticationConfigTest` cover conditional requests, atomic writes, cache recovery/isolation, disabled contacts, failures, cancellation and stale completion. `RemoteConfigUiTest` checks both palettes, large text and accessible retry.

The local API smoke check is read-only and uses a unique temporary config cache. With the dev APKs installed and the local API running, select the connected phone explicitly; use an emulator only when no phone is connected:

```bash
adb -s <device-serial> shell am instrument -w -r \
  -e runLocalRemoteConfig true \
  -e class com.kesofty.axiapay.RemoteConfigDeviceTest,com.kesofty.axiapay.RemoteConfigUiTest \
  com.kesofty.axiapay.dev.test/androidx.test.runner.AndroidJUnitRunner
```

`RemoteConfigDeviceTest` checks real Room persistence recovery and the live API's `200` then empty `304` flow without modifying Firebase, authenticating a customer, or creating an account.


Payment features now arrive in the same offline-first `GET /api/v1/config` snapshot: airtime, data, electricity, TV, education, insurance, bank transfers, merchant payments and airtime conversion. Example: `features.airtime.enabled`. The API reads these switches from explicit `feature_*_enabled` Firebase server parameters. Absent server feature parameters default to disabled while existing authentication configuration remains usable. Publish explicit values to enable the intended services; no database migration or mobile Firebase SDK is needed.

The home/payment grids show disabled services as temporarily unavailable. Refreshing policy also gates new review, meter verification and confirmation within an open live purchase; it preserves drafts, original request keys, history and status/reconciliation. Re-enabling does not replay consent. These cached flags only control display/preparation; the API authorizes new spending independently. Missing config, or an older authentication-only cache, leaves payment features unavailable. Demo uses its own all-enabled flavor fixture.

### Room dashboard snapshots

Non-demo flavors use Room 2.8.5 with the existing KSP toolchain. `AxiaDatabase` persists `accounts`, `wallet_summaries` and `app_configuration` in `noBackupFilesDir/dashboard/snapshots.db`. The complete version-1 schema is exported under `app/schemas`; future changes require reviewed Room migrations rather than destructive fallback. Demo retains its existing composition and snapshots.

Every row is scoped to the normalized API identity; account/wallet rows also carry account ownership. This prevents a development URL change or a later login from reading another API/account's records. Wallet amounts are stored as exact decimal text, including values beyond signed Long. Account updates use upsert so they cannot cascade-delete a saved wallet. Successful logout/session invalidation removes the captured account and dependent wallet rows, retaining public configuration.

The encrypted session additionally binds an account ID. Legacy session records acquire this binding after their next successful `/auth/me` response. Tokens remain exclusively in Keystore-backed session storage. Locally unexpired credentials permit cached display only; the API still authorizes every protected request. Expired, mismatched or revoked sessions cannot select cached dashboard data.

`ServerAccountAccess.cached()` reads account and wallet together before remote restoration. Refresh persists successful responses before publishing them. The Home balance remains visible during an in-flight refresh or network failure. A confirmed missing wallet removes its old snapshot; a wallet/session 401 clears captured access. Refresh never submits purchases, replays credentials or creates wallets.

Remote configuration reads Room before HTTP revalidation. A 200 atomically replaces policy and its quoted ETag; a matching 304 updates only the checked timestamp. Failed requests and commits preserve the last record. Existing API-scoped JSON configuration is imported once on a Room miss, with unknown receipt time represented by zero rather than claiming a new fetch. Client defaults are never persisted as a remote response.

`LocalDashboardTest` exercises startup-before-refresh, offline retention, expiry, ownership, logout and missing-wallet invalidation. `DashboardDatabaseTest` verifies actual SQLite reopen, exact amounts, foreign-key retention/deletion and API/account isolation. `DashboardRoomUiTest` verifies a reopened database renders the real Home wallet while remote access is suspended, retains it on failure, then shows a later committed server response. Device checks use an explicitly selected physical phone.

### Access and refresh sessions

Non-demo authentication receives a 15-minute access token and a rotating refresh token bound to a fixed 30-day session family. The complete pair, family identity and deadlines are stored in one AES-GCM encrypted Keystore-backed record. Existing token-only records remain readable until their original expiry; they cannot refresh implicitly. The API independently checks expiry, revocation, account/device state and spending permission.

One application-scoped `AuthenticationSessionRepositoryImpl` coordinates renewal across protected HTTP adapters. It refreshes near access expiry or once after an explicit authentication rejection, then atomically compares and replaces the captured record before a resource request uses the new pair. Concurrent requests share the replacement; a later login, logout or API change prevents an older renewal from overwriting credentials. Rotation keeps the same session identity so wallet/purchase responses remain scoped to their original account.

Only `POST /auth/refresh` receives refresh proof; resource calls send the access token. Authentication retry retains the exact payment payload and logical request key, with a new correlation ID. Timeouts and network failures never replay payment submission. Failed refresh remains explicit and temporary failures retain saved credentials. An acknowledged pair whose local commit failed can retry that local commit in memory; a lost server response or process loss before persistence may require sign-in because replay of consumed refresh proof revokes its family. No background timer renews indefinitely, and refresh never counts as fresh primary authentication. Demo remains independent.

`SessionTokenRenewalTest` covers concurrent requests, one retry, login/logout races, API isolation, cancellation, malformed pairs and storage recovery. `AuthenticationSessionStorageTest` checks real Keystore encryption and compare-and-replace. The opt-in `SessionRenewalDeviceTest` exercises the real API against disposable PostgreSQL through an explicitly selected physical phone, without using the app's real credentials.

## Compose Navigation

Account entry/recovery and dashboard destinations now use Navigation Compose. The dashboard host includes all four tabs and bulk service, recipient, import, review, progress and retry screens. Existing bottom sheets, offline palettes, drafts and payment request identities keep their established behavior. Back from a tab returns Home; Back through a bulk edit retains its list; accepted progress never opens an old payment review.

`MainActivity` hosts `AxiaAppRoute`, which obtains the three Activity-owned ViewModels through `hiltViewModel()` and collects immutable state. Routes contain no account credentials, payment inputs or amounts. After Android restoration, navigation follows the restored workflow/checkpoint instead of reopening an abandoned authentication form or replaying an operation. Demo fixtures and real Room/authentication recovery remain separate.

The catalog pins Navigation Compose 2.9.8 and Hilt lifecycle Compose 1.3.0, retaining the existing Lifecycle 2.9.4 and Compose BOM for the SDK 37 toolchain. Run `./gradlew verifyAllFlavors --max-workers=1` for shared verification. On the explicitly selected physical phone, the demo `ComposeNavigationTest,NavigationFlowUiTest,HiltGraphTest,AxiaUiTest` suites cover controller restoration, draft retention, owner isolation and simulated payment flows; the dev `ComposeNavigationTest,HomeWalletUiTest,DashboardRoomUiTest,PurchaseApiUiTest` suites cover real-wallet presentation and isolated API adapters. These tests do not authorize a real payment.
