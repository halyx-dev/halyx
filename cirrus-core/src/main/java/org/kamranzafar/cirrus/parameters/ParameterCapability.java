package org.kamranzafar.cirrus.parameters;

import org.kamranzafar.cirrus.capability.Capability;

/** Optional parameter store features. */
public enum ParameterCapability implements Capability {
    /** Reading earlier versions with {@link ParameterStore#get(String, String)}. */
    VERSIONS
}
