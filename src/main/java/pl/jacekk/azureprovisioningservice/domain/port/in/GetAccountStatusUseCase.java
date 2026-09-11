package pl.jacekk.azureprovisioningservice.domain.port.in;

import pl.jacekk.azureprovisioningservice.domain.model.Account;

public interface GetAccountStatusUseCase {

    /**
     * @throws pl.jacekk.azureprovisioningservice.domain.model.AccountNotFoundException when no
     *         such account exists
     */
    Account getAccount(String accountId);
}
