package uk.co.thinkofdeath.vanillacord.helper;

import com.mojang.authlib.properties.Property;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.EmptyByteBuf;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static uk.co.thinkofdeath.vanillacord.helper.BungeeHelper.*;

@SuppressWarnings("ConstantConditions")
public class VelocityHelper {

    private static final Object NAMESPACE = NamespacedKey.construct("velocity", "player_info");
    static final AttributeKey<Object> TRANSACTION_LOCK_KEY = AttributeKey.valueOf("-vch-transaction");
    static final AttributeKey<Object> INTERCEPTED_PACKET_KEY = AttributeKey.valueOf("-vch-intercepted");

    public static void initializeTransaction(Object network, Object intercepted) {
        try {
            Channel channel = (Channel) NetworkManager.channel.get(network);
            if (channel.attr(TRANSACTION_LOCK_KEY).get() != null) {
                throw new IllegalStateException("Unexpected login request");
            }

            // Prepare to receive
            channel.attr(TRANSACTION_LOCK_KEY).set(NAMESPACE);
            channel.attr(INTERCEPTED_PACKET_KEY).set(intercepted);


            // Send the packet
            LoginRequestPacket.send(network, 0, NAMESPACE, new EmptyByteBuf(ByteBufAllocator.DEFAULT));

        } catch (Exception e) {
            throw exception(null, e);
        }
    }

    public static void completeTransaction(Object network, Object login, Object response, String key) {
        try {
            Channel channel = (Channel) NetworkManager.channel.get(network);
            Object intercepted = channel.attr(INTERCEPTED_PACKET_KEY).getAndSet(null);
            if (intercepted == null) {
                throw new IllegalStateException("Unexpected login response");
            }

            // Check the metadata
            int id = LoginResponsePacket.getTransactionID(response);
            ByteBuf data = LoginResponsePacket.getData(response);

            if (id != 0)
                throw QuietException.notify("Unknown transaction ID: " + id);
            if (data == null)
                throw QuietException.notify("If you wish to use modern IP forwarding, please enable it in your Velocity config as well!");


            // Validate the data signature
            {
                byte[] signature = new byte[32];
                data.readBytes(signature);

                byte[] raw = new byte[data.readableBytes()];
                data.readBytes(raw).readerIndex(signature.length);
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(getSeecret(key), mac.getAlgorithm()));
                mac.update(raw);
                if (!Arrays.equals(signature, mac.doFinal()))
                    throw QuietException.notify("Received invalid IP forwarding data. Did you use the right forwarding secret?");
            }

            // Retrieve IP forwarding data
            readVarInt(data); // we don't do anything with the protocol version at this time

            NetworkManager.socket.set(network, new InetSocketAddress(readString(data), ((InetSocketAddress) NetworkManager.socket.get(network)).getPort()));
            channel.attr(UUID_KEY).set(new UUID(data.readLong(), data.readLong()));

            readString(data); // we don't do anything with the username field

            Property[] properties = new Property[readVarInt(data)];
            channel.attr(PROPERTIES_KEY).set(properties);
            for (int i = 0; i < properties.length; ++i) {
                properties[i] = new Property(readString(data), readString(data), (data.readBoolean())? readString(data) : null);
            }

            // Continue login flow
            LoginListener.handle(login, intercepted);

        } catch (Exception e) {
            throw exception(null, e);
        }
    }

    private static byte[] seecret = null;
    private static byte[] getSeecret(String def) throws IOException {
        if (seecret == null) {
            File config = new File("seecret.txt");
            if (config.exists()) {
                try (FileInputStream reader = new FileInputStream(config)) {
                    Properties properties = new Properties();
                    properties.load(reader);

                    String secret = properties.getProperty("modern-forwarding-secret");
                    if (secret == null || secret.length() == 0) secret = def;
                    seecret = secret.getBytes(UTF_8);
                }
            } else {
                seecret = def.getBytes(UTF_8);
                PrintWriter writer = new PrintWriter(config, UTF_8.name());
                writer.println("# Hey, there. We know you already gave VanillaCord a default secret key to use,");
                writer.println("# but if you ever need to change it, you can do so here without re-installing the patches.");
                writer.println("# ");
                writer.println("# This file is automatically generated by VanillaCord once a player attempts to join the server.");
                writer.println();
                writer.println("modern-forwarding-secret=");
                writer.close();
            }
        }
        return seecret;
    }

    static String readString(ByteBuf buf) {
        int len = readVarInt(buf);
        if (len > Short.MAX_VALUE * 4) {
            throw new RuntimeException("String is too long");
        }

        byte[] b = new byte[len];
        buf.readBytes(b);

        String s = new String(b, UTF_8);
        if (s.length() > Short.MAX_VALUE) {
            throw new RuntimeException("String is too long");
        }

        return s;
    }

    static int readVarInt(ByteBuf input) {
        int out = 0;
        int bytes = 0;
        byte in;
        do {
            in = input.readByte();
            out |= (in & 0x7F) << (bytes++ * 7);

            if (bytes > 5) {
                throw new RuntimeException("VarInt is too big");
            }
        } while ((in & 0x80) == 0x80);

        return out;
    }

    // Pre-calculate references to obfuscated classes
    static final class LoginListener {
        public static void handle(Object instance, Object packet) {
            throw exception("Class generation failed", new NoSuchMethodError());
        }
    }

    static final class NamespacedKey {
        public static Object construct(String space, String name) {
            throw exception("Class generation failed", new NoSuchMethodError());
        }
    }

    static final class LoginRequestPacket {
        private static final Field transactionID;
        private static final Field namespace;
        private static final Field data;

        static {
            try {
                Class<?> clazz = (Class<?>) (Object) "VCTR-LoginRequestPacket";

                transactionID = clazz.getDeclaredField("VCFR-LoginRequestPacket-TransactionID");
                transactionID.setAccessible(true);

                namespace = clazz.getDeclaredField("VCFR-LoginRequestPacket-Namespace");
                namespace.setAccessible(true);

                data = clazz.getDeclaredField("VCFR-LoginRequestPacket-Data");
                data.setAccessible(true);
            } catch (Throwable e) {
                throw exception("Class generation failed", e);
            }
        }

        public static void send(Object networkManager, int transactionID, Object namespace, ByteBuf data) throws Exception {
            throw exception("Class generation failed", new NoSuchMethodError());
        }
    }

    static final class LoginResponsePacket {
        private static final Field transactionID;
        private static final Field data;

        static {
            try {
                Class<?> clazz = (Class<?>) (Object) "VCTR-LoginResponsePacket";

                transactionID = clazz.getDeclaredField("VCFR-LoginResponsePacket-TransactionID");
                transactionID.setAccessible(true);

                data = clazz.getDeclaredField("VCFR-LoginResponsePacket-Data");
                data.setAccessible(true);
            } catch (Throwable e) {
                throw exception("Class generation failed", e);
            }
        }

        public static int getTransactionID(Object instance) throws Exception {
            return LoginResponsePacket.transactionID.getInt(instance);
        }

        public static ByteBuf getData(Object instance) throws Exception {
            return (ByteBuf) LoginResponsePacket.data.get(instance);
        }
    }
}
