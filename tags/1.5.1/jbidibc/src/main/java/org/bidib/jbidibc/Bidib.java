package org.bidib.jbidibc;

import gnu.io.CommPortIdentifier;
import gnu.io.NoSuchPortException;
import gnu.io.PortInUseException;
import gnu.io.SerialPort;
import gnu.io.SerialPortEvent;
import gnu.io.SerialPortEventListener;
import gnu.io.UnsupportedCommOperationException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TooManyListenersException;
import java.util.concurrent.Semaphore;

import org.bidib.jbidibc.exception.PortNotFoundException;
import org.bidib.jbidibc.exception.PortNotOpenedException;
import org.bidib.jbidibc.exception.ProtocolException;
import org.bidib.jbidibc.node.AccessoryNode;
import org.bidib.jbidibc.node.BidibNode;
import org.bidib.jbidibc.node.BoosterNode;
import org.bidib.jbidibc.node.CommandStationNode;
import org.bidib.jbidibc.node.NodeFactory;
import org.bidib.jbidibc.node.RootNode;
import org.bidib.jbidibc.utils.LibraryPathManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the default bidib implementation.
 * It creates and initializes the MessageReceiver and the NodeFactory that is used in the system.
 *
 */
public final class Bidib implements BidibInterface {

    private static final Logger LOGGER = LoggerFactory.getLogger(Bidib.class);

    public static final int DEFAULT_TIMEOUT = 1500;

    private NodeFactory nodeFactory;

    private SerialPort port;

    private Semaphore portSemaphore = new Semaphore(1);

    private Semaphore sendSemaphore = new Semaphore(1);

    private String logFile;

    private boolean librariesLoaded;

    private static Bidib INSTANCE;

    private MessageReceiver messageReceiver;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                try {
                    LOGGER.debug("Close the communication ports and perform cleanup.");
                    getInstance().close();
                }
                catch (IOException e) {
                }
            }
        });
    }

    private Bidib() {
    }

    /**
     * Initialize the instance. This must only be called from this class
     */
    protected void initialize() {
        LOGGER.info("Initialize Bidib.");
        nodeFactory = new NodeFactory();
        nodeFactory.setBidib(this);
        // create the message receiver
        messageReceiver = new MessageReceiver(nodeFactory);
        messageReceiver.setBidib(this);
        // set the receive queue
    }

    public static synchronized BidibInterface getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new Bidib();
            INSTANCE.initialize();
        }
        return INSTANCE;
    }

    @Override
    public void close() throws IOException {
        if (port != null) {
            LOGGER.debug("Close the port.");
            long start = System.currentTimeMillis();

            // no longer process received messages
            messageReceiver.disable();

            // this makes the close operation faster ...
            try {
                port.removeEventListener();
                port.enableReceiveTimeout(200);
            }
            catch (UnsupportedCommOperationException e) {
                // ignore
            }
            port.close();

            long end = System.currentTimeMillis();
            LOGGER.debug("Closed the port. duration: {}", end - start);

            port = null;

            if (nodeFactory != null) {
                // remove all stored nodes from the node factory
                nodeFactory.reset();
            }
        }
    }

    /**
     * @return returns the list of serial port identifiers that are available in the system.
     */
    public List<String> getPortIdentifiers() {
        List<String> portIdentifiers = new ArrayList<String>();

        // make sure the libraries are loaded
        loadLibraries();

        // get the comm port identifiers
        Enumeration<?> e = CommPortIdentifier.getPortIdentifiers();
        while (e.hasMoreElements()) {
            CommPortIdentifier id = (CommPortIdentifier) e.nextElement();

            if (id.getPortType() == CommPortIdentifier.PORT_SERIAL) {
                portIdentifiers.add(id.getName());
            }
        }
        return portIdentifiers;
    }

    public AccessoryNode getAccessoryNode(Node node) {
        return nodeFactory.getAccessoryNode(node);
    }

    public BoosterNode getBoosterNode(Node node) {
        return nodeFactory.getBoosterNode(node);
    }

    public CommandStationNode getCommandStationNode(Node node) {
        return nodeFactory.getCommandStationNode(node);
    }

    public MessageReceiver getMessageReceiver() {
        return messageReceiver;
    }

    /**
     * returns the cached node or creates a new instance
     * 
     * @param node
     *            the node
     * @return the BidibNode instance
     */
    @Override
    public BidibNode getNode(Node node) {
        return nodeFactory.getNode(node);
    }

    @Override
    public RootNode getRootNode() {
        return nodeFactory.getRootNode();
    }

    private void clearInputStream(SerialPort serialPort) {

        // get and clear stream

        try {
            InputStream serialStream = serialPort.getInputStream();
            // purge contents, if any
            int count = serialStream.available();
            LOGGER.debug("input stream shows {} bytes available", count);
            while (count > 0) {
                serialStream.skip(count);
                count = serialStream.available();
            }
            LOGGER.debug("input stream shows {} bytes available after purge.", count);
        }
        catch (Exception e) {
            LOGGER.warn("Clear input stream failed.", e);
        }

    }

    private SerialPort internalOpen(CommPortIdentifier commPort, int baudRate) throws PortInUseException,
        UnsupportedCommOperationException, TooManyListenersException {

        SerialPort serialPort = (SerialPort) commPort.open(Bidib.class.getName(), 2000);

        // set RTS high, DTR high - done early, so flow control can be configured after
        try {
            serialPort.setRTS(true); // not connected in some serial ports and adapters
            serialPort.setDTR(true); // pin 1 in DIN8; on main connector, this is DTR
        }
        catch (Exception e) {
            LOGGER.warn("Set RTS and DTR true failed.", e);
        }

        serialPort.setFlowControlMode(SerialPort.FLOWCONTROL_RTSCTS_IN | SerialPort.FLOWCONTROL_RTSCTS_OUT);
        serialPort.setSerialPortParams(baudRate, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);

        serialPort.enableReceiveThreshold(1);
        serialPort.enableReceiveTimeout(DEFAULT_TIMEOUT);

        clearInputStream(serialPort);

        // enable the message receiver before the event listener is added
        messageReceiver.enable();
        serialPort.addEventListener(new SerialPortEventListener() {
            {
                if (logFile != null) {
                    LOGGER.warn("Logfile is set: {}", logFile);
                    try {
                        new LogFileAnalyzer(new File(logFile), messageReceiver);
                    }
                    catch (IOException e) {
                        LOGGER.warn("Create LogFileAnalyzer failed.", e);
                    }
                }
            }

            @Override
            public void serialEvent(SerialPortEvent event) {
                // this callback is called every time data is available
                LOGGER.trace("serialEvent received: {}", event);
                switch (event.getEventType()) {
                    case SerialPortEvent.DATA_AVAILABLE:
                        try {
                            messageReceiver.receive(port);
                        }
                        catch (Exception ex) {
                            LOGGER.error("Message receiver has terminated with an exception!", ex);
                        }
                        break;
                    case SerialPortEvent.OUTPUT_BUFFER_EMPTY:
                        LOGGER.trace("The output buffer is empty.");
                        break;
                    default:
                        LOGGER.warn("SerialPortEvent was triggered, type: {}, old value: {}, new value: {}",
                            new Object[] { event.getEventType(), event.getOldValue(), event.getNewValue() });
                        break;
                }
            }
        });
        serialPort.notifyOnDataAvailable(true);

        return serialPort;
    }

    private void loadLibraries() {
        if (!librariesLoaded) {
            new LibraryPathManipulator().manipulateLibraryPath(null);
            librariesLoaded = true;
        }
    }

    public void open(String portName) throws PortNotFoundException, PortNotOpenedException {
        if (port == null) {
            if (portName == null || portName.trim().isEmpty()) {
                throw new PortNotFoundException("");
            }
            loadLibraries();
            LOGGER.debug("Open port with name: {}", portName);

            File file = new File(portName);

            if (file.exists()) {
                try {
                    portName = file.getCanonicalPath();
                    LOGGER.info("Changed port name to: {}", portName);
                }
                catch (IOException ex) {
                    throw new PortNotFoundException(portName);
                }
            }

            CommPortIdentifier commPort = null;
            try {
                commPort = CommPortIdentifier.getPortIdentifier(portName);
            }
            catch (NoSuchPortException ex) {
                throw new PortNotFoundException(portName);
            }

            try {
                portSemaphore.acquire();

                try {
                    // 115200 Baud
                    close();
                    port = internalOpen(commPort, 115200);
                    sendMagic();
                }
                catch (Exception e2) {
                    try {
                        // 19200 Baud
                        close();
                        port = internalOpen(commPort, 19200);
                        sendMagic();
                    }
                    catch (Exception e3) {
                        try {
                            close();
                        }
                        catch (Exception e4) {
                            // ignore
                        }
                    }
                }
            }
            catch (InterruptedException ex) {
                LOGGER.warn("Wait for portSemaphore was interrupted.", ex);
                throw new PortNotOpenedException("Open port failed: " + portName);
            }
            finally {
                portSemaphore.release();
            }
        }
        else {
            LOGGER.warn("Port is already opened.");
        }
    }

    public boolean isOpened() {
        boolean isOpened = false;
        try {
            portSemaphore.acquire();

            LOGGER.debug("Check if port is opened: {}", port);
            isOpened = (port != null && port.getOutputStream() != null);
        }
        catch (InterruptedException ex) {
            LOGGER.warn("Wait for portSemaphore was interrupted.", ex);
        }
        catch (IOException ex) {
            LOGGER.warn("OutputStream is not available.", ex);
        }
        finally {
            portSemaphore.release();
        }
        return isOpened;
    }

    /**
     * Send the bytes to the outputstream.
     * @param bytes the bytes to send
     */
    @Override
    public void send(final byte[] bytes) {
        if (port != null) {
            try {
                sendSemaphore.acquire();

                OutputStream output = port.getOutputStream();

                port.setRTS(true);
                output.write(bytes);
                output.flush();
                port.setRTS(false);
            }
            catch (Exception e) {
                throw new RuntimeException("Send message to output stream failed.", e);
            }
            finally {
                sendSemaphore.release();
            }
        }
    }

    /**
     * Get the magic from the root node
     * @return the magic provided by the root node
     * @throws ProtocolException
     */
    private int sendMagic() throws ProtocolException {
        BidibNode rootNode = getRootNode();

        // Ignore the first exception ...
        try {
            rootNode.getMagic();
        }
        catch (Exception e) {
        }
        int magic = rootNode.getMagic();
        LOGGER.debug("The node returned magic: {}", magic);
        return magic;
    }

    public void setLogFile(String logFile) {
        this.logFile = logFile;
    }

    /**
     * Set the recieve timeout for the port.
     * @param timeout the receive timeout to set
     */
    @Override
    public void setReceiveTimeout(int timeout) {
        if (port != null) {
            try {
                port.enableReceiveTimeout(timeout);
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void setIgnoreWaitTimeout(boolean ignoreWaitTimeout) {
        if (nodeFactory != null) {
            nodeFactory.setIgnoreWaitTimeout(ignoreWaitTimeout);
        }
        else {
            LOGGER.warn("The node factory is not available, set the ignoreWaitTimeout is discarded.");
        }
    }
}
