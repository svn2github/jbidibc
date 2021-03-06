package org.bidib.message;

import org.bidib.BidibLibrary;

public class LcMacroParaSetMessage extends BidibMessage {
    public LcMacroParaSetMessage(int macroNumber, int parameter, byte... value) {
        super(0, BidibLibrary.MSG_LC_MACRO_PARA_SET, concat(new byte[] { (byte) macroNumber, (byte) parameter }, value));
    }
}
