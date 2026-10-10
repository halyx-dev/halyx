package org.kamranzafar.stratum.parameters;

import org.kamranzafar.stratum.capability.Capability;

/** Optional parameter store features. */
public enum ParameterCapability implements Capability {
    /** Reading earlier versions with {@link ParameterStore#get(String, String)}. */
    VERSIONS
}
