package pl.jacekk.azureprovisioningservice.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The claim a single replica holds over a provisioning job.
 *
 * <p>{@code jobId} is the correlation id that ties one accepted POST to every log line its async
 * workflow emits; a retry of the same account gets a fresh one. {@code ownerId} identifies the
 * replica, so an expired lease can say who abandoned the job.
 */
public record JobLease(String jobId, String ownerId, Instant expiresAt) {

    public JobLease {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
