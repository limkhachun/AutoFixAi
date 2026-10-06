package com.portfolio;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PortfolioController {
    private final PortfolioService portfolios;
    private final MarketService markets;
    public PortfolioController(PortfolioService portfolios, MarketService markets) { this.portfolios=portfolios;this.markets=markets; }
    @GetMapping("/transactions") public List<PortfolioService.Transaction> history(Principal principal) { return portfolios.history(principal.getName()); }
    @GetMapping("/portfolio") public PortfolioService.Summary summary(Principal principal) { return portfolios.summary(principal.getName()); }
    public record Dashboard(List<PortfolioService.Transaction> trades, PortfolioService.Summary summary, MarketService.Market market, MarketService.Valuation valuation) {}
    @GetMapping("/portfolio/view") public Dashboard view(Principal principal) {
        var view=portfolios.view(principal.getName());
        var market=markets.snapshot();
        return new Dashboard(view.trades(),view.summary(),market,markets.value(view.summary(),market));
    }
    @PostMapping("/transactions") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Long> create(Principal principal,@Valid @RequestBody PortfolioService.Input input) { return Map.of("id",portfolios.create(principal.getName(),input)); }
    @PutMapping("/transactions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void edit(Principal principal,@PathVariable long id,@Valid @RequestBody PortfolioService.Input input) { portfolios.edit(principal.getName(),id,input); }
    @DeleteMapping("/transactions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Principal principal,@PathVariable long id) { portfolios.delete(principal.getName(),id); }
    @PostMapping("/transactions/{id}/restore") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(Principal principal,@PathVariable long id) { portfolios.restore(principal.getName(),id); }
}
