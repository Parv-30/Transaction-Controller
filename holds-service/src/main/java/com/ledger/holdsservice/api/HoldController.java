package com.ledger.holdsservice.api;

import com.ledger.holdsservice.api.dto.CreateHoldRequest;
import com.ledger.holdsservice.api.dto.HoldResponse;
import com.ledger.holdsservice.service.HoldService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class HoldController {

    private final HoldService holdService;

    public HoldController(HoldService holdService) {
        this.holdService = holdService;
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
}
