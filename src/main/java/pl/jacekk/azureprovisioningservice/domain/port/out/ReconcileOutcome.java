package pl.jacekk.azureprovisioningservice.domain.port.out;

/** What a step had to do to reach the desired state. Reported so a rerun's effect is visible. */
public enum ReconcileOutcome {

    CREATED,
    /** It already existed, built by an earlier run of this same account. */
    ADOPTED,
    UPDATED,
    ALREADY_SATISFIED
}
