package org.zjmproject.controller;

import org.springframework.web.bind.annotation.*;
import org.zjmproject.pojo.Result;
import org.zjmproject.service.AccessGuard;
import org.zjmproject.service.MembershipService;

@RestController
@CrossOrigin
@RequestMapping("/user/membership")
public class MembershipController {
    public record PurchaseRequest(String planCode) {}
    private final MembershipService membership;

    public MembershipController(MembershipService membership) { this.membership = membership; }

    @GetMapping("/plans")
    public Result<?> plans() {
        AccessGuard.userId();
        return Result.success(membership.plans());
    }

    @PostMapping("/orders")
    public Result<?> create(@RequestBody PurchaseRequest request,
                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return Result.success(membership.create(AccessGuard.userId(), request.planCode(), key));
    }

    @GetMapping("/orders/current")
    public Result<?> current() { return Result.success(membership.current(AccessGuard.userId())); }

    @PostMapping("/orders/{id}/pay")
    public Result<?> pay(@PathVariable String id) {
        return Result.success(membership.pay(AccessGuard.userId(), id));
    }
}
