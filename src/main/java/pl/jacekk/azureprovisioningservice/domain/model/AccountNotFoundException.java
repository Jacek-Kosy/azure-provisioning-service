package pl.jacekk.azureprovisioningservice.domain.model;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String accountId) {
        super("No account with id '%s'".formatted(accountId));
    }
}
