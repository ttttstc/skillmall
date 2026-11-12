package com.iflytek.skillhub.gitcode;

/**
 * How a binding stays attached to its source.
 *
 * <p>Phase one only creates {@link #ONE_SHOT} bindings: the imported package is an ordinary
 * skill that receives no further automatic updates. {@link #BOUND} is reserved for the later
 * sync phase and is never written by the current import flow.</p>
 */
public enum GitcodeLinkType {
    ONE_SHOT,
    BOUND
}
