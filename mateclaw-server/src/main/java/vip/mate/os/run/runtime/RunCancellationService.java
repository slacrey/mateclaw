package vip.mate.os.run.runtime;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RunCancellationService {

    private final Set<Long> cancellingRuns = ConcurrentHashMap.newKeySet();

    public void requestCancel(Long runId) {
        if (runId != null) {
            cancellingRuns.add(runId);
        }
    }

    public boolean isCancellationRequested(Long runId) {
        return runId != null && cancellingRuns.contains(runId);
    }

    public void clear(Long runId) {
        if (runId != null) {
            cancellingRuns.remove(runId);
        }
    }
}
