package pl.jacekk.azureprovisioningservice.adapter.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.jacekk.azureprovisioningservice.config.SecurityConfig;
import pl.jacekk.azureprovisioningservice.domain.model.Account;
import pl.jacekk.azureprovisioningservice.domain.model.AccountNotFoundException;
import pl.jacekk.azureprovisioningservice.domain.model.InvalidLabelsException;
import pl.jacekk.azureprovisioningservice.domain.model.JobLease;
import pl.jacekk.azureprovisioningservice.domain.model.Labels;
import pl.jacekk.azureprovisioningservice.domain.port.in.AcceptanceOutcome;
import pl.jacekk.azureprovisioningservice.domain.port.in.AccountAcceptance;
import pl.jacekk.azureprovisioningservice.domain.port.in.CreateAccountCommand;
import pl.jacekk.azureprovisioningservice.domain.port.in.CreateAccountUseCase;
import pl.jacekk.azureprovisioningservice.domain.port.in.GetAccountStatusUseCase;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Import(SecurityConfig.class)
class AccountControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");

    private static final String VALID_BODY = """
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

    @MockitoBean
    private CreateAccountUseCase createAccount;

    @MockitoBean
    private GetAccountStatusUseCase getAccountStatus;

    private static Account account(String id) {
        return Account.newRequest(id, "team-alpha-prod", "mg-workloads",
                Labels.of(Map.of(
                        Labels.COST_CENTER_ID, "CC-1001",
                        Labels.COST_CENTER_ID_PROVIDER, "sap",
                        Labels.PROJECT_INTERNAL_ID, "PRJ-42",
                        Labels.PROJECT_INTERNAL_ID_PROVIDER, "servicenow")),
                new JobLease("job-1", "replica-a", NOW.plusSeconds(300)), NOW);
    }

    @Test
    void acceptsAFreshRequestAndPointsAtTheNewJob() throws Exception {
        when(createAccount.createAccount(any()))
                .thenReturn(new AccountAcceptance("acc-1", AcceptanceOutcome.CREATED));

        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "http://localhost/accounts/acc-1"))
                .andExpect(jsonPath("$.id").value("acc-1"))
                .andExpect(jsonPath("$.outcome").value("CREATED"));
    }

    @Test
    void passesTheRequestThroughToTheUseCaseUnchanged() throws Exception {
        when(createAccount.createAccount(any()))
                .thenReturn(new AccountAcceptance("acc-1", AcceptanceOutcome.CREATED));

        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY));

        org.mockito.ArgumentCaptor<CreateAccountCommand> command =
                org.mockito.ArgumentCaptor.forClass(CreateAccountCommand.class);
        org.mockito.Mockito.verify(createAccount).createAccount(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().subscriptionName())
                .isEqualTo("team-alpha-prod");
        org.assertj.core.api.Assertions.assertThat(command.getValue().targetManagementGroup())
                .isEqualTo("mg-workloads");
        org.assertj.core.api.Assertions.assertThat(command.getValue().labels())
                .containsEntry("cost-center-id", "CC-1001")
                .hasSize(4);
    }

    @Test
    void rejectsIncompleteLabelsWithTheViolationsThatCausedIt() throws Exception {
        when(createAccount.createAccount(any())).thenThrow(new InvalidLabelsException(
                List.of("label 'project-internal-id' is required",
                        "label 'cost-center-id' must not be blank")));

        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid labels"))
                .andExpect(jsonPath("$.violations", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.violations[0]").value("label 'project-internal-id' is required"));
    }

    @Test
    void rejectsARequestMissingTheSubscriptionNameBeforeReachingTheUseCase() throws Exception {
        String body = """
                {
                  "targetManagementGroup": "mg-workloads",
                  "labels": {"cost-center-id": "CC-1001"}
                }
                """;

        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations", org.hamcrest.Matchers.hasSize(1)));

        verifyNoInteractions(createAccount);
    }

    @Test
    void reportsAConflictPointingAtTheJobThatAlreadyHoldsTheName() throws Exception {
        when(createAccount.createAccount(any()))
                .thenReturn(new AccountAcceptance("acc-existing", AcceptanceOutcome.ALREADY_IN_PROGRESS));

        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(header().string("Location", "http://localhost/accounts/acc-existing"))
                .andExpect(jsonPath("$.id").value("acc-existing"))
                .andExpect(jsonPath("$.outcome").value("ALREADY_IN_PROGRESS"));
    }

    @Test
    void acceptsARetryOfAFailedJobOnTheSameName() throws Exception {
        when(createAccount.createAccount(any()))
                .thenReturn(new AccountAcceptance("acc-existing", AcceptanceOutcome.RETRY_ACCEPTED));

        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "http://localhost/accounts/acc-existing"))
                .andExpect(jsonPath("$.outcome").value("RETRY_ACCEPTED"));
    }

    @Test
    void returnsTheCurrentStateOfAJob() throws Exception {
        Account account = account("acc-1");
        account.startProvisioning(NOW);
        account.recordSubscriptionCreated("sub-123", NOW);
        when(getAccountStatus.getAccount("acc-1")).thenReturn(account);

        mockMvc.perform(get("/accounts/acc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("acc-1"))
                .andExpect(jsonPath("$.subscriptionName").value("team-alpha-prod"))
                .andExpect(jsonPath("$.targetManagementGroup").value("mg-workloads"))
                .andExpect(jsonPath("$.status").value("CREATING_SUBSCRIPTION"))
                .andExpect(jsonPath("$.azureSubscriptionId").value("sub-123"))
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.labels['cost-center-id']").value("CC-1001"))
                .andExpect(jsonPath("$.errorDetail").doesNotExist());
    }

    @Test
    void reportsWhyAFailedJobFailed() throws Exception {
        Account account = account("acc-1");
        account.startProvisioning(NOW);
        account.recordSubscriptionCreated("sub-123", NOW);
        account.startAssigningManagementGroup(NOW);
        account.failStep("management group not found", NOW);
        when(getAccountStatus.getAccount("acc-1")).thenReturn(account);

        mockMvc.perform(get("/accounts/acc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorDetail")
                        .value("Step ASSIGNING_MANAGEMENT_GROUP failed: management group not found"))
                .andExpect(jsonPath("$.azureSubscriptionId").value("sub-123"));
    }

    @Test
    void reportsAnUnknownJob() throws Exception {
        when(getAccountStatus.getAccount("nope")).thenThrow(new AccountNotFoundException("nope"));

        mockMvc.perform(get("/accounts/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Account not found"));
    }
}
