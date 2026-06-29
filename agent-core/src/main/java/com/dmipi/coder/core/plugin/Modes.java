package com.dmipi.coder.core.plugin;

import com.dmipi.coder.core.domain.permissions.Mode;

/** The modes capability: read the active approval mode and switch it — the seam a plan tool uses to unlock work once its plan is approved. */
public interface Modes {

    Mode current();

    void switchTo(Mode mode);
}
