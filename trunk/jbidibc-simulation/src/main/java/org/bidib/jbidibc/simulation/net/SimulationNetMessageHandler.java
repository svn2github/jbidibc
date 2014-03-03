package org.bidib.jbidibc.simulation.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.LinkedList;
import java.util.List;

import org.bidib.jbidibc.core.BidibMessageProcessor;
import org.bidib.jbidibc.exception.ProtocolException;
import org.bidib.jbidibc.net.NetBidibPort;
import org.bidib.jbidibc.net.NetMessageHandler;
import org.bidib.jbidibc.utils.ByteUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimulationNetMessageHandler implements NetMessageHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SimulationNetMessageHandler.class);

    private BidibMessageProcessor messageReceiverDelegate;

    private int sessionKey;

    private int sequence;

    public final class KnownBidibHost {
        private final InetAddress address;

        private final int portNumber;

        public KnownBidibHost(final InetAddress address, final int portNumber) {
            this.address = address;
            this.portNumber = portNumber;
        }

        /**
         * @return the address
         */
        public InetAddress getAddress() {
            return address;
        }

        /**
         * @return the portNumber
         */
        public int getPortNumber() {
            return portNumber;
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof KnownBidibHost) {
                KnownBidibHost bidibHost = (KnownBidibHost) other;
                if (bidibHost.getAddress().equals(address) && bidibHost.getPortNumber() == portNumber) {
                    return true;
                }
            }
            return false;
        }
    }

    private List<KnownBidibHost> knownBidibHosts = new LinkedList<KnownBidibHost>();

    public SimulationNetMessageHandler(BidibMessageProcessor messageReceiverDelegate) {
        this.messageReceiverDelegate = messageReceiverDelegate;
    }

    @Override
    public void receive(final DatagramPacket packet) {
        // TODO a datagramm packet was received ... process the envelope and extract the message
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Received a packet from address: {}, port: {}, data: {}", packet.getAddress(),
                packet.getPort(), ByteUtils.bytesToHex(packet.getData(), packet.getLength()));
        }

        KnownBidibHost current = new KnownBidibHost(packet.getAddress(), packet.getPort());
        if (!knownBidibHosts.contains(current)) {
            LOGGER.info("Adding new known Bidib host: {}", current);
            knownBidibHosts.add(current);
        }

        // TODO for the first magic response we need special processing because we need to keep the session key

        // remove the UDP paket wrapper data and forward to the MessageReceiver
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(packet.getData(), 4, packet.getLength() - 4);

        LOGGER.info("Forward received message to messageReceiverDelegate: {}", messageReceiverDelegate);
        try {
            messageReceiverDelegate.processMessages(output);
        }
        catch (ProtocolException ex) {
            LOGGER.warn("Process messages failed.", ex);
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void send(NetBidibPort port, byte[] bytes) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Send message to port: {}, message: {}", port, ByteUtils.bytesToHex(bytes));
        }

        // TODO Auto-generated method stub
        if (port != null) {

            try {
                // TODO add the UDP packet wrapper ...
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bos.write(ByteUtils.getHighByte(sessionKey));
                bos.write(ByteUtils.getLowByte(sessionKey));
                bos.write(ByteUtils.getHighByte(sequence));
                bos.write(ByteUtils.getLowByte(sequence));
                bos.write(bytes);

                for (KnownBidibHost host : knownBidibHosts) {
                    LOGGER.info("Send message to address: {}, port: {}", host.getAddress(), host.getPortNumber());
                    port.send(bos.toByteArray(), host.getAddress(), host.getPortNumber());
                }

                // increment the sequence counter after sending the message sucessfully
                prepareNextSequence();
            }
            catch (IOException ex) {
                LOGGER.warn("Send message to port failed.", ex);
                throw new RuntimeException("Send message to datagram socket failed.", ex);
            }
        }
        else {
            LOGGER.warn("Send not possible, the port is closed.");
        }
    }

    private void prepareNextSequence() {
        sequence++;
        if (sequence > 65535) {
            sequence = 0;
        }
    }

}
