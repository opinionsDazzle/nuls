package io.nuls.core.mesasge;

import io.nuls.core.mesasge.constant.MessageTypeEnum;

/**
 * Created by Niels on 2017/11/10.
 *
 */
public class EventMessage extends NulsMessage {

    public EventMessage(NulsMessageHeader header, byte[] data) {
        super(header, data);
    }
}