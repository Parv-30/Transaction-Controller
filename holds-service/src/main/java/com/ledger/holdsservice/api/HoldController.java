package com.ledger.holdsservice.api;

import com.ledger.holdsservice.api.dto.AvailableBalanceResponse;
import com.ledger.holdsservice.api.dto.CaptureHoldRequest;
import com.ledger.holdsservice.api.dto.CreateHoldRequest;
import com.ledger.holdsservice.api.dto.HeldBalanceResponse;
import com.ledger.holdsservice.api.dto.HoldResponse;
import com.ledger.holdsservice.security.CallerContext;
import com.ledger.holdsservice.security.JwtRoleReader;
import com.ledger.holdsservice.service.HoldService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class HoldController {

    private final HoldService holdService;
    private final JwtRoleReader jwtRoleReader;

    public HoldController(HoldService holdService, JwtRoleReader jwtRoleReader) {
        this.holdService = holdService;
        this.jwtRoleReader = jwtRoleReader;
    }

    @PostMapping("/holds")
    public ResponseEntity<HoldResponse> create(
            @RequestBody CreateHoldRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        HoldResponse response = holdService.createHold(request, idempotencyKey);
        HttpStatus status = response.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (response.replay()) {
            builder.header("X-Idempotent-Replay", "true");
        }
        return builder.body(response);
    }

    @PostMapping("/holds/{id}/release")
    public ResponseEntity<HoldResponse> release(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(holdService.release(id));
    }

    @GetMapping("/holds/{id}")
    public ResponseEntity<HoldResponse> get(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(holdService.getHold(id));
    }

    @GetMapping("/holds")
    public ResponseEntity<List<HoldResponse>> list(
            @RequestParam(value = "accountRef", required = false) String accountRef,
            @RequestParam(value = "status", required = false) String status,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        CallerContext caller = jwtRoleReader.readRoles(authorizationHeader);
        return ResponseEntity.ok(holdService.listHolds(accountRef, status, caller));
    }

    @PostMapping("/holds/{id}/capture")
    public ResponseEntity<HoldResponse> capture(
            @PathVariable("id") UUID id,
            @RequestBody CaptureHoldRequest request) {
        return ResponseEntity.ok(holdService.capture(id, request.amountMinor()));
    }

    @GetMapping("/accounts/{accountRef}/available-balance")
    public ResponseEntity<AvailableBalanceResponse> availableBalance(@PathVariable("accountRef") String accountRef) {
        return ResponseEntity.ok(holdService.getAvailableBalance(accountRef));
    }

    @GetMapping("/accounts/{accountRef}/held-balance")
    public ResponseEntity<HeldBalanceResponse> heldBalance(@PathVariable("accountRef") String accountRef) {
        return ResponseEntity.ok(holdService.getHeldBalance(accountRef));
    }
}
