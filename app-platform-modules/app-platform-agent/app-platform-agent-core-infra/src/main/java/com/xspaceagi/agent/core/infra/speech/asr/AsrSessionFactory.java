package com.xspaceagi.agent.core.infra.speech.asr;

import com.xspaceagi.agent.core.infra.speech.protocol.AudioParams;

public interface AsrSessionFactory {

    AsrService createSession(AudioParams audioParams);
}
