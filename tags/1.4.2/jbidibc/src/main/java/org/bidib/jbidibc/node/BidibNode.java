package org.bidib.jbidibc.node;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.bidib.jbidibc.Bidib;
import org.bidib.jbidibc.BidibInterface;
import org.bidib.jbidibc.BidibLibrary;
import org.bidib.jbidibc.CRC8;
import org.bidib.jbidibc.Feature;
import org.bidib.jbidibc.FirmwareUpdateStat;
import org.bidib.jbidibc.MessageReceiver;
import org.bidib.jbidibc.Node;
import org.bidib.jbidibc.ProtocolVersion;
import org.bidib.jbidibc.SoftwareVersion;
import org.bidib.jbidibc.VendorData;
import org.bidib.jbidibc.enumeration.FirmwareUpdateOperation;
import org.bidib.jbidibc.enumeration.IdentifyState;
import org.bidib.jbidibc.exception.ProtocolException;
import org.bidib.jbidibc.exception.ProtocolNoAnswerException;
import org.bidib.jbidibc.message.BidibMessage;
import org.bidib.jbidibc.message.BoostOffMessage;
import org.bidib.jbidibc.message.BoostOnMessage;
import org.bidib.jbidibc.message.BoostQueryMessage;
import org.bidib.jbidibc.message.FeatureCountResponse;
import org.bidib.jbidibc.message.FeatureGetAllMessage;
import org.bidib.jbidibc.message.FeatureGetMessage;
import org.bidib.jbidibc.message.FeatureGetNextMessage;
import org.bidib.jbidibc.message.FeatureNotAvailableResponse;
import org.bidib.jbidibc.message.FeatureResponse;
import org.bidib.jbidibc.message.FeatureSetMessage;
import org.bidib.jbidibc.message.FeedbackGetAddressRangeMessage;
import org.bidib.jbidibc.message.FeedbackGetConfidenceMessage;
import org.bidib.jbidibc.message.FeedbackGetRangeMessage;
import org.bidib.jbidibc.message.FeedbackMirrorFreeMessage;
import org.bidib.jbidibc.message.FeedbackMirrorMultipleMessage;
import org.bidib.jbidibc.message.FeedbackMirrorOccupiedMessage;
import org.bidib.jbidibc.message.FwUpdateOpMessage;
import org.bidib.jbidibc.message.FwUpdateStatResponse;
import org.bidib.jbidibc.message.NodeTabCountResponse;
import org.bidib.jbidibc.message.NodeTabGetAllMessage;
import org.bidib.jbidibc.message.NodeTabGetNextMessage;
import org.bidib.jbidibc.message.NodeTabResponse;
import org.bidib.jbidibc.message.SysDisableMessage;
import org.bidib.jbidibc.message.SysEnableMessage;
import org.bidib.jbidibc.message.SysErrorResponse;
import org.bidib.jbidibc.message.SysGetPVersionMessage;
import org.bidib.jbidibc.message.SysGetSwVersionMessage;
import org.bidib.jbidibc.message.SysIdentifyMessage;
import org.bidib.jbidibc.message.SysMagicMessage;
import org.bidib.jbidibc.message.SysMagicResponse;
import org.bidib.jbidibc.message.SysPVersionResponse;
import org.bidib.jbidibc.message.SysPingMessage;
import org.bidib.jbidibc.message.SysPongResponse;
import org.bidib.jbidibc.message.SysResetMessage;
import org.bidib.jbidibc.message.SysSwVersionResponse;
import org.bidib.jbidibc.message.SysUniqueIdMessage;
import org.bidib.jbidibc.message.SysUniqueIdResponse;
import org.bidib.jbidibc.message.VendorAckResponse;
import org.bidib.jbidibc.message.VendorDisableMessage;
import org.bidib.jbidibc.message.VendorEnableMessage;
import org.bidib.jbidibc.message.VendorGetMessage;
import org.bidib.jbidibc.message.VendorResponse;
import org.bidib.jbidibc.message.VendorSetMessage;
import org.bidib.jbidibc.node.listener.TransferListener;
import org.bidib.jbidibc.utils.ByteUtils;
import org.bidib.jbidibc.utils.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BidibNode {
    private static final Logger LOGGER = LoggerFactory.getLogger(BidibNode.class);

    private static final Logger MSG_TX_LOGGER = LoggerFactory.getLogger("TX");

    private final List<TransferListener> listeners = new LinkedList<TransferListener>();

    private final byte[] addr;

    private final StringBuilder logRecord = new StringBuilder();

    private int nextReceiveMsgNum = 0;

    private Semaphore nextReceiveMsgNumSemaphore = new Semaphore(1);

    private int nextSendMsgNum = -1;

    private Semaphore nextSendMsgNumSemaphore = new Semaphore(1);

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private MessageReceiver messageReceiver;

    private BidibInterface bidib;

    private Integer nodeMagic;

    private BlockingQueue<BidibMessage> receiveQueue = new LinkedBlockingQueue<BidibMessage>();

    /**
     * Create a new BidibNode that represents a connected node (slave) on the BiDiB bus.
     * 
     * @param addr
     *            the address
     * @param messageReceiver
     *            the message receiver
     */
    protected BidibNode(byte[] addr, MessageReceiver messageReceiver) {
        this.addr = addr != null ? addr.clone() : null;
        this.messageReceiver = messageReceiver;
        LOGGER.debug("Create new BidibNode with address: {}", addr);
    }

    /**
     * @param bidib the bidib to set
     */
    public void setBidib(BidibInterface bidib) {
        this.bidib = bidib;
    }

    protected MessageReceiver getMessageReceiver() {
        return messageReceiver;
    }

    public BlockingQueue<BidibMessage> getReceiveQueue() {
        return receiveQueue;
    }

    /**
     * @return the magic
     */
    public Integer getNodeMagic() {
        return nodeMagic;
    }

    /**
     * @param magic the magic to set
     */
    public void setNodeMagic(Integer magic) {
        LOGGER.debug("Set magic of node: {}", magic);
        this.nodeMagic = magic;
    }

    /**
     * Returns if the node is a bootloader node with limited functionality.
     * @return node is bootloader node
     */
    public boolean isBootloaderNode() {
        return (BidibLibrary.BIDIB_BOOT_MAGIC == nodeMagic);
    }

    /**
     * Send the feedback mirror free message for the specified detector number.
     * @param detectorNumber the detector number
     * @throws ProtocolException
     */
    public void acknowledgeFree(int detectorNumber) throws ProtocolException {
        sendNoWait(new FeedbackMirrorFreeMessage(detectorNumber));
    }

    /**
     * Send the feedback mirror multiple message.
     * @param baseAddress the base address
     * @param size the size
     * @param detectorData the detector data
     * @throws ProtocolException
     */
    public void acknowledgeMultiple(int baseAddress, int size, byte[] detectorData) throws ProtocolException {
        LOGGER.debug("Send acknowledge multiple to baseAddress: {}", baseAddress);
        sendNoWait(new FeedbackMirrorMultipleMessage(baseAddress, size, detectorData));
    }

    /**
     * Send the feedback mirror occupied message for the specified detector number.
     * @param detectorNumber the detector number
     * @throws ProtocolException
     */
    public void acknowledgeOccupied(int detectorNumber) throws ProtocolException {
        sendNoWait(new FeedbackMirrorOccupiedMessage(detectorNumber));
    }

    //    /**
    //     * Send the accessory state acknowledgement message for the specified accessory.
    //     * @param accessoryState the accessory state
    //     * @throws ProtocolException
    //     */
    //    public void acknowledgeAccessoryState(AccessoryState accessoryState) throws ProtocolException {
    //        // DO NOTHING
    //    }

    public void addTransferListener(TransferListener l) {
        listeners.add(l);
    }

    public List<TransferListener> getTransferListeners() {
        return listeners;
    }

    /**
     * Switch on track signal on the booster.
     * 
     * @throws ProtocolException
     * @deprecated Use BoosterNode to send this message
     */
    @Deprecated
    public void boosterOn() throws ProtocolException {
        sendNoWait(new BoostOnMessage());
    }

    /**
     * Switch on track signal on the booster.
     * @param broadcast broadcast command
     * @throws ProtocolException
     */
    public void boosterOn(byte broadcast) throws ProtocolException {
        sendNoWait(new BoostOnMessage(broadcast));
    }

    /**
     * Switch off track signal on the booster.
     * 
     * @throws ProtocolException
     * @deprecated Use BoosterNode to send this message
     */
    @Deprecated
    public void boosterOff() throws ProtocolException {
        sendNoWait(new BoostOffMessage());
    }

    /**
     * Switch off track signal on the booster.
     * @param broadcast broadcast command
     * @throws ProtocolException
     */
    public void boosterOff(byte broadcast) throws ProtocolException {
        sendNoWait(new BoostOffMessage(broadcast));
    }

    /**
     * Query the booster state. We don't wait for the response because the {@link MessageReceiver} fires the booster
     * status callback on receipt.
     * 
     * @throws ProtocolException
     * @deprecated Use BoosterNode to send this message
     */
    public void boosterQuery() throws ProtocolException {
        sendNoWait(new BoostQueryMessage());
    }

    private void escape(byte c) throws IOException {
        if ((c == (byte) BidibLibrary.BIDIB_PKT_MAGIC) || (c == (byte) BidibLibrary.BIDIB_PKT_ESCAPE)) {
            output.write((byte) BidibLibrary.BIDIB_PKT_ESCAPE);
            c = (byte) (c ^ 0x20);
        }
        output.write(c);
    }

    private void fireReceiveStarted() {
        for (TransferListener l : listeners) {
            l.receiveStarted();
        }
    }

    private void fireReceiveStopped() {
        for (TransferListener l : listeners) {
            l.receiveStopped();
        }
    }

    private void fireSendStarted() {
        for (TransferListener l : listeners) {
            l.sendStarted();
        }
    }

    private void fireSendStopped() {
        for (TransferListener l : listeners) {
            l.sendStopped();
        }
    }

    /**
     * @return the address of the node
     */
    public byte[] getAddr() {
        return addr;
    }

    /**
     * Get the loco addresses in the specified range from the feedback system.
     * 
     * @param begin
     *            the start of Melderbits to be transfered
     * @param end
     *            the end of Melderbits to be transfered
     */
    public void getAddressState(int begin, int end) throws ProtocolException {
        sendNoWait(new FeedbackGetAddressRangeMessage(begin, end));
    }

    /**
     * Get the current 'quality' of the track sensoring.
     */
    public void getConfidence() throws ProtocolException {
        sendNoWait(new FeedbackGetConfidenceMessage());
    }

    /**
     * Get the feature with the specified number from the node.
     * 
     * @param number
     *            the feature number
     * @return the returned feature
     * @throws ProtocolException
     */
    public Feature getFeature(int number) throws ProtocolException {
        LOGGER.debug("get feature with number: {}", number);
        if (isBootloaderNode()) {
            LOGGER.warn("The current node is a bootloader node and does not support feature requests.");
            throw createNotSupportedByBootloaderNode("MSG_FEATURE_GET");
        }

        // if a node does not support the feature a feature not available response is received
        BidibMessage response =
            send(new FeatureGetMessage(number), true, FeatureResponse.TYPE, FeatureNotAvailableResponse.TYPE);

        LOGGER.debug("get feature with number '{}' returned: {}", number, response);
        if (response instanceof FeatureResponse) {
            Feature result = ((FeatureResponse) response).getFeature();
            return result;
        }
        else if (response instanceof FeatureNotAvailableResponse) {
            FeatureNotAvailableResponse result = (FeatureNotAvailableResponse) response;
            // TODO change this in version 2.0 to throw an exception
            //            throw new ProtocolException("The requested feature is not available, featureNumber: "
            //                + result.getFeatureNumber());
            LOGGER.warn("The requested feature is not available, featureNumber: {}", result.getFeatureNumber());
            return null;
        }
        throw createNoResponseAvailable("get feature");
    }

    /**
     * Request the number of features of the node. This call will reset the internal counter for the next
     * <code>getNextFeature()</code> request.
     * 
     * @return number of features on the node
     * @throws IOException
     * @throws ProtocolException
     * @throws InterruptedException
     */
    public Integer getFeatureCount() throws ProtocolException {
        if (isBootloaderNode()) {
            LOGGER.warn("The current node is a bootloader node and does not support feature requests.");
            throw createNotSupportedByBootloaderNode("MSG_FEATURE_GETALL");
        }

        BidibMessage response = send(new FeatureGetAllMessage(), true, FeatureCountResponse.TYPE);
        if (response instanceof FeatureCountResponse) {
            Integer result = ((FeatureCountResponse) response).getCount();
            return result;
        }

        throw createNoResponseAvailable("get feature count");
    }

    /**
     * Returns the next feature of the node. Call <code>getFeatureCount()</code> to reset the internal counter for the
     * feature index
     * 
     * @return a feature or null if no more features available
     * @throws ProtocolException
     */
    public Feature getNextFeature() throws ProtocolException {
        if (isBootloaderNode()) {
            LOGGER.warn("The current node is a bootloader node and does not support feature requests.");
            throw createNotSupportedByBootloaderNode("MSG_FEATURE_GETNEXT");
        }

        BidibMessage response =
            send(new FeatureGetNextMessage(), true, FeatureResponse.TYPE, FeatureNotAvailableResponse.TYPE);
        if (response instanceof FeatureResponse) {
            return ((FeatureResponse) response).getFeature();
        }
        else if (response instanceof FeatureNotAvailableResponse) {
            FeatureNotAvailableResponse result = (FeatureNotAvailableResponse) response;
            // TODO change this in version 2.0 to throw an exception
            //            throw new ProtocolException("The requested feature is not available, featureNumber: "
            //                + result.getFeatureNumber());
            LOGGER.warn("The requested feature is not available, featureNumber: {}", result.getFeatureNumber());
            return null;
        }
        throw createNoResponseAvailable("get next feature");
    }

    /**
     * Get the status of the track sensoring in the specified range.
     * 
     * @param begin
     *            the start of Melderbits to be transfered
     * @param end
     *            the end of Melderbits to be transfered
     */
    public void getFeedbackState(int begin, int end) throws ProtocolException {
        sendNoWait(new FeedbackGetRangeMessage(begin, end));
    }

    /**
     * Get the magic from the node.
     * @return the magic
     * @throws ProtocolException
     */
    public int getMagic() throws ProtocolException {
        LOGGER.debug("Get the magic.");

        BidibMessage response = send(new SysMagicMessage(), true, SysMagicResponse.TYPE);
        LOGGER.debug("getMagic, received response: {}", response);
        if (response instanceof SysMagicResponse) {
            int magic = ((SysMagicResponse) response).getMagic();
            setNodeMagic(magic);
            return magic;
        }
        throw createNoResponseAvailable("get magic");
    }

    /**
     * Create a ProtocolException if no response is available.
     * @param messageName the message name that is inserted in the exception message
     * @return the exception
     */
    private ProtocolException createNoResponseAvailable(String messageName) {
        ProtocolException ex = new ProtocolException("No response received from '" + messageName + "' message.");
        return ex;
    }

    private ProtocolException createNotSupportedByBootloaderNode(String messageName) {
        ProtocolException ex =
            new ProtocolException("The current node is a limited bootload node and does not support the '"
                + messageName + "' message.");
        return ex;
    }

    /**
     * Prepare the next receive message number in the communication with the communication partner. Every received
     * message should be incremented by 1 and on overflow start again with 1.
     * 
     * @param message
     *            the message
     * @return the next receive message number
     */
    public int getNextReceiveMsgNum(BidibMessage message) {

        try {
            nextReceiveMsgNumSemaphore.acquire();
            nextReceiveMsgNum++;
            if (nextReceiveMsgNum > 255) {
                nextReceiveMsgNum = 1;
            }
            if (message.getNum() == 0) {
                nextReceiveMsgNum = 0;
            }
        }
        catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        finally {
            nextReceiveMsgNumSemaphore.release();
        }
        return nextReceiveMsgNum;
    }

    private byte getNextSendMsgNum() {
        try {
            nextSendMsgNumSemaphore.acquire();
            nextSendMsgNum++;
            if (nextSendMsgNum > 255) {
                nextSendMsgNum = 1;
            }
        }
        catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        finally {
            nextSendMsgNumSemaphore.release();
        }
        return (byte) nextSendMsgNum;
    }

    /**
     * Get the next node from the system.
     * @return the node
     * @throws ProtocolException
     */
    public Node getNextNode() throws ProtocolException {
        BidibMessage response = send(new NodeTabGetNextMessage(), true, NodeTabResponse.TYPE);

        if (response instanceof NodeTabResponse) {
            // create a new node from the received data
            LOGGER.debug("Get next tab returned: {}, own addr: {}", response, addr);
            Node node = ((NodeTabResponse) response).getNode(addr);
            LOGGER.debug("Fetched node: {}", node);
            return node;
        }
        throw createNoResponseAvailable("get next node");
    }

    /**
     * Get the number of nodes from the system.
     * @return the number of nodes
     * @throws ProtocolException
     */
    public Integer getNodeCount() throws ProtocolException {
        LOGGER.debug("Get all registered nodes from system.");

        BidibMessage response = send(new NodeTabGetAllMessage(), true, NodeTabCountResponse.TYPE);

        if (response instanceof NodeTabCountResponse) {
            int totalNodes = ((NodeTabCountResponse) response).getCount();

            LOGGER.debug("Found total nodes: {}", totalNodes);
            return totalNodes;
        }
        throw createNoResponseAvailable("get node count");
    }

    /**
     * Get the protocol version of the node.
     * @return the protocol version
     * @throws ProtocolException
     */
    public ProtocolVersion getPVersion() throws ProtocolException {
        BidibMessage response = send(new SysGetPVersionMessage(), true, SysPVersionResponse.TYPE);
        if (response instanceof SysPVersionResponse) {
            return ((SysPVersionResponse) response).getVersion();
        }
        throw createNoResponseAvailable("get protocol version");
    }

    /**
     * Get the software version from the node.
     * @return the software version
     * @throws ProtocolException
     */
    public SoftwareVersion getSwVersion() throws ProtocolException {
        BidibMessage response = send(new SysGetSwVersionMessage(), true, SysSwVersionResponse.TYPE);
        if (response instanceof SysSwVersionResponse) {
            return ((SysSwVersionResponse) response).getVersion();
        }
        throw createNoResponseAvailable("get sw version");
    }

    /**
     * Get the unique id from the node.
     * @return the unique id
     * @throws ProtocolException
     */
    public byte[] getUniqueId() throws ProtocolException {
        BidibMessage response = send(new SysUniqueIdMessage(), true, SysUniqueIdResponse.TYPE);
        if (response instanceof SysUniqueIdResponse) {
            return ((SysUniqueIdResponse) response).getUniqueId();
        }
        throw createNoResponseAvailable("get unique id");
    }

    /**
     * Sets the identify state of the node. The result is received asynchronously.
     * @param state the identify state to set.
     * @throws ProtocolException
     */
    public void identify(IdentifyState state) throws ProtocolException {
        // do not wait for the response
        sendNoWait(new SysIdentifyMessage(state));
    }

    /**
     * Verify if the node supports FW updates.
     * @return true if the node has the FW update feature set, false otherwise
     * @throws ProtocolException
     */
    public boolean isUpdatable() throws ProtocolException {
        try {
            Feature feature = getFeature(BidibLibrary.FEATURE_FW_UPDATE_MODE);

            return feature != null && feature.getValue() == 1;
        }
        catch (ProtocolException ex) {
            LOGGER.warn("Check if node is updatable caused protocol exception.", ex);
        }
        return false;
    }

    /**
     * Send the ping message to the node.
     * @return the received message number
     * @throws ProtocolException
     */
    public int ping() throws ProtocolException {
        BidibMessage response = send(new SysPingMessage(), true, SysPongResponse.TYPE);
        if (response instanceof SysPongResponse) {
            SysPongResponse result = (SysPongResponse) response;
            return result.getNum();
        }
        throw createNoResponseAvailable("ping");
    }

    /**
     * Send the system reset message to the node.
     * @throws ProtocolException
     */
    public void reset() throws ProtocolException {
        sendNoWait(new SysResetMessage());
    }

    /**
     * Wait until the expected response is received or the timeout has expired.
     * @param expectedResponseTypes list of the expected response message types
     * @return the received response
     * @throws InterruptedException thrown if wait wait for response is interrupted
     */
    private BidibMessage receive(List<Integer> expectedResponseTypes) throws InterruptedException {
        BidibMessage result = null;
        LOGGER.debug("Receive message, expected responseTypes: {}", expectedResponseTypes);

        fireReceiveStarted();

        try {
            // TODO we should provide a timeout parameter here ...
            result = getMessage(expectedResponseTypes);
        }
        finally {
            if (result == null) {
                // no result was fetched
                resetNextSendMsgNum();
            }
        }
        fireReceiveStopped();
        return result;
    }

    private static int timeout = Bidib.DEFAULT_TIMEOUT;

    /**
     * Get a message from the receiveQueue for the defined timeout period.
     * 
     * @param responseTypes the optional list of responseType ids to wait for
     *
     * @return the received message or null if no message was received during the defined period.
     * @throws InterruptedException thrown if wait wait for response is interrupted
     */
    public BidibMessage getMessage(List<Integer> responseTypes) throws InterruptedException {
        LOGGER.debug("get message with responseType: {}", responseTypes);
        BidibMessage result = null;

        // wait a maximum of 3 seconds to recieve message
        long cancelReceiveTs = System.currentTimeMillis() + 3000;
        boolean leaveLoop = false;

        do {
            result = receiveQueue.poll(timeout, TimeUnit.MILLISECONDS);

            long now = System.currentTimeMillis();

            // handling of specific response type
            if (result instanceof SysErrorResponse) {
                LOGGER.warn("Received an error response: {}", result);
                leaveLoop = true;
            }
            else if (result != null && CollectionUtils.hasElements(responseTypes)) {
                BidibMessage response = null;
                for (Integer responseType : responseTypes) {
                    LOGGER.debug("Check if the response type is equal, responseType: {}, response.type: {}",
                        responseType.byteValue(), result.getType());

                    if (responseType == ByteUtils.getInt(result.getType())) {
                        LOGGER.debug("This is the expected response: {}", result);
                        response = result;
                        break;
                    }
                }

                if (response == null) {
                    LOGGER.debug("This is NOT the expected response: {}", result);
                    result = null;
                }

                LOGGER.debug("startReceive: {}, now: {}, result: {}", cancelReceiveTs, now, result);
                if (result != null || cancelReceiveTs < now) {
                    LOGGER.debug("leave loop.");
                    leaveLoop = true;
                }
            }
            // handling if no specific response type expected ...
            else if (result != null || (cancelReceiveTs < now)) {
                LOGGER.debug("leave loop, result: {}", result);
                leaveLoop = true;
            }
        }
        while (!leaveLoop);
        LOGGER.debug("Return received message: {}", result);
        return result;
    }

    private void resetNextSendMsgNum() {
        try {
            nextSendMsgNumSemaphore.acquire();

            // reset the counter. The next message will be sent with
            // SendMsgNum=0 which
            // will reset the counter on the receiver side
            nextSendMsgNum = -1;
        }
        catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        finally {
            nextSendMsgNumSemaphore.release();
        }
    }

    /**
     * Send a message without waiting for response.
     * @param message the message to send
     * @throws ProtocolException
     */
    protected void sendNoWait(BidibMessage message) throws ProtocolException {
        send(message, false, (Integer) null);
    }

    /**
     * Send a message and wait for the unspecific result.
     * @param message the message to send
     * @return the received result
     * @throws ProtocolException
     */
    protected BidibMessage send(BidibMessage message) throws ProtocolException {
        return send(message, true, (Integer) null);
    }

    /**
     * Send a message and wait for answer if expectAnswer is true.
     * 
     * @param message
     *            the message to send
     * @param expectAnswer
     *            answer is expected, this will cause to wait for an answer
     * @param expectedResponseTypes
     *            the list of expected type of response (optional)
     * @return the response from the node or null if no answer was expected
     * @throws ProtocolException
     *             thrown if no response was received if an answer was expected
     */
    protected synchronized BidibMessage send(
        BidibMessage message, boolean expectAnswer, Integer... expectedResponseTypes) throws ProtocolException {

        int num = getNextSendMsgNum();
        message.setSendMsgNum(num);
        logRecord.append("send " + message + " to " + this);

        BidibMessage result = null;
        byte type = message.getType();
        byte[] data = message.getData();
        byte[] bytes = new byte[1 + (addr != null ? addr.length + 1 : 1) + 2 + (data != null ? data.length : 0)];
        int index = 0;

        bytes[index++] = (byte) (bytes.length - 1);
        LOGGER.debug("Current node addr: {}", addr);

        if (addr != null && addr.length != 0 && addr[0] != 0) {
            for (int addrIndex = 0; addrIndex < addr.length; addrIndex++) {
                bytes[index++] = addr[addrIndex];
            }
        }
        else {
            LOGGER.debug("Current address is the root node.");
        }
        bytes[index++] = 0; // 'terminating zero' of the address

        bytes[index++] = (byte) num;
        bytes[index++] = type;
        if (data != null) {
            for (int dataIndex = 0; dataIndex < data.length; dataIndex++) {
                bytes[index++] = data[dataIndex];
            }
        }

        try {
            sendMessage(bytes, message);
        }
        catch (IOException ex) {
            LOGGER.warn("Send message failed.", ex);
            throw new ProtocolException("Send message failed: " + message);
        }

        if (expectAnswer) {
            // wait for the answer
            try {
                result =
                    receive((expectedResponseTypes != null && expectedResponseTypes[0] != null) ? Arrays
                        .asList(expectedResponseTypes) : null);
            }
            catch (InterruptedException ex) {
                LOGGER.warn("Waiting for response was interrupted", ex);
            }
            if (result == null) {
                LOGGER.warn("Get result failed for message:  {}", message);
                throw new ProtocolNoAnswerException("Got no answer to " + message);
            }
        }
        LOGGER.debug("Return the result message: {}", result);
        return result;
    }

    private void sendDelimiter() throws IOException {
        output.write((byte) BidibLibrary.BIDIB_PKT_MAGIC);
    }

    private void sendMessage(byte[] message, BidibMessage bidibMessage) throws IOException {
        LOGGER.debug("Send the message: {}", message);

        fireSendStarted();
        sendDelimiter();

        byte length = message[0];

        escape(length);

        int txCrc = CRC8.getCrcValue(length);

        for (int i = 1; i <= length; i++) {
            escape(message[i]);
            txCrc = CRC8.getCrcValue((message[i] ^ txCrc) & 0xFF);
        }
        escape((byte) txCrc);
        sendDelimiter();

        flush(bidibMessage);
        fireSendStopped();
    }

    private void flush(BidibMessage bidibMessage) throws IOException {
        byte[] bytes = output.toByteArray();

        StringBuilder sb = new StringBuilder("send ");
        sb.append(bidibMessage);
        sb.append(" : ");
        for (int index = 0; index < bytes.length; index++) {
            sb.append(String.format("%02x ", bytes[index]));
        }
        MSG_TX_LOGGER.info(sb.toString());

        // send the output to Bidib
        bidib.send(bytes);

        // this takes 'much' time, only format if debug level enabled
        if (LOGGER.isDebugEnabled()) {
            logRecord.append(" : ");
            for (int index = 0; index < bytes.length; index++) {
                logRecord.append(String.format("%02x ", bytes[index]));
            }
            LOGGER.debug("Flush logRecord: {}", logRecord);
        }
        logRecord.setLength(0);
        output.reset();
    }

    /**
     * Send an firmware update operation message to the node.
     * @param operation the operation identifier
     * @param data the data to send
     * @return the returned firmware update status
     * @throws ProtocolException
     */
    public FirmwareUpdateStat sendFirmwareUpdateOperation(FirmwareUpdateOperation operation, byte... data)
        throws ProtocolException {

        BidibMessage response = send(new FwUpdateOpMessage(operation, data), true, FwUpdateStatResponse.TYPE);
        if (response instanceof FwUpdateStatResponse) {
            return ((FwUpdateStatResponse) response).getUpdateStat();
        }
        throw createNoResponseAvailable("firmware update operation");
    }

    /**
     * Sets the feature value on the node.
     * @param number the feature number
     * @param value the feature value
     * @throws ProtocolException
     */
    public Feature setFeature(int number, int value) throws ProtocolException {
        BidibMessage response =
            send(new FeatureSetMessage(number, value), true, FeatureResponse.TYPE, FeatureNotAvailableResponse.TYPE);
        if (response instanceof FeatureResponse) {
            Feature result = ((FeatureResponse) response).getFeature();
            return result;
        }
        else if (response instanceof FeatureNotAvailableResponse) {
            FeatureNotAvailableResponse result = (FeatureNotAvailableResponse) response;
            throw new ProtocolException("The requested feature is not available, featureNumber: "
                + result.getFeatureNumber());
        }
        throw createNoResponseAvailable("feature set");
    }

    /**
     * Send the system disable message to the node.
     * @throws ProtocolException
     */
    public void sysDisable() throws ProtocolException {
        sendNoWait(new SysDisableMessage());
    }

    /**
     * Send the system enable message to the node.
     * @throws ProtocolException
     */
    public void sysEnable() throws ProtocolException {
        sendNoWait(new SysEnableMessage());
    }

    public String toString() {
        StringBuffer sb = new StringBuffer(getClass().getSimpleName()).append("@").append(hashCode());
        if (addr != null) {
            sb.append(Arrays.toString(addr));
        }
        sb.append(",magic=").append(nodeMagic);
        return sb.toString();
        //        return getClass().getSimpleName() + "@" + hashCode() + (addr != null ? Arrays.toString(addr) : "");
    }

    /**
     * Send the vendor disable message to the node.
     * @return the user config mode is off
     * @throws ProtocolException
     */
    public boolean vendorDisable() throws ProtocolException {
        BidibMessage result = send(new VendorDisableMessage(), true, VendorAckResponse.TYPE);
        if (result instanceof VendorAckResponse) {
            return ((VendorAckResponse) result).getReturnCode() == VendorAckResponse.USER_CONFIG_MODE_OFF;
        }
        return false;
    }

    /**
     * Send the vendor enable message to the node.
     * @return the user config mode is on
     * @throws ProtocolException
     */
    public boolean vendorEnable(byte[] uniqueId) throws ProtocolException {
        BidibMessage result = send(new VendorEnableMessage(uniqueId), true, VendorAckResponse.TYPE);
        if (result instanceof VendorAckResponse) {
            return ((VendorAckResponse) result).getReturnCode() == VendorAckResponse.USER_CONFIG_MODE_ON;
        }
        return false;
    }

    /**
     * Get the vendor data with the provided name from the node.
     * @param name the vendor specific name
     * @return the current vendor data values received from the node
     * @throws ProtocolException
     */
    public VendorData vendorGet(String name) throws ProtocolException {
        LOGGER.info("Get vendor message, name: {}", name);
        if (isBootloaderNode()) {
            LOGGER.warn("The current node is a bootloader node and does not support vendor data requests.");
            throw createNotSupportedByBootloaderNode("MSG_VENDOR_GET");
        }

        BidibMessage result = send(new VendorGetMessage(name), true, VendorResponse.TYPE);
        if (result instanceof VendorResponse) {
            return ((VendorResponse) result).getVendorData();
        }
        return null;
    }

    /**
     * Set the provided vendor data on the node.
     * @param name the vendor specific name
     * @param value the value to set
     * @return the current vendor data values received from the node
     * @throws ProtocolException
     */
    public VendorData vendorSet(String name, String value) throws ProtocolException {
        if (isBootloaderNode()) {
            LOGGER.warn("The current node is a bootloader node and does not support vendor data requests.");
            throw createNotSupportedByBootloaderNode("MSG_VENDOR_SET");
        }

        BidibMessage result = send(new VendorSetMessage(name, value), true, VendorResponse.TYPE);
        if (result instanceof VendorResponse) {
            return ((VendorResponse) result).getVendorData();
        }
        return null;
    }
}
