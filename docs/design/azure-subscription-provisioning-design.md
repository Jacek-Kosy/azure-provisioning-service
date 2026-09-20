# Azure Subscription Provisioning Service — Design

Status: accepted · Date: 2026-09-11 · Branch: `feature/azure-subscription-provisioning-service`

## Purpose

Accept a request to provision an Azure subscription, provision it asynchronously (create →
assign to a management group → apply labels), and expose the job's progress. The Azure calls
themselves are stubs: real subscription creation and deletion need enterprise-agreement /
billing-scope wiring that is out of scope here. Everything around them — validation,
idempotency, the state machine, retry-with-cleanup, persistence, and the HTTP contract — is
real and covered by tests.

## Architecture: ports & adapters

The dependency rule is one-way: `adapter` → `application` → `domain`. The `domain` package
imports nothing from Spring, Mongo, or Azure; it is plain Java and is unit-tested without a
container.

```
pl.jacekk.azureprovisioningservice
├── domain
│   ├── model            Account aggregate, Labels VO, ProvisioningStatus, domain exceptions
│   └── port
│       ├── in           CreateAccountUseCase, GetAccountStatusUseCase (driving)
│       └── out          AccountRepositoryPort, AzureSubscriptionPort,
│                        ManagementGroupPort, LabelValidationPort (driven)
├── application
│   └── service          AccountProvisioningService, ProvisioningWorkflow, StaleJobSweeper
├── adapter
│   ├── in.web           AccountController, DTOs, RestExceptionHandler
│   ├── out.persistence  AccountDocument, Mongo repository adapter, mapper
│   ├── out.azure        Stub Azure adapters (the TODO seam)
│   └── out.validation   Rule-based LabelValidationPort implementation
└── config               async executor, Azure credentials, security, OpenAPI, Mongo indexes
```

### Why the status field is the aggregate's only lock

`ProvisioningStatus` is not a label attached to the side of the process; it *is* the process
state, and it is what makes concurrency safe. See "Concurrency" below.

## Domain model

`Account` is the aggregate. Its fields are `id`, `subscriptionName`, `targetManagementGroup`,
`labels`, `status`, `createdAt`, `updatedAt`, `azureSubscriptionId` (null until step 1
succeeds), `errorDetail` (null unless failed), plus three fields that exist for coordination:
`jobId`, `ownerId`, `leaseExpiresAt`.

The aggregate exposes transitions, not setters — `startProvisioning()`, `recordSubscription(id)`,
`startAssigningManagementGroup()`, `startApplyingLabels()`, `complete()`, `failStep(cause)`,
`startRetry(lease)`, `abandon()`. Each one validates the move against the state machine and throws
`IllegalStatusTransitionException` otherwise, so an out-of-order workflow is a test failure rather
than a corrupt document.

```
PENDING ──► CREATING_SUBSCRIPTION ──► ASSIGNING_MANAGEMENT_GROUP ──► APPLYING_LABELS ──► COMPLETED
   ▲                  │                          │                         │
   │                  └──────────────────────────┴─────────────────────────┴──► FAILED
   └───────────────────────────── (retry claim) ─────────────────────────────────┘
```

`Labels` is a value object over the tag map. It owns the four required keys as constants
(`cost-center-id`, `cost-center-id-provider`, `project-internal-id`, `project-internal-id-provider`)
and is immutable.

`ProvisioningStatus` answers `isTerminal()` and `isRetryable()` so neither the service nor the
controller re-derives those rules.

## Request handling

### POST /accounts

1. `LabelValidationPort.validate` runs first. Violations → `InvalidLabelsException` → **400**,
   before anything is persisted.
2. The subscription name is the idempotency key, backed by a unique index on
   `accounts.subscriptionName`.
   - No document → insert `PENDING` and dispatch the fresh workflow → **202** + `Location`.
   - Document exists and is not `FAILED` → **409** + `Location` of the existing job. No new work.
   - Document exists and is `FAILED` → claimed as a retry (see below) → **202** + `Location`.

### GET /accounts/{id}

Returns status, `azureSubscriptionId` when present, labels, `errorDetail` when failed, plus
timestamps and `jobId`. Unknown id → **404**.

Both error responses use RFC 7807 `ProblemDetail`.

## Concurrency: correct under multiple replicas

A read-then-write (`find` → check status → `save`) is unsafe across replicas: two instances can
both observe a `FAILED` account and both start a full provisioning run, producing two real Azure
subscriptions for one request, the second silently overwriting the first's id. The design avoids
read-then-write entirely.

**Claim by compare-and-set.** `AccountRepositoryPort.claimForRetry(...)` is a single Mongo
`findAndModify` whose *filter includes the expected status*:

```
{ subscriptionName: X, status: "FAILED" } → { $set: { status: "CLEANING_UP", jobId: …,
                                                      ownerId: …, leaseExpiresAt: …,
                                                      errorDetail: null } }
```

Exactly one replica gets a document back and starts the workflow; the other gets an empty
`Optional`, re-reads, and answers 409. The fresh-insert race is resolved by the unique index —
a losing insert throws `DuplicateKeyException`, which the service translates into the same 409.

**Optimistic locking.** `AccountDocument` carries `@Version`, so every step write is guarded.
A second workflow that somehow slipped through dies on `OptimisticLockingFailureException`
instead of interleaving writes with the legitimate one.

**Lease + sweeper.** A claim stamps `ownerId` and `leaseExpiresAt`; the workflow renews the
lease as it crosses each step boundary. `StaleJobSweeper` runs on a schedule and marks jobs
whose lease expired as `FAILED`, with an `errorDetail` naming the replica that abandoned them.
It never resumes anything — it only makes the account retryable, and the ordinary
retry-with-cleanup path does the rest. This is what keeps a killed replica from parking a
subscription name in a non-terminal status forever.

`Clock` is injected so lease and sweeper behaviour is deterministic under test.

## Async workflow

Orchestrated by `ProvisioningWorkflow` in the application layer — a separate bean from
`AccountProvisioningService` because `@Async` self-invocation is silently synchronous. The
controller never sees it.

Every step states a desired end state rather than an action, so running it twice is harmless:

1. `CREATING_SUBSCRIPTION` → `ensureSubscription(alias, name)` — creates, or adopts what an earlier
   run built. The id is recorded.
2. `ASSIGNING_MANAGEMENT_GROUP` → `ensurePlacedUnder(id, group)` — moves only if it is elsewhere.
3. `APPLYING_LABELS` → `ensureTags(id, labels)` — writes only if the tags differ.
4. `COMPLETED`.

A step failure records `Step <STATUS> failed: <cause>` and leaves everything built so far in place.

**There is no retry path**, because a retry is this path again. The claim sends a `FAILED` account
back to `PENDING`, and the workflow cannot tell it apart from a fresh request. This replaces the
earlier cleanup-then-full-rerun design, in which a retry deleted the previous attempt's subscription
and built a new one.

The reason is that a recorded step position is a hint, not a fact: the status write and the Azure
call cannot be atomic, so "failed at step 2" never means "step 2 did not happen". Skipping work has
to be decided by observing the provider, which is authoritative, rather than by reading our own
bookkeeping, which is the first thing lost when a replica dies. Making each step reconcile also
removes the need to destroy anything, which matters for the later multi-cloud question — AWS
accounts cannot be deleted at all.

### The alias, and why it never moves

Each account owns one alias, `acct-<account id>`, derived rather than stored and stable for the life
of the account. Step 1 uses it to find and adopt an existing subscription.

It also covers the case where a run created a subscription and died before the id reached Mongo: the
alias still names it, so the next run adopts it rather than orphaning it and building a second.
`recordSubscription` refuses to replace an id it already holds with a different one — that would
mean two subscriptions for one account, and it fails loudly instead.

This encodes two assumptions about Azure that the stubs cannot verify: that an alias can be looked
up to find its subscription, and that re-creating an existing alias is idempotent. Both must be
validated when the real adapter is written.

## Extension seams

| Seam | How to extend |
|---|---|
| Provider-specific label formats | Add a `LabelRule` bean in `adapter.out.validation`. The validator composes all rules in order; `CreateAccountUseCase` is untouched. |
| Real subscription create/delete | Replace the body of `StubAzureSubscriptionAdapter`. The injected `TokenCredential` and `AzureProperties` are already wired. |
| Real management-group assignment | Replace the body of `StubManagementGroupAdapter`. |
| Work distribution across replicas | The sweeper already recovers abandoned jobs. A polling claim loop would slot in beside it using the same CAS primitive. |

## Non-functional

**Correlation.** Every accepted request mints a `jobId` (a retry gets a fresh one). It is stored
on the aggregate, returned by `GET`, and placed in the MDC for both the request thread and the
async worker, which puts it in the MDC for the duration of the run — so a single `jobId`
ties the POST log line to every workflow line and to the sweeper line that later abandons it.

**Credentials.** `DefaultAzureCredential` resolves from environment variables or managed
identity. Nothing secret is bound into configuration properties, and no credential value is ever
logged. `AzureProperties` carries only non-secret routing data (tenant id, default management
group, subscription-creation settings).

**Security.** `/accounts/**` and the springdoc UI are permitted; the session is stateless and
CSRF is disabled for the JSON API. The security starter stays wired so a real scheme (JWT /
OAuth2 resource server) is a change to `SecurityConfig` alone.

## Testing

| Layer | Test | Covers |
|---|---|---|
| domain | `AccountTest`, `LabelsTest` | every legal transition, rejection of illegal ones, the stable alias, refusing to swap a recorded subscription |
| validation | `RuleBasedLabelValidatorTest` | all four keys required, blank rejected, unknown extra keys, rule composition |
| application | `AccountProvisioningServiceTest` | fresh accept, 409 on non-FAILED duplicate, retry accept, duplicate-key race, validation before persistence |
| application | `ProvisioningWorkflowTest` | happy path, failure at each step, a rerun adopting the previous run's subscription — recorded or not — and a rerun over already-correct state |
| application | `StaleJobSweeperTest` | expired lease abandoned, live lease untouched, terminal jobs ignored |
| persistence | `MongoAccountRepositoryAdapterIT` (Testcontainers) | unique index on name, CAS claim semantics, exactly one winner under concurrent claims |
| web | `AccountControllerTest` (MockMvc) | 202 + Location fresh, 400 invalid labels, 409 duplicate, 202 retry, 404 unknown, response body |

The Testcontainers tests skip themselves when Docker is unavailable rather than failing the build.
