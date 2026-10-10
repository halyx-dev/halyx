package org.kamranzafar.cirrus.gcp.parameters;

import java.time.Instant;

/** Test access to package-private helpers of {@link ParameterManagerDriver}. */
public final class ParameterManagerDriverAccess {

    private ParameterManagerDriverAccess() {
    }

    public static String newVersionId(Instant now) {
        return ParameterManagerDriver.newVersionId(now);
    }
}
