package vip.mate.browser.orchestrator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import vip.mate.browser.edge.action.ActionRequest;
import vip.mate.browser.edge.action.ActionResult;
import vip.mate.browser.edge.session.BrowserSession;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlanExecutionService {

    private final ActionExecutionService actionExec;

    /**
     * Execute the plan sequentially. Each action result is awaited before
     * the next action is sent, preserving the browser indicator timing contract.
     */
    public Mono<PlanResult> execute(BrowserSession session, List<ActionRequest> plan) {
        if (plan == null || plan.isEmpty()) {
            return Mono.just(PlanResult.success(List.of()));
        }

        return Flux.fromIterable(plan)
                .concatMap(req -> Mono.from(actionExec.execute(session, req))
                        .map(result -> new ExecutedStep(req, result)))
                .takeUntil(step -> step.result() instanceof ActionResult.Failure)
                .collectList()
                .map(this::shapeResult);
    }

    private PlanResult shapeResult(List<ExecutedStep> steps) {
        var successes = new ArrayList<ActionResult.Success>();
        for (var step : steps) {
            switch (step.result()) {
                case ActionResult.Success success -> successes.add(success);
                case ActionResult.Failure failure -> {
                    return PlanResult.partial(successes, failure);
                }
            }
        }
        return PlanResult.success(successes);
    }

    private record ExecutedStep(ActionRequest request, ActionResult result) {
    }
}
