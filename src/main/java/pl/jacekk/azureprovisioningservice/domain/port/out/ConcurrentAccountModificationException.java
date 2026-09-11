package pl.jacekk.azureprovisioningservice.domain.port.out;

/**
 * Another replica wrote this account first. Raised by the persistence adapter in place of any
 * framework-specific optimistic-locking exception, so the application layer stays portable.
 */
public class ConcurrentAccountModificationException extends RuntimeException {

    public ConcurrentAccountModificationException(String accountId) {
        super("Account '%s' was modified concurrently".formatted(accountId));
    }
}
