package pl.jacekk.azureprovisioningservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI provisioningOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Azure Subscription Provisioning Service")
                .version("v1")
                .description("Requests an Azure subscription, moves it into a management group and "
                        + "tags it. Work runs asynchronously; poll GET /accounts/{id} for progress."));
    }
}
