package pl.jacekk.azureprovisioningservice.adapter.in.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pl.jacekk.azureprovisioningservice.domain.model.AccountNotFoundException;
import pl.jacekk.azureprovisioningservice.domain.model.InvalidLabelsException;

import java.util.List;

/** Turns domain and binding failures into RFC 7807 responses. */
@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(InvalidLabelsException.class)
    ProblemDetail onInvalidLabels(InvalidLabelsException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid labels");
        problem.setDetail("The request does not carry the four required labels with non-blank values");
        problem.setProperty("violations", e.getViolations());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onInvalidRequest(MethodArgumentNotValidException e) {
        List<String> violations = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage() == null
                        ? error.getField() + " is invalid"
                        : error.getDefaultMessage())
                .toList();
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid request");
        problem.setProperty("violations", violations);
        return problem;
    }

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail onAccountNotFound(AccountNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Account not found");
        problem.setDetail(e.getMessage());
        return problem;
    }
}
