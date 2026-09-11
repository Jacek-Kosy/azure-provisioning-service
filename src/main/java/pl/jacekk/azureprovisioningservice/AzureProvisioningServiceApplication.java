package pl.jacekk.azureprovisioningservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AzureProvisioningServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AzureProvisioningServiceApplication.class, args);
    }

}
