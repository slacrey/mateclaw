package vip.mate.browser.orchestrator.engine;

import org.springframework.stereotype.Component;
import vip.mate.browser.orchestrator.domain.GroundedTarget;
import vip.mate.browser.orchestrator.domain.GroundingEngine;
import vip.mate.browser.orchestrator.domain.GroundingHint;
import vip.mate.browser.orchestrator.domain.GroundingResult;
import vip.mate.browser.orchestrator.domain.PageSnapshot;

@Component
public class DomEngine implements GroundingEngine {

    @Override
    public String name() {
        return "dom";
    }

    @Override
    public GroundingResult ground(PageSnapshot snapshot, GroundingHint hint) {
        return switch (hint) {
            case GroundingHint.A11yMatch m -> groundByA11yMatch(snapshot, m);
            case GroundingHint.ByRefId r -> groundByRefId(snapshot, r);
        };
    }

    private GroundingResult groundByA11yMatch(PageSnapshot snapshot, GroundingHint.A11yMatch hint) {
        var matches = snapshot.lines().stream()
                .filter(line -> line.role().equalsIgnoreCase(hint.role()))
                .filter(line -> hint.namePattern().matcher(line.name()).matches())
                .toList();

        if (matches.isEmpty()) {
            return new GroundingResult.Miss(
                    "no role+name match for role=" + hint.role()
                            + " name=/" + hint.namePattern().pattern() + "/");
        }

        if (matches.size() == 1) {
            var line = matches.getFirst();
            return new GroundingResult.Hit(
                    new GroundedTarget(line.bbox(), line.refId()),
                    "role+name match: role=" + line.role()
                            + " name=\"" + line.name() + "\" ref=" + line.refId());
        }

        var candidates = matches.stream()
                .map(line -> new GroundedTarget(line.bbox(), line.refId()))
                .toList();
        return new GroundingResult.Ambiguous(
                candidates,
                matches.size() + " elements with role=" + hint.role()
                        + " matching /" + hint.namePattern().pattern() + "/");
    }

    private GroundingResult groundByRefId(PageSnapshot snapshot, GroundingHint.ByRefId hint) {
        return snapshot.lines().stream()
                .filter(line -> line.refId().equals(hint.refId()))
                .findFirst()
                .<GroundingResult>map(line -> new GroundingResult.Hit(
                        new GroundedTarget(line.bbox(), line.refId()),
                        "ref " + hint.refId()))
                .orElseGet(() -> new GroundingResult.Miss("ref " + hint.refId() + " not in snapshot"));
    }
}
