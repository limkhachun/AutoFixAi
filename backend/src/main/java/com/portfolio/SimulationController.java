package com.portfolio;
import java.security.Principal;
import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/simulation")
public class SimulationController {
    private final SimulationService simulation;
    private final MarketService markets;
    public SimulationController(SimulationService simulation,MarketService markets) { this.simulation=simulation;this.markets=markets; }
    @GetMapping public SimulationService.Wallet wallet(Principal p) { return simulation.wallet(p.getName(),markets.snapshot()); }
    @GetMapping("/quotes") public MarketService.Market quotes() { return markets.snapshot(); }
    @PostMapping("/requests") @ResponseStatus(HttpStatus.CREATED)
    public void request(Principal p,@Valid @RequestBody SimulationService.FundingInput input) { simulation.request(p.getName(),input); }
    @GetMapping("/admin/requests") public List<SimulationService.Request> requests(Principal p) { return simulation.adminRequests(p.getName()); }
    @GetMapping("/admin/credits") public List<SimulationService.Credit> credits(Principal p) { return simulation.adminCredits(p.getName()); }
    @GetMapping("/admin/accounts") public SimulationService.AccountPage accounts(Principal p,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="0") int page) {
        return simulation.accounts(p.getName(),search,page);
    }
    @PostMapping("/admin/requests/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void review(Principal p,@PathVariable long id,@Valid @RequestBody SimulationService.ReviewInput input) { simulation.review(p.getName(),id,input); }
    @PostMapping("/admin/grants") @ResponseStatus(HttpStatus.CREATED)
    public void grant(Principal p,@Valid @RequestBody SimulationService.GrantInput input) { simulation.grant(p.getName(),input); }
    @PostMapping("/trade-preview") @ResponseStatus(HttpStatus.CREATED)
    public SimulationService.Preview preview(Principal p,@Valid @RequestBody SimulationService.Order input) { return simulation.preview(p.getName(),input,markets.snapshot()); }
    @PostMapping("/trades") @ResponseStatus(HttpStatus.CREATED)
    public void trade(Principal p,@Valid @RequestBody SimulationService.Execution input) { simulation.trade(p.getName(),input,markets.snapshot()); }
}
