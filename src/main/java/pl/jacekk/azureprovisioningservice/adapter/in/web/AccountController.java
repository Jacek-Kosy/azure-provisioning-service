package pl.jacekk.azureprovisioningservice.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import pl.jacekk.azureprovisioningservice.adapter.in.web.dto.AccountAcceptedResponse;
import pl.jacekk.azureprovisioningservice.adapter.in.web.dto.AccountResponse;
import pl.jacekk.azureprovisioningservice.adapter.in.web.dto.CreateAccountRequest;
import pl.jacekk.azureprovisioningservice.domain.port.in.AccountAcceptance;
import pl.jacekk.azureprovisioningservice.domain.port.in.CreateAccountCommand;
import pl.jacekk.azureprovisioningservice.domain.port.in.CreateAccountUseCase;
import pl.jacekk.azureprovisioningservice.domain.port.in.GetAccountStatusUseCase;

import java.net.URI;

/**
 * The driving adapter. It translates HTTP into use-case calls and the use case's decision into a
 * status code — it holds no workflow logic of its own, and never touches the async run.
 */
@RestController
@RequestMapping("/accounts")
@Tag(name = "Accounts", description = "Provision and inspect Azure subscriptions")
public class AccountController {

    private final CreateAccountUseCase createAccount;
    private final GetAccountStatusUseCase getAccountStatus;

    public AccountController(CreateAccountUseCase createAccount, GetAccountStatusUseCase getAccountStatus) {
        this.createAccount = createAccount;
        this.getAccountStatus = getAccountStatus;
    }

    @PostMapping
    @Operation(summary = "Request a subscription",
            description = "The subscription name is the idempotency key. A repeat of a name held by "
                    + "a job that has not failed is a conflict; a repeat of a failed one is accepted "
                    + "as a retry, which cleans up the previous attempt and reruns every step.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted as a new job or a retry"),
            @ApiResponse(responseCode = "400", description = "Labels missing or blank"),
            @ApiResponse(responseCode = "409", description = "Name held by a job that has not failed")
    })
    public ResponseEntity<AccountAcceptedResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountAcceptance acceptance = createAccount.createAccount(new CreateAccountCommand(
                request.subscriptionName(), request.targetManagementGroup(), request.labels()));

        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}")
                .buildAndExpand(acceptance.accountId())
                .toUri();
        AccountAcceptedResponse body = new AccountAcceptedResponse(acceptance.accountId(), acceptance.outcome());

        return acceptance.outcome().startedWork()
                ? ResponseEntity.accepted().location(location).body(body)
                : ResponseEntity.status(409).location(location).body(body);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read a provisioning job's current state")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The job's status, labels and any error"),
            @ApiResponse(responseCode = "404", description = "No such job")
    })
    public AccountResponse get(@PathVariable String id) {
        return AccountResponse.from(getAccountStatus.getAccount(id));
    }
}
