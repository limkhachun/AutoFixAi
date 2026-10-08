package com.portfolio;
import java.security.Principal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/simulation")
public class SimulationController {
    private final SimulationService simulation;
    private final MarketService markets;
    public SimulationController(SimulationService simulation,MarketService markets) { this.simulation=simulation;this.markets=markets; }
    @GetMapping public SimulationService.Wallet wallet(Principal p) { return simulation.wallet(p.getName()); }
    @GetMapping("/quotes") public MarketService.Market quotes() { return markets.snapshot(); }
    @PostMapping("/requests") @ResponseStatus(HttpStatus.CREATED)
    public void request(Principal p,@Valid @RequestBody SimulationService.FundingInput input) { simulation.request(p.getName(),input); }
    @GetMapping("/admin/requests") public List<SimulationService.Request> requests(Principal p) { return simulation.adminRequests(p.getName()); }
    record Review(@NotNull Boolean approve) {}
    @PostMapping("/admin/requests/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void review(Principal p,@PathVariable long id,@Valid @RequestBody Review input) { simulation.review(p.getName(),id,input.approve()); }
    @PostMapping("/trades") @ResponseStatus(HttpStatus.CREATED)
    public void trade(Principal p,@Valid @RequestBody SimulationService.Order order) {
        var quote=markets.snapshot().quotes().stream().filter(q->q.asset().equals(order.asset())).findFirst().orElse(null);
        simulation.trade(p.getName(),order,quote);
    }
}
