package org.bidib.jbidibc.message;

import java.io.IOException;

import org.bidib.jbidibc.Bidib;
import org.bidib.jbidibc.BidibLibrary;
import org.bidib.jbidibc.MessageReceiver;
import org.bidib.jbidibc.exception.ProtocolException;
import org.bidib.jbidibc.node.BidibNode;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tests for FeatureGetMessage
 *
 */
public class FeatureGetMessageTest {

    @Test
    public void createMessageFeatureGetFromByteArray() throws ProtocolException {
        byte[] message = { 0x05, 0x02, 0x00, (byte) 0xD6, 0x12, 0x16, (byte) 0x9d };

        BidibMessage bidibMessage = new BidibMessage(message);

        Assert.assertEquals(bidibMessage.getType(), BidibLibrary.MSG_FEATURE_GET);
    }

    @Test
    public void createMessageFeatureGet() throws ProtocolException {

        int number = BidibLibrary.FEATURE_CTRL_MAC_COUNT;
        FeatureGetMessage message = new FeatureGetMessage(number);

        Assert.assertEquals(message.getType(), BidibLibrary.MSG_FEATURE_GET);
        Assert.assertEquals(message.getData()[0], BidibLibrary.FEATURE_CTRL_MAC_COUNT);
    }

    @Test
    public void createMessageFeatureGetAndSend() throws ProtocolException, IOException, InterruptedException {

        int number = BidibLibrary.FEATURE_CTRL_MAC_COUNT;
        FeatureGetMessage message = new FeatureGetMessage(number);

        Assert.assertEquals(message.getType(), BidibLibrary.MSG_FEATURE_GET);

        DummyNode dummyNode = new DummyNode(new byte[] { 0, 1 }, null);
        dummyNode.setBidib(Bidib.getInstance());

        // send the message
        dummyNode.sendNoWait(message);

        // we assume the SendMsgNum is 0
        Assert.assertTrue(message.getNum() == 0);

        // send the message again
        dummyNode.sendNoWait(message);

        // we assume the SendMsgNum is 1 now
        Assert.assertTrue(message.getNum() == 1);
    }

    private static final class DummyNode extends BidibNode {

        protected DummyNode(byte[] address, MessageReceiver messageReceiver) {
            super(address, messageReceiver);
        }

        @Override
        protected void sendNoWait(BidibMessage message) throws ProtocolException {
            super.sendNoWait(message);
        }

    }
}
