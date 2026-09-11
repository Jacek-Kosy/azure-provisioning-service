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
│                        AsyncProvisioningWorkflow   — the run itself
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
  `recordSubscriptionCreated`, `failStep` and `failCleanup`, each validated against
  `ProvisioningStatus`'s transition table. An out-of-order workflow is rejected by the domain
  rather than written to Mongo.
- **The use case returns an `AcceptanceOutcome`, not a status code.** The controller maps
  `CREATED` and `RETRY_ACCEPTED` to 202, `ALREADY_IN_PROGRESS` to 409. HTTP stops at the adapter.

## Retry and cleanup semantics

A failed job is retried by **posting the same subscription name again**. There is no separate retry
endpoint, and no partial resume.

```
POST (name held by a FAILED job)
  │
  ▼
0. CLEANING_UP ── azureSubscriptionId set? ── yes ──► delete it, then clear it
  │                                          no  ──► nothing to do
  │
  ├── cleanup failed ──► FAILED, errorDetail "CLEANUP_FAILED: …", run stops here.
  │                      Step 1 is not attempted until a later retry cleans up successfully.
  ▼
1. CREATING_SUBSCRIPTION      create, record the new id
2. ASSIGNING_MANAGEMENT_GROUP move it under the target group
3. APPLYING_LABELS            apply the four labels as tags
  │
  ├── any step failed ──► FAILED, errorDetail "Step <STATUS> failed: <cause>".
  │                       The partially created subscription is LEFT IN PLACE on purpose —
  │                       the next retry's step 0 deletes it.
  ▼
   COMPLETED
```

Two rules that are easy to get wrong and are covered by tests:

- **Always a full rerun.** After a successful cleanup the run restarts at step 1. No step is
  skipped because it happened to succeed last time.
- **Cleanup is deferred, never immediate.** A failing step does not delete what it created. That
  keeps the failure inspectable, and means the delete happens exactly once, at the start of the
  next attempt.

`CLEANUP_FAILED:` and `Step …:` prefixes are deliberately distinct, so an operator can tell "Azure
would not let go of the old subscription" from "the new one could not be built".

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
| Create / delete a subscription | `StubAzureSubscriptionAdapter` | Use `SubscriptionManager`. Creation means creating an **alias** against a billing scope (EA enrollment account, MCA billing profile, or MPA agreement); deletion means cancelling. The billing-scope wiring is why this ships as a stub. |
| Apply tags | `StubAzureSubscriptionAdapter.applyTags` | Same manager, on the subscription resource. |
| Move into a management group | `StubManagementGroupAdapter` | `ManagementGroupsManager.managementGroupSubscriptions().create(groupId, subscriptionId)`. |
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
| `e2e` | HTTP → Mongo → async workflow, including the retry and failed-cleanup branches |

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
