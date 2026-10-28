package com.rawwave.core;

import java.util.Map;

/** Contract versions, not claims of full replaceability. Implementation source is bound by buildRun/hash. */
public final class ModuleVersions {
    private ModuleVersions() {}
    public static Map<String,Integer> contracts() {
        return Map.of("sensor",SensorPort.CONTRACT_VERSION,"transport",TransportPort.CONTRACT_VERSION,
            "resourceOwnership",ResourceHandoff.CONTRACT_VERSION,"sendStore",SendStore.CONTRACT_VERSION,
            "receiveStore",ReceiveStore.CONTRACT_VERSION,"attemptAdmission",TransferAttempt.CONTRACT_VERSION,
            "commitExchange",CommitExchange.CONTRACT_VERSION,"executionLease",LeaseScope.CONTRACT_VERSION,
            "capturePhase",CapturePhasePolicy.CONTRACT_VERSION,"summaryStore",SummaryStore.CONTRACT_VERSION);
    }
}
