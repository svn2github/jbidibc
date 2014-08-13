package org.bidib.jbidibc.message;

import org.bidib.jbidibc.AddressData;
import org.bidib.jbidibc.enumeration.AddressTypeEnum;
import org.bidib.jbidibc.enumeration.CommandStationPom;
import org.bidib.jbidibc.enumeration.PomOperation;
import org.bidib.jbidibc.exception.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CommandStationPomMessageTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandStationPomMessageTest.class);

    @Test
    public void prepareCommandStationPomMessage() {
        AddressData locoAddress = new AddressData(3, AddressTypeEnum.LOCOMOTIVE_FORWARD);
        CommandStationPom opCode = CommandStationPom.RD_BYTE;
        int cvNumber = 7;
        byte[] data = { 0 };

        CommandStationPomMessage message = new CommandStationPomMessage(locoAddress, opCode, cvNumber, data);

        LOGGER.debug("Created message: {}", message);
        Assert.assertNotNull(message);

        Assert.assertEquals(message.getData()[0], (byte) 0x03); // ADDR_L
        Assert.assertEquals(message.getData()[1], (byte) 0x00); // ADDR_H

        Assert.assertEquals(message.getData()[2], (byte) 0x00); // ADDR_XL
        Assert.assertEquals(message.getData()[3], (byte) 0x00); // ADDR_XH

        Assert.assertEquals(message.getData()[4], (byte) 0x00); // MID

        Assert.assertEquals(message.getData()[5], opCode.getType()); // OPCODE

        Assert.assertEquals(message.getData()[6], (byte) cvNumber - 1); // CV_L
        Assert.assertEquals(message.getData()[7], (byte) 0x00); // CV_H
        Assert.assertEquals(message.getData()[8], (byte) 0x00); // CV_X

        Assert.assertEquals(message.getData()[9], (byte) 0x00); // DATA
    }

    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void prepareInvalidCommandStationPomMessage1() {
        AddressData locoAddress = new AddressData(3, AddressTypeEnum.LOCOMOTIVE_FORWARD);
        CommandStationPom opCode = CommandStationPom.RD_BYTE;
        int cvNumber = 0;
        byte[] data = { 0 };

        new CommandStationPomMessage(locoAddress, opCode, cvNumber, data);
    }

    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void prepareInvalidCommandStationPomMessage2() {
        AddressData locoAddress = new AddressData(3, AddressTypeEnum.LOCOMOTIVE_FORWARD);
        CommandStationPom opCode = CommandStationPom.RD_BYTE;
        int cvNumber = 1025;
        byte[] data = { 0 };

        new CommandStationPomMessage(locoAddress, opCode, cvNumber, data);
    }

    // OUT MU : MSG_CS_POM 0C 00 60 67 03 00 00 00 00 01 04 00 00
    @Test
    public void prepareCommandStationPomMessage2() throws ProtocolException {
        byte[] message = new byte[] { 0x0C, 0x00, 0x60, 0x67, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01, 0x04, 0x00, 0x00 };
        CommandStationPomMessage pomMessage = new CommandStationPomMessage(message);
        Assert.assertNotNull(pomMessage);

        AddressData locoAddress = new AddressData(3, AddressTypeEnum.LOCOMOTIVE_FORWARD);

        LOGGER.info("Prepare pomMessage: {}", pomMessage);
        Assert.assertEquals(pomMessage.getDecoderAddress(), locoAddress);

        Assert.assertEquals(pomMessage.getAddressX(), 0x00);
        Assert.assertEquals(pomMessage.getMid(), 0x00);
        Assert.assertEquals(pomMessage.getOpCode(), 0x01);
    }

    // FE 0D 00 1B 67 03 00 00 00 00 01 02 00 00 00 14 FE
    @Test
    public void prepareCommandStationPomMessage3() throws ProtocolException {
        byte[] message =
            new byte[] { 0x0D, 0x00, 0x1B, 0x67, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x00, 0x00, 0x00 };
        CommandStationPomMessage pomMessage = new CommandStationPomMessage(message);
        Assert.assertNotNull(pomMessage);

        AddressData locoAddress = new AddressData(3, AddressTypeEnum.LOCOMOTIVE_FORWARD);

        LOGGER.info("Prepare pomMessage: {}", pomMessage);
        Assert.assertEquals(pomMessage.getDecoderAddress(), locoAddress);

        Assert.assertEquals(pomMessage.getAddressX(), 0x00);
        Assert.assertEquals(pomMessage.getMid(), 0x00);
        Assert.assertEquals(pomMessage.getOpCode(), (int) PomOperation.RD_BYTE.getType());
        Assert.assertEquals(pomMessage.getCvNumber(), 3);
        Assert.assertEquals(pomMessage.getCvValue(), 0);
    }

    // FE 0D 00 1A 67 03 00 00 00 00 03 02 00 00 03 1D FE
    @Test
    public void prepareCommandStationPomMessage4() throws ProtocolException {
        byte[] message =
            new byte[] { 0x0D, 0x00, 0x1A, 0x67, 0x03, 0x00, 0x00, 0x00, 0x00, 0x03, 0x02, 0x00, 0x00, 0x03 };
        CommandStationPomMessage pomMessage = new CommandStationPomMessage(message);
        Assert.assertNotNull(pomMessage);

        AddressData locoAddress = new AddressData(3, AddressTypeEnum.LOCOMOTIVE_FORWARD);

        LOGGER.info("Prepare pomMessage: {}", pomMessage);
        Assert.assertEquals(pomMessage.getDecoderAddress(), locoAddress);

        Assert.assertEquals(pomMessage.getAddressX(), 0x00);
        Assert.assertEquals(pomMessage.getMid(), 0x00);
        Assert.assertEquals(pomMessage.getOpCode(), (int) PomOperation.WR_BYTE.getType());
        Assert.assertEquals(pomMessage.getCvNumber(), 3);
        Assert.assertEquals(pomMessage.getCvValue(), 3);
    }

    // FE 0D 00 1C 67 03 00 00 00 00 03 02 00 00 04 F7 FE
    @Test
    public void prepareCommandStationPomMessage5() throws ProtocolException {
        byte[] message =
            new byte[] { 0x0D, 0x00, 0x1C, 0x67, 0x03, 0x00, 0x00, 0x00, 0x00, 0x03, 0x02, 0x00, 0x00, 0x04 };
        CommandStationPomMessage pomMessage = new CommandStationPomMessage(message);
        Assert.assertNotNull(pomMessage);

        AddressData locoAddress = new AddressData(3, AddressTypeEnum.LOCOMOTIVE_FORWARD);

        LOGGER.info("Prepare pomMessage: {}", pomMessage);
        Assert.assertEquals(pomMessage.getDecoderAddress(), locoAddress);

        Assert.assertEquals(pomMessage.getAddressX(), 0x00);
        Assert.assertEquals(pomMessage.getMid(), 0x00);
        Assert.assertEquals(pomMessage.getOpCode(), (int) PomOperation.WR_BYTE.getType());
        Assert.assertEquals(pomMessage.getCvNumber(), 3);
        Assert.assertEquals(pomMessage.getCvValue(), 4);
    }

    // FE 0D 00 76 67 0A 00 00 00 00 03 24 00 00 04 1B FE
    @Test
    public void prepareCommandStationPomMessage6() throws ProtocolException {
        byte[] message =
            new byte[] { 0x0D, 0x00, 0x76, 0x67, 0x0A, 0x00, 0x00, 0x00, 0x00, 0x03, 0x24, 0x00, 0x00, 0x04 };
        CommandStationPomMessage pomMessage = new CommandStationPomMessage(message);
        Assert.assertNotNull(pomMessage);

        AddressData locoAddress = new AddressData(10, AddressTypeEnum.LOCOMOTIVE_FORWARD);

        LOGGER.info("Prepare pomMessage: {}", pomMessage);
        Assert.assertEquals(pomMessage.getDecoderAddress(), locoAddress);

        Assert.assertEquals(pomMessage.getAddressX(), 0x00);
        Assert.assertEquals(pomMessage.getMid(), 0x00);
        Assert.assertEquals(pomMessage.getOpCode(), (int) PomOperation.WR_BYTE.getType());
        Assert.assertEquals(pomMessage.getCvNumber(), 37);
        Assert.assertEquals(pomMessage.getCvValue(), 4);
    }
}
