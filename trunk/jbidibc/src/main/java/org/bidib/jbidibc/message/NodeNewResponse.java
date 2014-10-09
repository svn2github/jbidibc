package org.bidib.jbidibc.message;

import org.bidib.jbidibc.BidibLibrary;
import org.bidib.jbidibc.exception.ProtocolException;
import org.bidib.jbidibc.utils.ByteUtils;

public class NodeNewResponse extends NodeTabResponse {
    public static final Integer TYPE = BidibLibrary.MSG_NODE_NEW;

    NodeNewResponse(byte[] addr, int num, int type, byte... data) throws ProtocolException {
        super(addr, num, type, data);
    }

    public NodeNewResponse(byte[] addr, int num, byte version, byte localAddr, long uniqueId) throws ProtocolException {
        this(addr, num, BidibLibrary.MSG_NODE_NEW, ByteUtils.concat(new byte[] { version, localAddr },
            ByteUtils.getVidPidFromUniqueId(uniqueId)));
    }

    public String getName() {
        return "MSG_NODE_NEW";
    }
}
