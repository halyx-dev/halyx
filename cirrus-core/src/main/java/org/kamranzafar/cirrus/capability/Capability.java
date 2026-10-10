package org.kamranzafar.cirrus.capability;

/**
 * An optional feature that a provider may or may not support. Each service defines an enum of its
 * capabilities (see {@code StorageCapability}).
 */
public interface Capability {

    String name();
}
