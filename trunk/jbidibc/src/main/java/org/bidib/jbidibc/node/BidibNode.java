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
import org.bidib.jbidibc.message.NodeChangedAckMessage;
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

    public static final int BIDIB_MAGIC_UNKNOWN = -1;

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

    protected boolean ignoreWaitTimeout;

    /**
     * Create a new BidibNode that represents a connected node (slave) on the BiDiB bus.
     * 
     * @param addr
     *            the address
     * @param messageReceiver
     *            the message receiver
     * @param ignoreWaitTimeout ignore the wait timeout
     */
    protected BidibNode(byte[] addr, MessageReceiver messageReceiver, boolean ignoreWaitTimeout) {
        this.addr = addr != null ? addr.clone() : null;
        this.messageReceiver = messageReceiver;
        this.ignoreWaitTimeout = ignoreWaitTimeout;
        LOGGER.debug("Create new BidibNode with address: {}, ignoreWaitTimeout: {}", addr, ignoreWaitTimeout);
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
        if (nodeMagic != null) {
            return (BidibLibrary.BIDIB_BOOT_MAGIC == nodeMagic);
        }
        LOGGER.warn("No magic available for current node. Assume this is a bootloader node!");
        return true;
    }

    /**
     * Send the node changed acknowledge message.
     * @param versionNumber the version number of the node table
     * @throws ProtocolException
     */
    public void acknowledgeNodeChanged(int versionNumber) throws ProtocolException {
        sendNoWait(new NodeChangedAckMessage(versionNumber));
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

        if (ignoreWaitTimeout) {
            LOGGER.warn("No response received but ignoreWaitTimeout ist set!");
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
        if (ignoreWaitTimeout) {
            LOGGER.warn("No response received but ignoreWaitTimeout ist set!");
            return Integer.valueOf(0);
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

        if (ignoreWaitTimeout) {
            LOGGER.warn("No response received but ignoreWaitTimeout ist set!");
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

        if (ignoreWaitTimeout) {
            LOGGER.warn("No response received but ignoreWaitTimeout ist set! Return BIDIB_MAGIC_UNKNOWN!");
            setNodeMagic(BIDIB_MAGIC_UNKNOWN);
            return BIDIB_MAGIC_UNKNOWN;
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

        if (ignoreWaitTimeout) {
            LOGGER.warn("No response received but ignoreWaitTimeout ist set!");
            return null;
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

        if (ignoreWaitTimeout) {
            LOGGER.warn("No response received but ignoreWaitTimeout ist set!");
            return Integer.valueOf(0);
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

        if (ignoreWaitTimeout) {
            LOGGER.warn("No response received but ignoreWaitTimeout ist set!");
            return null;
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

        if (ignoreWaitTimeout) {
            LOGGER.warn("No response received but ignoreWaitTimeout ist set!");
            return null;
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

        if (ignoreWaitTimeout) {
            LOGGER.warn("No response received but ignoreWaitTimeout ist set!");
            return null;
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

        if (ignoreWaitTimeout) {
            LOGGER.warn("No response received but ignoreWaitTimeout ist set!");
            return Integer.valueOf(0);
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
        long cancelReceiveTs = System.currentTimeMillis() + Bidib.DEFAULT_TIMEOUT;
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

        prepareAndSendMessage(message);

        BidibMessage response = null;
        if (expectAnswer) {
            // wait for the answer
            try {
                response =
                    receive((expectedResponseTypes != null && expectedResponseTypes[0] != null) ? Arrays
                        .asList(expectedResponseTypes) : null);
            }
            catch (InterruptedException ex) {
                LOGGER.warn("Waiting for response was interrupted", ex);
            }
            if (response == null && !ignoreWaitTimeout) {
                LOGGER.warn("Receive message timed out. Get response failed for message:  {}", message);
                throw new ProtocolNoAnswerException("Got no answer to " + message);
            }
        }
        LOGGER.debug("Return the response message: {}", response);
        return response;
    }

    private void prepareAndSendMessage(BidibMessage message) throws ProtocolException {

        try {
            EncodedMessage encodedMessage = encodeMessage(message);
            // byte[] bytes = encodeMessageWithStream(message);

            sendMessage(encodedMessage, message);
        }
        catch (IOException ex) {
            LOGGER.warn("Send message failed.", ex);
            throw new ProtocolException("Send message failed: " + message);
        }
    }

    protected EncodedMessage encodeMessage(BidibMessage message) {
        int num = getNextSendMsgNum();
        message.setSendMsgNum(num);
        logRecord.append("send ").append(message).append(" to ").append(this);

        byte type = message.getType();
        byte[] data = message.getData();
        byte[] bytes = null;
        int index = 0;

        LOGGER.trace("Current node addr: {}", addr);

        if (addr != null && addr.length != 0 && addr[0] != 0) {
            bytes = new byte[1 + (addr.length + 1) + 2 + (data != null ? data.length : 0)];
            bytes[index++] = (byte) (bytes.length - 1);
            for (int addrIndex = 0; addrIndex < addr.length; addrIndex++) {
                bytes[index++] = addr[addrIndex];
            }
        }
        else {
            LOGGER.trace("Current address is the root node.");
            bytes = new byte[1 + (addr.length /*len of root node*/) + 2 + (data != null ? data.length : 0)];
            bytes[index++] = (byte) (bytes.length - 1);
        }
        bytes[index++] = 0; // 'terminating zero' of the address

        bytes[index++] = (byte) num;
        bytes[index++] = type;
        if (data != null) {
            //            LOGGER.debug("Add data: {}", ByteUtils.bytesToHex(data));
            for (int dataIndex = 0; dataIndex < data.length; dataIndex++) {
                bytes[index++] = data[dataIndex];
            }
        }
        EncodedMessage encodedMessage = new EncodedMessage(bytes);
        return encodedMessage;
    }

    //    protected EncodedMessage encodeMessageWithStream(BidibMessage message) throws IOException {
    //        ByteArrayOutputStream stream = new ByteArrayOutputStream();
    //
    //        int num = getNextSendMsgNum();
    //        message.setSendMsgNum(num);
    //        logRecord.append("send ").append(message).append(" to ").append(this);
    //
    //        byte type = message.getType();
    //        byte[] data = message.getData();
    //        LOGGER.trace("Current node addr: {}", addr);
    //
    //        if (addr != null && addr.length != 0 && addr[0] != 0) {
    //            int len = 1 + (addr.length + 1) + 2 + (data != null ? data.length : 0);
    //            stream.write(ByteUtils.getLowByte(len - 1));
    //            stream.write(addr);
    //        }
    //        else {
    //            LOGGER.trace("Current address is the root node.");
    //            int len = 1 + (addr.length /*len of root node*/) + 2 + (data != null ? data.length : 0);
    //            stream.write(ByteUtils.getLowByte(len - 1));
    //        }
    //        stream.write(0); // 'terminating zero' of the address
    //
    //        stream.write(ByteUtils.getLowByte(num));
    //        stream.write(type);
    //        if (data != null) {
    //            //            LOGGER.debug("Add data: {}", ByteUtils.bytesToHex(data));
    //            stream.write(data);
    //        }
    //        EncodedMessage encodedMessage = new EncodedMessage(stream.toByteArray());
    //        return encodedMessage;
    //    }

    public static class EncodedMessage {
        private byte[] message;

        public EncodedMessage(byte[] message) {
            this.message = message;
        }

        public byte[] getMessage() {
            return message;
        }
    }

    private void prepareAndSendMessages(List<BidibMessage> messages) throws ProtocolException {

        try {
            List<EncodedMessage> encodedMessages = new LinkedList<EncodedMessage>();
            for (BidibMessage message : messages) {
                EncodedMessage encodedMessage = encodeMessage(message);
                // byte[] bytes = encodeMessageWithStream(message);
                encodedMessages.add(encodedMessage);
            }
            sendMessages(encodedMessages, messages);
        }
        catch (IOException ex) {
            LOGGER.warn("Send messages failed.", ex);
            throw new ProtocolException("Send messages failed: " + messages);
        }
    }

    private void sendMessages(List<EncodedMessage> encodedMessages, List<BidibMessage> bidibMessages)
        throws IOException {

        fireSendStarted();

        // send the leading delimiter
        sendDelimiter();

        for (EncodedMessage encodedMessage : encodedMessages) {
            byte[] message = encodedMessage.getMessage();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Send the message: {}", ByteUtils.bytesToHex(message));
            }

            byte length = message[0];

            escape(length);

            int txCrc = CRC8.getCrcValue(length);

            for (int i = 1; i <= length; i++) {
                escape(message[i]);
                txCrc = CRC8.getCrcValue((message[i] ^ txCrc) & 0xFF);
            }
            escape((byte) txCrc);
            sendDelimiter();
        }
        // flush the messages in the buffer
        flush(bidibMessages);

        fireSendStopped();
    }

    /**
     * Send a bulk of messages with a window size of 4 to the node.
     * @param windowSize the window size
     * @param messages the messages
     * @param expectAnswer wait for expected answer
     * @param expectedResponseTypes the expected response types
     * @return the list of responses
     * @throws ProtocolException
     */
    protected synchronized List<BidibMessage> sendBulk(
        int windowSize, List<BidibMessage> messages, boolean expectAnswer, Integer... expectedResponseTypes)
        throws ProtocolException {

        List<BidibMessage> responses = null;

        int numMessages = messages.size();
        LOGGER.debug("Send bulk messages total: {}", numMessages);

        // send messages with a window-size
        int fromIndex = 0;
        int toIndex = Math.min(numMessages, windowSize);
        int receivedMessages = 0;

        while (fromIndex < numMessages) {
            LOGGER.trace("Send bulk messages fromIndex: {}, toIndex: {}", fromIndex, toIndex);

            prepareAndSendMessages(messages.subList(fromIndex, toIndex));

            // send the next message if one is received
            fromIndex = toIndex;
            toIndex++;
            LOGGER.trace("Prepeared new fromIndex: {}, toIndex: {}", fromIndex, toIndex);

            if (expectAnswer) {
                BidibMessage response = null;
                // wait for the answer
                try {
                    if (responses == null) {
                        responses = new LinkedList<BidibMessage>();
                    }
                    while (receivedMessages < numMessages) {
                        LOGGER.trace("Receive response, receivedMessages: {}, numMessages: {}", receivedMessages,
                            numMessages);
                        response =
                            receive((expectedResponseTypes != null && expectedResponseTypes[0] != null) ? Arrays
                                .asList(expectedResponseTypes) : null);

                        LOGGER.trace("Received message response: {}", response);
                        if (response != null) {
                            responses.add(response);
                        }
                        receivedMessages++;

                        if (fromIndex < numMessages) {
                            LOGGER.trace("Not all messages sent yet. Leave receive loop to send next message.");
                            break;
                        }
                    }
                }
                catch (InterruptedException ex) {
                    LOGGER.warn("Waiting for response was interrupted", ex);
                }
                if (responses.size() == 0 && !ignoreWaitTimeout) {
                    LOGGER.warn("Receive message timed out. Get response failed for messages:  {}", messages);
                    throw new ProtocolNoAnswerException("Got no answer to " + messages);
                }
            }
        }
        LOGGER.debug("Return the response messages: {}", responses);

        if (responses == null) {
            LOGGER.warn("No responses received! Expected: {}", numMessages);
        }
        else if (responses.size() < numMessages) {
            LOGGER.warn("Received not all responses! Expected: {}, actual: {}", numMessages, responses.size());
        }
        return responses;
    }

    private void sendDelimiter() {
        output.write((byte) BidibLibrary.BIDIB_PKT_MAGIC);
    }

    private void escape(byte c) throws IOException {
        if ((c == (byte) BidibLibrary.BIDIB_PKT_MAGIC) || (c == (byte) BidibLibrary.BIDIB_PKT_ESCAPE)) {
            output.write((byte) BidibLibrary.BIDIB_PKT_ESCAPE);
            c = (byte) (c ^ 0x20);
        }
        output.write(c);
    }

    /**
     * Put the contents of the message into the "output" stream and call the flush method that sends the 
     * content of the "output" stream to bidib.
     * @param message the message contents
     * @param bidibMessage the bidib message instance
     * @throws IOException
     */
    private void sendMessage(EncodedMessage encodedMessage, BidibMessage bidibMessage) throws IOException {
        byte[] message = encodedMessage.getMessage();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Send the message: {}", ByteUtils.bytesToHex(message));
        }

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

        // log the bidib message and the content of the "output" stream
        StringBuilder sb = new StringBuilder("send ");
        sb.append(bidibMessage);
        sb.append(" : ");
        sb.append(ByteUtils.bytesToHex(bytes));
        MSG_TX_LOGGER.info(sb.toString());

        // send the output to Bidib
        bidib.send(bytes);

        // this takes 'much' time, only format if debug level enabled
        if (LOGGER.isDebugEnabled()) {
            logRecord.append(" : ");
            logRecord.append(ByteUtils.bytesToHex(bytes));
            LOGGER.debug("Flush logRecord: {}", logRecord);
        }
        logRecord.setLength(0);
        output.reset();
    }

    private void flush(List<BidibMessage> bidibMessages) throws IOException {
        byte[] bytes = output.toByteArray();

        // log the bidib message and the content of the "output" stream
        StringBuilder sb = new StringBuilder("send ");
        sb.append(bidibMessages);
        sb.append(" : ");
        sb.append(ByteUtils.bytesToHex(bytes));
        MSG_TX_LOGGER.info(sb.toString());

        // send the output to Bidib
        bidib.send(bytes);

        // this takes 'much' time, only format if debug level enabled
        if (LOGGER.isDebugEnabled()) {
            logRecord.append(" : ");
            logRecord.append(ByteUtils.bytesToHex(bytes));
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

        if (ignoreWaitTimeout) {
            LOGGER.warn("No response received but ignoreWaitTimeout ist set!");
            return null;
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

        if (ignoreWaitTimeout) {
            LOGGER.warn("No response received but ignoreWaitTimeout ist set!");
            return null;
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

    private static final int BULK_WINDOW_SIZE = 4;

    /**
     * Get the vendor data with the provided name from the node.
     * @param name the vendor specific name
     * @return the current vendor data values received from the node
     * @throws ProtocolException
     */
    public List<VendorData> vendorGetBulk(List<String> cvNumbers) throws ProtocolException {
        LOGGER.info("Get vendor message, cvNumbers: {}", cvNumbers);
        if (isBootloaderNode()) {
            LOGGER.warn("The current node is a bootloader node and does not support vendor data requests.");
            throw createNotSupportedByBootloaderNode("MSG_VENDOR_GET");
        }

        // prepare all messages
        List<BidibMessage> messages = new LinkedList<BidibMessage>();
        for (String name : cvNumbers) {
            LOGGER.debug("Add new CV name: {}", name);
            messages.add(new VendorGetMessage(name));
        }

        List<BidibMessage> results = sendBulk(BULK_WINDOW_SIZE, messages, true, VendorResponse.TYPE);
        if (results != null) {
            List<VendorData> vendorDatas = new LinkedList<VendorData>();
            for (BidibMessage result : results) {
                if (result instanceof VendorResponse) {
                    VendorData vendorData = ((VendorResponse) result).getVendorData();
                    LOGGER.debug("Received vendor data: {}", vendorData);
                    vendorDatas.add(vendorData);
                }
            }
            return vendorDatas;
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
