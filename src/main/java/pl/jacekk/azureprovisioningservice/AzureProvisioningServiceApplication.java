package pl.jacekk.azureprovisioningservice;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@OpenAPIDefinition(info = @Info(
        title = "Azure Subscription Provisioning Service",
        version = "v1",
        description = "Requests an Azure subscription, moves it into a management group and tags it. "
                + "Work runs asynchronously; poll GET /accounts/{id} for progress."))
public class AzureProvisioningServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AzureProvisioningServiceApplication.class, args);
    }

}
