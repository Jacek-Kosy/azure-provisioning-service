package pl.jacekk.azureprovisioningservice.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import pl.jacekk.azureprovisioningservice.adapter.out.persistence.AccountDocument;
import pl.jacekk.azureprovisioningservice.domain.model.ProvisioningStatus;
import pl.jacekk.azureprovisioningservice.domain.port.out.AzureProvisioningException;
import pl.jacekk.azureprovisioningservice.domain.port.out.AzureSubscriptionPort;
import pl.jacekk.azureprovisioningservice.domain.port.out.ManagementGroupPort;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole slice: HTTP in, Mongo underneath, the async workflow doing the work.
 *
 * <p>The Azure ports are replaced by mocks rather than the shipped stubs, because the point of
 * these tests is what the service does when a step fails — which the stubs never do.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AccountProvisioningEndToEndIT {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private static final String REQUEST = """
            {
              "subscriptionName": "team-alpha-prod",
              "targetManagementGroup": "mg-workloads",
              "labels": {
                "cost-center-id": "CC-1001",
                "cost-center-id-provider": "sap",
                "project-internal-id": "PRJ-42",
                "project-internal-id-provider": "servicenow"
              }
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MongoTemplate mongoTemplate;

    @MockitoBean
    private AzureSubscriptionPort subscriptions;

    @MockitoBean
    private ManagementGroupPort managementGroups;

    @BeforeEach
    void resetState() {
        mongoTemplate.remove(new Query(), AccountDocument.class);
        reset(subscriptions, managementGroups);
        when(subscriptions.ensureSubscription(any(), any())).thenReturn("sub-1");
    }

    private String postAccount(int expectedStatus) throws Exception {
        return mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private static String idOf(String responseBody) {
        return responseBody.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    private ProvisioningStatus awaitSettled(String accountId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            AccountDocument document = mongoTemplate.findById(accountId, AccountDocument.class);
            if (document != null && document.getStatus().isTerminal()) {
                return document.getStatus();
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Job " + accountId + " never reached a terminal status");
    }

    @Test
    void provisionsAFreshRequestThroughEveryStep() throws Exception {
        String accountId = idOf(postAccount(202));

        assertThat(awaitSettled(accountId)).isEqualTo(ProvisioningStatus.COMPLETED);

        verify(subscriptions).ensureSubscription("acct-" + accountId, "team-alpha-prod");
        verify(managementGroups).ensurePlacedUnder("sub-1", "mg-workloads");
        verify(subscriptions).ensureTags(eq("sub-1"), any());
        mockMvc.perform(get("/accounts/" + accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.azureSubscriptionId").value("sub-1"))
                .andExpect(jsonPath("$.labels['project-internal-id']").value("PRJ-42"));
    }

    @Test
    void refusesASecondRequestForANameThatIsAlreadyProvisioned() throws Exception {
        String accountId = idOf(postAccount(202));
        awaitSettled(accountId);

        String conflict = postAccount(409);

        assertThat(idOf(conflict)).isEqualTo(accountId);
        verify(subscriptions, times(1)).ensureSubscription(any(), any());
    }

    @Test
    void rejectsARequestWhoseLabelsAreIncomplete() throws Exception {
        String body = """
                {
                  "subscriptionName": "team-beta-prod",
                  "targetManagementGroup": "mg-workloads",
                  "labels": {"cost-center-id": "CC-1001"}
                }
                """;

        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations", org.hamcrest.Matchers.hasSize(3)));

        assertThat(mongoTemplate.count(new Query(), AccountDocument.class))
                .as("nothing is persisted for an invalid request")
                .isZero();
    }

    @Test
    void aRetryAdoptsTheSubscriptionTheFailedRunBuiltRatherThanCreatingASecond() throws Exception {
        doThrow(new AzureProvisioningException("management group not found"))
                .when(managementGroups).ensurePlacedUnder(any(), any());

        String accountId = idOf(postAccount(202));
        assertThat(awaitSettled(accountId)).isEqualTo(ProvisioningStatus.FAILED);
        AccountDocument failed = mongoTemplate.findById(accountId, AccountDocument.class);
        assertThat(failed.getErrorDetail())
                .isEqualTo("Step ASSIGNING_MANAGEMENT_GROUP failed: management group not found");
        assertThat(failed.getAzureSubscriptionId()).isEqualTo("sub-1");

        // The alias never moves, so the rerun's first step finds what the failed run built.
        doReturn("sub-1").when(subscriptions).ensureSubscription("acct-" + accountId, "team-alpha-prod");
        doNothing().when(managementGroups).ensurePlacedUnder(any(), any());

        assertThat(idOf(postAccount(202))).isEqualTo(accountId);
        assertThat(awaitSettled(accountId)).isEqualTo(ProvisioningStatus.COMPLETED);

        verify(subscriptions, times(2)).ensureSubscription("acct-" + accountId, "team-alpha-prod");
        mockMvc.perform(get("/accounts/" + accountId))
                .andExpect(jsonPath("$.azureSubscriptionId").value("sub-1"))
                .andExpect(jsonPath("$.errorDetail").doesNotExist());
    }

    @Test
    void aRetryAdoptsASubscriptionTheFailedRunNeverManagedToRecord() throws Exception {
        doThrow(new AzureProvisioningException("management group not found"))
                .when(managementGroups).ensurePlacedUnder(any(), any());
        String accountId = idOf(postAccount(202));
        assertThat(awaitSettled(accountId)).isEqualTo(ProvisioningStatus.FAILED);

        // Simulate the crash this design exists for: Azure created sub-1, but the id never
        // reached Mongo, so nothing in our records points at it.
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(accountId)),
                new Update().unset("azureSubscriptionId"),
                AccountDocument.class);
        doReturn("sub-1").when(subscriptions).ensureSubscription("acct-" + accountId, "team-alpha-prod");
        doNothing().when(managementGroups).ensurePlacedUnder(any(), any());

        postAccount(202);

        assertThat(awaitSettled(accountId)).isEqualTo(ProvisioningStatus.COMPLETED);
        mockMvc.perform(get("/accounts/" + accountId))
                .andExpect(jsonPath("$.azureSubscriptionId").value("sub-1"));
    }
}
