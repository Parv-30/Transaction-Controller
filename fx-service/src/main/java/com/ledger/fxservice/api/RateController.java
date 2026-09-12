package com.ledger.fxservice.api;

import com.ledger.fxservice.api.dto.QuoteRequest;
import com.ledger.fxservice.api.dto.QuoteResponse;
import com.ledger.fxservice.api.dto.RateResponse;
import com.ledger.fxservice.service.RateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
public class RateController {

    private final RateService rateService;

    public RateController(RateService rateService) {
        this.rateService = rateService;
    }

    @GetMapping("/rates/{base}/{quote}")
    public ResponseEntity<RateResponse> getRate(@PathVariable("base") String base,
                                                 @PathVariable("quote") String quote,
                                                 @RequestParam(value = "at", required = false) Instant at) {
        RateResponse response = (at != null)
                ? rateService.getRateAt(base, quote, at)
                : rateService.getLatestRate(base, quote);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/conversions/quote")
    public ResponseEntity<QuoteResponse> lockQuote(@RequestBody QuoteRequest request) {
        return ResponseEntity.ok(rateService.lockQuote(
                request.baseCurrency(), request.quoteCurrency(), request.amountMinor()));
    }
}
