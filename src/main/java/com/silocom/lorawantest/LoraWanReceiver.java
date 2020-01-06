package com.silocom.lorawantest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.silocom.m2m.layer.physical.Connection;
import com.silocom.m2m.layer.physical.MessageListener;
import com.silocom.protocol.lorawan.pf.PacketForwarder;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Base64;
import java.util.Random;

/**
 *
 * @author hvarona
 */
public class LoraWanReceiver {

    String data = null;
    int rssi = 0;
    int rfch = 0;
    int size = 0;
    long tmst = 0;
    float freq = 0;
    String datr = null;
    String codr = null;
    String modu = null;

    String utfString;
    PayloadConstructor Sender;
    JsonConstructor jsonCons;
    PacketForwarder pForwarder;

    final int joinRequest = 0x00;      //Secuencia dada por el documento de LoRaWAN Alliance
    final int joinAccept = 0x01;
    final int unconfirmedDataUp = 0x02;
    final int unconfirmedDataDown = 0x03;
    final int confirmedDataUp = 0x04;
    final int confirmedDataDown = 0x05;
    final int RFU = 0x06;
    final int propietary = 0x07;

    private final byte[] nwSKey;
    private final byte[] appSKey;
    private final byte[] appKey;

    private final Cipher cipher;

    private final SecretKeySpec secretKeySpec;
    private final Random rand = new Random();
    private final JsonParser parser = new JsonParser();

    public LoraWanReceiver(byte[] nwSKey, byte[] appSKey, byte[] appKey, PacketForwarder pf) throws NoSuchAlgorithmException, NoSuchPaddingException {
        this.cipher = Cipher.getInstance("AES/CTR/PKCS5Padding");
        this.nwSKey = nwSKey;
        this.appSKey = appSKey;
        this.appKey = appKey;
        this.pForwarder = pf;
        this.jsonCons = new JsonConstructor();
        this.Sender = new PayloadConstructor(jsonCons);
        secretKeySpec = new SecretKeySpec(appSKey, "AES");
    }

    public void ReceiveMessage(byte[] messageComplete, String message, boolean imme, long tmst, float freq, int rfch, int powe,
            String modu, String datr, String codr, boolean ipol, int size, boolean ncrc) {

        byte[] decodeMessage = Base64.decodeBase64(message);
        int mType = decodeMessage[0] & 0xFF;

        switch (mType) {

            case joinRequest:

                String string = new String(messageComplete);
                System.out.println(" Join Request: " + string);

                decodeJoinRequest(message, imme, tmst, freq, rfch, powe, modu, datr, codr, ipol, size, ncrc, appKey);
                break;

            case joinAccept:
                //ERROR - SERVER SIDE CANNOT RECEIVE JOIN ACCEPT 

                break;

            case unconfirmedDataUp:
                //TODO

                break;

            case unconfirmedDataDown:
                //TODO

                break;

            case confirmedDataUp:

                break;

            case confirmedDataDown:
                //TODO

                break;

            case RFU:
                //TODO

                break;

            case propietary:
                //TODO
                break;

            default:
                decodeMACPayload(message);
                String string2 = new String(messageComplete);
                System.out.println("Data up: " + string2);

        }

    }

    public void decodeJoinRequest(String message, boolean imme, long tmst, float freq, int rfch, int powe,
            String modu, String datr, String codr, boolean ipol, int size, boolean ncrc, byte[] appKey) {

        byte[] decodeMessage = Base64.decodeBase64(message);
        int mType = (decodeMessage[0] & 0xE0) << 5;
        long appEUI = (decodeMessage[1] & 0xFF)
                | (decodeMessage[2] & (long) 0xFF) << 8
                | (decodeMessage[3] & (long) 0xFF) << 16
                | (decodeMessage[4] & (long) 0xFF) << 24
                | (decodeMessage[5] & (long) 0xFF) << 32
                | (decodeMessage[6] & (long) 0xFF) << 40
                | (decodeMessage[7] & (long) 0xFF) << 48
                | (decodeMessage[8] & (long) 0xFF) << 56;

        long devAddr = (decodeMessage[9] & 0xFF)
                | (decodeMessage[10] & (long) 0xFF) << 8
                | (decodeMessage[11] & (long) 0xFF) << 16
                | (decodeMessage[12] & (long) 0xFF) << 24
                | (decodeMessage[13] & (long) 0xFF) << 32
                | (decodeMessage[14] & (long) 0xFF) << 40
                | (decodeMessage[15] & (long) 0xFF) << 48
                | (decodeMessage[16] & (long) 0xFF) << 56;

        int devNonce = (decodeMessage[18] & 0xFF)
                | (decodeMessage[17] & 0xFF) << 8;

        int appNonce = rand.nextInt(0x100000) + 0xEFFFFF;

        this.pForwarder.sendMessage(Sender.JoinAccept(appNonce, imme, tmst, freq, rfch, powe, modu, datr, codr, ipol, size, ncrc, appKey));

    }

    public String decodeMACPayload(String message) {
        byte[] decodeMessage = Base64.decodeBase64(message);
        // System.out.println("Message Decoded: " + Utils.hexToString(decodeMessage));
        int mType = decodeMessage[0] & 0xFF;
        int devAddress = (decodeMessage[1] & 0xff)
                | (decodeMessage[2] & 0xff) << 8
                | (decodeMessage[3] & 0xff) << 16
                | (decodeMessage[4] & 0xff) << 24;
        int fCtrl = decodeMessage[5] & 0xFF;
        int fCount = ((decodeMessage[7] & 0xff) << 8 | (decodeMessage[6] & 0xff));

        byte[] payload = new byte[decodeMessage.length - 9];
        System.arraycopy(decodeMessage, 9, payload, 0, decodeMessage.length - 9);
        return decryptPayload(payload, devAddress, fCount, (byte) 0).substring(0, payload.length - 4);
    }

    public String decryptPayload(byte[] payload, int devAddress, int fCount, byte dir) {
        try {
            byte[] ivKey = new byte[16];
            Arrays.fill(ivKey, (byte) 0);
            ivKey[0] = 1;
            ivKey[15] = 1;

            ivKey[5] = dir;
            ivKey[6] = (byte) ((devAddress) & 0xFF);
            ivKey[7] = (byte) ((devAddress >> 8) & 0xFF);
            ivKey[8] = (byte) ((devAddress >> 16) & 0xFF);
            ivKey[9] = (byte) ((devAddress >> 24) & 0xFF);

            ivKey[10] = (byte) ((fCount) & 0xFF);
            ivKey[11] = (byte) ((fCount >> 8) & 0xFF);

            IvParameterSpec ivParameterSpec = new IvParameterSpec(ivKey);

            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec);

            return new String(cipher.doFinal(payload));
        } catch (InvalidAlgorithmParameterException | InvalidKeyException | BadPaddingException | IllegalBlockSizeException ex) {
        }
        return null;
    }

    /* @Override
    public void receiveMessage(byte[] message, Connection con) {
        //throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }*/
}
