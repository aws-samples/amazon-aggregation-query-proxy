// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.connectors;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.internal.retry.SdkDefaultRetrySetting;
import software.amazon.awssdk.core.retry.RetryMode;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionDDBFactoryTest {

    /**
     * Every retry must be able to happen before the call timeout. Worst case: each attempt runs
     * to its attempt timeout, and each back-off is the full, un-jittered throttling delay.
     * Reads the SDK's own constants (an internal class, acceptable in a test) so the check tracks
     * the SDK rather than a copy of its numbers. The previous 8 retries needed 35.8-71.5s of
     * back-off alone under a 30s call timeout, so the later retries could never happen.
     */
    @Test
    void retryBudgetFitsInsideTheCallTimeout() {
        RetryMode mode = RetryMode.defaultRetryMode();
        long base = SdkDefaultRetrySetting.throttledBaseDelay(mode).toMillis();
        long cap = SdkDefaultRetrySetting.MAX_BACKOFF.toMillis();

        long worstCase = (ConnectionDDBFactory.NUM_RETRIES_DDB + 1L)
                * ConnectionDDBFactory.API_CALL_ATTEMPT_TIMEOUT_DDB.toMillis();
        for (int retry = 0; retry < ConnectionDDBFactory.NUM_RETRIES_DDB; retry++) {
            worstCase += Math.min(cap, base << retry);
        }

        long callTimeout = ConnectionDDBFactory.API_CALL_TIMEOUT_DDB.toMillis();
        long worst = worstCase;
        assertTrue(worst <= callTimeout, () -> String.format(
                "worst case %s exceeds the %s call timeout", Duration.ofMillis(worst), Duration.ofMillis(callTimeout)));
    }
}
