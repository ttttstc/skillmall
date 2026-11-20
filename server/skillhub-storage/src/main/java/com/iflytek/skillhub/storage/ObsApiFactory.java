package com.iflytek.skillhub.storage;

/**
 * Creates an {@link ObsApi} bound to one credential generation.
 *
 * <p>The service calls this again whenever {@link ObsCredentialProvider} hands out new temporary
 * credentials, because OBS temporary credentials must be captured when the client is built.
 */
@FunctionalInterface
interface ObsApiFactory {
    ObsApi create(ObsCredentialProvider.Credentials credentials);
}
