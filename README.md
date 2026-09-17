# Azure Subscription Provisioning Service

Requests an Azure subscription, moves it into a management group, and tags it — asynchronously,
idempotently, and safely across replicas.

The Azure calls themselves are stubs (see [Plugging in real Azure logic](#plugging-in-real-azure-logic)).
Everything around them is real and tested: label validation, idempotency on the subscription name,
the provisioning state machine, retry-with-cleanup, Mongo persistence, and the HTTP contract.

Full design notes: [`docs/design/azure-subscription-provisioning-design.md`](docs/design/azure-subscription-provisioning-design.md).

## Running it

Needs a MongoDB. Index creation is eager and fatal, so the database must be reachable at startup —
a service that came up without the unique index would silently accept duplicate provisioning jobs.

```bash
docker run -d -p 27017:27017 --name provisioning-mongo mongo:7.0
./gradlew bootRun
```

API docs at `http://localhost:8080/swagger-ui.html`.

| Variable | Purpose |
|---|---|
| `MONGODB_URI` | defaults to `mongodb://localhost:27017/azure-provisioning` |
| `AZURE_TENANT_ID`, `AZURE_BILLING_SCOPE`, `AZURE_ROOT_MANAGEMENT_GROUP` | non-secret routing data |
| `AZURE_CLIENT_ID` / `AZURE_CLIENT_SECRET`, or a managed identity | credentials, read by `DefaultAzureCredential` |

Credentials are never read from `application.yaml`, never bound into a configuration property, and
never logged. In a cluster, use a managed or workload identity and set no secret at all.

## API

### `POST /accounts`

```json
{
  "subscriptionName": "team-alpha-prod",
  "targetManagementGroup": "mg-workloads",
  "labels": {
    "cost-center-id": "CC-1001",
    "cost-center-id-provider": "sap",
    "project-internal-id": "PRJ-42",
    "project-internal-id-provider": "servicenow"
  }
}
```

All four label keys are required and must be non-blank. They are checked before anything is
persisted, so an invalid request leaves no trace.

**The subscription name is the idempotency key.**

| Situation | Response |
|---|---|
| Name is free | `202 Accepted` + `Location: /accounts/{id}` — a new job starts |
| Name held by a job that has not failed | `409 Conflict` + `Location` of that job — no new work |
| Name held by a **failed** job | `202 Accepted` + `Location` of that job — accepted as a retry |
| Labels missing or blank | `400 Bad Request`, RFC 7807 body listing every violation |

### `GET /accounts/{id}`

Returns the current status, `azureSubscriptionId` once it exists, the labels, `errorDetail` if the
job failed, and the `jobId` that ties the request to its log lines. Unknown id → `404`.

## The hexagon

The dependency rule runs one way: `adapter` → `application` → `domain`. The `domain` package
imports nothing from Spring, Mongo, or Azure, which is why the state machine is unit-tested with
no container in sight.

```
pl.jacekk.azureprovisioningservice
├── domain
│   ├── model            Account aggregate, Labels, ProvisioningStatus, JobLease
│   └── port
│       ├── in           CreateAccountUseCase, GetAccountStatusUseCase          (driving)
│       └── out          AccountRepositoryPort, AzureSubscriptionPort,
│                        ManagementGroupPort, LabelValidationPort               (driven)
├── application
│   └── service          AccountProvisioningService  — accept / conflict / retry decision
│                        AsyncProvisioningWorkflow   — the run itself (one path, not two)
│                        StaleJobSweeper             — recovers jobs from dead replicas
├── adapter
│   ├── in.web           AccountController, DTOs, RestExceptionHandler
│   ├── out.persistence  AccountDocument, mapper, Mongo repository adapter
│   ├── out.azure        the two stubs
│   └── out.validation   rule-based LabelValidationPort implementation
└── config               async executor, Azure credentials, security, OpenAPI, Mongo indexes
```

Two conventions worth knowing before you edit:

- **`Account` has no setters.** State changes go through methods like `startProvisioning`,
  `recordSubscription`, `startRetry` and `failStep`, each validated against `ProvisioningStatus`'s
  transition table. An out-of-order workflow is rejected by the domain rather than written to Mongo.
- **The use case returns an `AcceptanceOutcome`, not a status code.** The controller maps
  `CREATED` and `RETRY_ACCEPTED` to 202, `ALREADY_IN_PROGRESS` to 409. HTTP stops at the adapter.

## Retry semantics

A failed job is retried by **posting the same subscription name again**. There is no separate retry
endpoint — and no cleanup step, because nothing has to be undone first.

Every step states a desired end state rather than an action:

```
1. CREATING_SUBSCRIPTION       ensureSubscription(alias, name)   → CREATED | ADOPTED
2. ASSIGNING_MANAGEMENT_GROUP  ensurePlacedUnder(id, group)      → CREATED | UPDATED | ALREADY_SATISFIED
3. APPLYING_LABELS             ensureTags(id, labels)            → CREATED | UPDATED | ALREADY_SATISFIED
```

So a retry is simply the run again. Each step asks Azure whether it is already satisfied and skips
the work if so — which means the decision to skip comes from Azure, which knows, rather than from
our record of where the previous run got to, which may be stale or was never written at all. A
failing step records `Step <STATUS> failed: <cause>` and leaves everything it built in place, for
the next run to adopt.

The claim that accepts a retry sends the account back to `PENDING`, so a retried job and a fresh one
are indistinguishable from the workflow's point of view.

### The alias, and why it never moves

Each account owns one Azure subscription alias, `acct-<account id>`, derived rather than stored and
fixed for the life of the account. It is how step 1 finds an existing subscription and adopts it
instead of creating a second.

That also covers the nastiest failure: a run creates the subscription and dies — evicted, or its
lease expires mid-call — before the id reaches Mongo. Nothing in our records points at that
subscription, but the alias does, so the next run adopts it. The alias survives what the crash lost.

Adoption is also why `recordSubscription` refuses to overwrite an id it already holds with a
different one: that would mean two subscriptions exist for one account, and it should fail loudly
rather than silently forget the first.

Re-running a `COMPLETED` account is safe and corrects drift — if someone retags or moves the
subscription by hand, the steps put it back.

## Running on more than one replica

The subscription name is unique-indexed, so two simultaneous fresh requests cannot both insert;
the loser's `DuplicateKeyException` becomes a 409.

The retry path is the one that needs care, and it is handled by never reading-then-writing.
`claimForRetry` is a single `findAndModify` whose filter requires the account to still be `FAILED`:

```
{ subscriptionName: X, status: FAILED } → { $set: { status: CLEANING_UP, jobId, ownerId,
                                                    leaseExpiresAt }, $unset: { errorDetail } }
```

Exactly one replica gets a document back and starts the run; the others get nothing and answer 409.
`AccountDocument` also carries `@Version`, so every later step-write is rejected if another replica
got there first.

A claim stamps `ownerId` and `leaseExpiresAt`, and the workflow renews the lease as it crosses each
step. If a replica is killed mid-run, `StaleJobSweeper` marks the abandoned job `FAILED` — it never
tries to resume anything, because the ordinary retry path already knows how to clean up and start
over. Without it, a killed replica would park a subscription name in a non-terminal status and
every later request for that name would 409 forever.

Tune with `provisioning.lease-duration`, `provisioning.sweep-interval`, `provisioning.sweep-batch-size`,
and `provisioning.owner-id` (derived from `HOSTNAME` when unset).

## Correlation

Every accepted request mints a `jobId`; a retry gets a fresh one. It is stored on the aggregate,
returned by `GET`, and put in the MDC. `AsyncConfig`'s task decorator carries it across the hop
onto the worker thread, so one `jobId` ties the POST line, every workflow line, and any later
sweeper line together:

```
 INFO [jobId=6f2c…, accountId=a41b…] Accepted a retry for subscription 'team-alpha-prod'
 INFO [jobId=6f2c…, accountId=a41b…] Deleting subscription sub-1 left behind by the previous run
 INFO [jobId=6f2c…, accountId=a41b…] Created subscription sub-2
```

## Plugging in real Azure logic

| What | Where | Notes |
|---|---|---|
| Create or adopt a subscription | `StubAzureSubscriptionAdapter.ensureSubscription` | Use `SubscriptionManager`. A subscription is created by PUTting an **alias** (`Microsoft.Subscription/aliases/{aliasName}`) against a billing scope (EA enrollment account, MCA billing profile, or MPA agreement) — the billing-scope wiring is why this ships as a stub. GET the alias first and return `ADOPTED` if it already resolves. **Validate against the live API** that a GET returns the subscription and that PUTting an existing alias is idempotent: the adopt path depends on both. |
| Apply tags | `StubAzureSubscriptionAdapter.ensureTags` | Read the current tags, compare, and only write when they differ. |
| Move into a management group | `StubManagementGroupAdapter.ensurePlacedUnder` | Read the current parent; only call `managementGroupSubscriptions().create(groupId, subscriptionId)` when it differs. |
| Credentials | `AzureCredentialConfig` | Already builds a `DefaultAzureCredential`; inject the `TokenCredential` into the adapters. |
| A provider-specific label rule | new `LabelRule` bean in `adapter.out.validation` | E.g. "`cost-center-id` must match `CC-\d{4}` when the provider is `sap`". Publish the bean; `RuleBasedLabelValidator` picks it up and neither the use case nor the controller changes. |

Throw `AzureProvisioningException` from a port when Azure refuses — the workflow turns it into a
`FAILED` status naming the step.

## Tests

```bash
./gradlew test
```

| Scope | What it covers |
|---|---|
| `domain.model` | every legal transition, rejection of illegal ones and of step-skipping, error-detail shapes |
| `adapter.out.validation` | the four required keys, blank and null values, extra keys, rule composition |
| `application.service` | fresh accept, 409, retry accept, the duplicate-key race; the full run, failure at each step, cleanup-then-rerun, cleanup failure stopping the run; sweeper behaviour |
| `adapter.in.web` | 202 + `Location`, 400 on invalid labels, 409 on a duplicate, 202 on a retry, 404, response bodies |
| `adapter.out.persistence` | unique index, CAS claim semantics, one winner among concurrent claims, optimistic locking, stale-job query |
| `e2e` | HTTP → Mongo → async workflow, including a retry adopting the failed run's subscription — and one it never recorded |

The last two rows need a container runtime for Testcontainers. **They skip themselves when none is
available** (`@Testcontainers(disabledWithoutDocker = true)`) rather than failing the build — so a
green `./gradlew test` is not proof that Mongo was exercised. Check the skip count, or look for
`MongoAccountRepositoryAdapterIT` in `build/reports/tests/test/index.html`.

On Colima, Testcontainers does not pick up the Docker context on its own and will quietly skip
everything unless both of these are set — `DOCKER_HOST` alone is not enough, because Ryuk (the
container Testcontainers uses to clean up after itself) needs the socket at its conventional path:

```bash
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew test
```

With those set the full suite runs: 107 tests, nothing skipped.
