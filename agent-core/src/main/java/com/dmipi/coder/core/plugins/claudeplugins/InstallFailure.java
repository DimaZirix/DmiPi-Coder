package com.dmipi.coder.core.plugins.claudeplugins;

/** An installation step that could not complete; the message names what failed and the offending value. */
final class InstallFailure extends RuntimeException {

    InstallFailure(final String message) {
        super(message);
    }
}
