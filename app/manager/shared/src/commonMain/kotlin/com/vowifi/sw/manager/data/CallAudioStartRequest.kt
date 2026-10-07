package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineProtocolFrame

internal suspend fun startCallAudioControl(session: CallAudioSession, control: CallControlTransport): EngineProtocolFrame.Response =
    withEngineRequestTimeout(CallProtocolCodec.AUDIO_START_TIMEOUT_MILLIS) {
        control.request(session.iccid, "audioStart", CallProtocolCodec.audioStartParams(session.token), CallProtocolCodec.AUDIO_START_TIMEOUT_MILLIS)
    }
