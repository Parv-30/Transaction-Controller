package com.ledger.ledgerservice.api;

import com.ledger.ledgerservice.fx.CreateCrossCurrencyTransferRequest;
import com.ledger.ledgerservice.fx.CrossCurrencyTransferService;
import com.ledger.ledgerservice.fx.PendingFxTransferResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CrossCurrencyTransferController {

    private final CrossCurrencyTransferService crossCurrencyTransferService;

    public CrossCurrencyTransferController(CrossCurrencyTransferService crossCurrencyTransferService) {
        this.crossCurrencyTransferService = crossCurrencyTransferService;
    }

    @PostMapping("/transfers/cross-currency")
    public ResponseEntity<PendingFxTransferResponse> transfer(
            @RequestBody CreateCrossCurrencyTransferRequest request) {
        PendingFxTransferResponse response = crossCurrencyTransferService.transfer(request);
        // COMPLETED means money actually reached the destination -- the only outcome that is
        // unambiguously a success from the caller's point of view. Any other status (in
        // particular COMPENSATED, where money left the source and came back rather than
        // reaching the destination) is reported as 422, matching how this API already reports
        // "request was well-formed but the requested movement of money could not be carried
        // out" elsewhere (see ApiExceptionHandler's handling of InsufficientFundsException /
        // AccountNotActiveException). The body still carries the full PendingFxTransferResponse
        // (including errorMessage) either way, so callers who prefer to branch on the body's
        // status field instead of the HTTP status code can still do so.
        HttpStatus httpStatus = "COMPLETED".equals(response.status())
                ? HttpStatus.OK
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(httpStatus).body(response);
    }
}
