package pl.jacekk.azureprovisioningservice.domain.port.out;

/** A step of the workflow failed in Azure. */
public class AzureProvisioningException extends RuntimeException {

    public AzureProvisioningException(String message) {
        super(message);
    }

    public AzureProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }
}
