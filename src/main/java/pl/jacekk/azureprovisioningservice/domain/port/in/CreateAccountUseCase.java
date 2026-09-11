package pl.jacekk.azureprovisioningservice.domain.port.in;

public interface CreateAccountUseCase {

    /**
     * Validates, then accepts the request as a fresh job or a retry of a failed one, or reports
     * that the subscription name is already held by a job that has not failed.
     *
     * @throws pl.jacekk.azureprovisioningservice.domain.model.InvalidLabelsException before
     *         anything is persisted, when the labels are incomplete
     */
    AccountAcceptance createAccount(CreateAccountCommand command);
}
