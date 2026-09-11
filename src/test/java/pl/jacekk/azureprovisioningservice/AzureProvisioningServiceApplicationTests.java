package pl.jacekk.azureprovisioningservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The application's index creation is eager and fatal, so the context needs a real Mongo. This
 * skips itself when no container runtime is available rather than failing the build.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AzureProvisioningServiceApplicationTests {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    @Test
    void contextLoads() {
    }

}
