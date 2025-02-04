package uk.co.thinkofdeath.vanillacord.generator;

import io.netty.buffer.ByteBuf;
import org.objectweb.asm.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;

public class VelocityHelper extends HelperVisitor {
    private final Class<?> networkManager;
    private final Class<?> loginListener;
    private final Class<?> loginPacket;
    private final Class<?> serverQuery;
    private final Class<?> clientQuery;
    private boolean useFields = true;

    public VelocityHelper(LinkedHashMap<String, byte[]> queue, ClassWriter classWriter, String networkManager, String loginListener, String loginPacket, String serverQuery, String clientQuery) throws ClassNotFoundException {
        super(queue, classWriter);
        this.networkManager = Class.forName(Type.getType(networkManager).getClassName());
        this.loginListener = getClass(loginListener);
        this.loginPacket = getClass(loginPacket);
        this.serverQuery = getClass(serverQuery);
        this.clientQuery = getClass(clientQuery);
    }

    private static void classSearch(Class<?> next, ArrayList<Class<?>> types) {
        types.add(next);

        if (next.getSuperclass() != null && !types.contains(next.getSuperclass())) types.add(next.getSuperclass());
        for (Class<?> c : next.getInterfaces()) if (!types.contains(c)) {
            classSearch(c, types);
        }
    }

    @Override
    protected void generate() {
        values.put("VCTR-NetworkManager", Type.getType(networkManager));
        {
            ArrayList<Class<?>> types = new ArrayList<>();
            classSearch(serverQuery, types);

            for (Method m : networkManager.getMethods()) {
                if (m.getParameterCount() == 1) {
                    Class<?> param = m.getParameterTypes()[0];
                    boolean select = false;
                    for (Class<?> type : types) {
                        if (param == type) {
                            select = true;
                            break;
                        }
                    }
                    if (select) {
                        values.put("VCM-NetworkManager-SendPacket", m);
                        break;
                    }
                }
            }
        }

        values.put("VCTR-LoginListener", Type.getType(loginListener));
        {
            for (Method m : loginListener.getMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == loginPacket) {
                    values.put("VCM-LoginListener-HandleIntercepted", m);
                    break;
                }
            }
        }

        values.put("VCTR-InterceptedPacket", Type.getType(loginPacket));
        values.put("VCTR-LoginRequestPacket", Type.getType(serverQuery));
        values.put("VCTR-LoginResponsePacket", Type.getType(clientQuery));
        {
            useFields = true;
            Constructor<?> qConstruct = null;
            for (Constructor<?> c : serverQuery.getConstructors()) {
                if (c.getParameterCount() == 3) {
                    useFields = false;
                    qConstruct = c;
                    break;
                }
            }

            if (useFields) {
                for (Field f : serverQuery.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) {
                        if (f.getType() == int.class) {
                            values.put("VCFR-LoginRequestPacket-TransactionID", f.getName());
                        } else if (ByteBuf.class.isAssignableFrom(f.getType())) {
                            values.put("VCTR-PacketData", Type.getType(f.getType()));
                            values.put("VCFR-LoginRequestPacket-Data", f.getName());
                        } else if (!f.getType().isPrimitive()) {
                            values.put("VCTR-NamespacedKey", Type.getType(f.getType()));
                            values.put("VCFR-LoginRequestPacket-Namespace", f.getName());
                        }
                    }
                }

                for (Field f : clientQuery.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) {
                        if (f.getType() == int.class) {
                            values.put("VCFR-LoginResponsePacket-TransactionID", f.getName());
                        } else if (ByteBuf.class.isAssignableFrom(f.getType())) {
                            values.put("VCFR-LoginResponsePacket-Data", f.getName());
                        }
                    }
                }
            } else {
                values.put("VCTR-NamespacedKey", Type.getType(qConstruct.getParameterTypes()[1]));
                values.put("VCTR-PacketData", Type.getType(qConstruct.getParameterTypes()[2]));

                for (Method m : clientQuery.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0) {
                        if (m.getReturnType() == int.class) {
                            values.put("VCM-LoginResponsePacket-GetTransactionID", m);
                        } else if (ByteBuf.class.isAssignableFrom(m.getReturnType())) {
                            values.put("VCM-LoginResponsePacket-GetData", m);
                        }
                    }
                }
            }
        }
    }

    @Override
    protected MethodVisitor rewriteMethod(String tag, MethodVisitor mv) {
        try {
            switch (tag) {
                case "LoginListener::handle": {
                    Type loginListener = (Type) values.get("VCTR-LoginListener");
                    Method method = (Method) values.get("VCM-LoginListener-HandleIntercepted");
                    Type packet = (Type) values.get("VCTR-InterceptedPacket");

                    mv.visitCode();
                    mv.visitLabel(new Label());
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, loginListener.getInternalName());
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, packet.getInternalName());
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            loginListener.getInternalName(),
                            method.getName(),
                            Type.getMethodDescriptor(method), false
                    );
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(2, 2);
                    mv.visitEnd();
                    return null;
                }
                case "NamespacedKey::construct": {
                    Type namespace = (Type) values.get("VCTR-NamespacedKey");
                    Constructor<?> constructor = getClass(namespace).getConstructor(String.class, String.class);

                    mv.visitCode();
                    mv.visitLabel(new Label());
                    mv.visitTypeInsn(Opcodes.NEW, namespace.getInternalName());
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                            namespace.getInternalName(),
                            "<init>",
                            Type.getConstructorDescriptor(constructor), false
                    );
                    mv.visitInsn(Opcodes.ARETURN);
                    mv.visitMaxs(2, 2);
                    mv.visitEnd();
                    return null;
                }
                case "LoginRequestPacket::send": {
                    Type data = (Type) values.get("VCTR-PacketData");
                    Type request  =  (Type) values.get("VCTR-LoginRequestPacket");
                    Type networkManager = (Type) values.get("VCTR-NetworkManager");
                    Method send_packet = (Method) values.get("VCM-NetworkManager-SendPacket");
                    Constructor<?> construct_data = getClass(data).getConstructor(ByteBuf.class);

                    mv.visitCode();
                    mv.visitLabel(new Label());

                    if (useFields) {
                        Type field = Type.getType(Field.class);
                        Method set_field = Field.class.getMethod("set", Object.class, Object.class);
                        Constructor<?> construct_request = getClass(request).getConstructor();

                        mv.visitTypeInsn(Opcodes.NEW, request.getInternalName());
                        mv.visitInsn(Opcodes.DUP);
                        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                                request.getInternalName(),
                                "<init>",
                                Type.getConstructorDescriptor(construct_request), false
                        );
                        mv.visitVarInsn(Opcodes.ASTORE, 4);

                        mv.visitLabel(new Label());
                        mv.visitFieldInsn(Opcodes.GETSTATIC,
                                "uk/co/thinkofdeath/vanillacord/helper/VelocityHelper$LoginRequestPacket",
                                "transactionID",
                                field.getDescriptor()
                        );
                        mv.visitVarInsn(Opcodes.ALOAD, 4);
                        mv.visitVarInsn(Opcodes.ILOAD, 1);
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                field.getInternalName(),
                                "setInt",
                                Type.getMethodDescriptor(Field.class.getMethod("setInt", Object.class, int.class)), false
                        );

                        mv.visitLabel(new Label());
                        mv.visitFieldInsn(Opcodes.GETSTATIC,
                                "uk/co/thinkofdeath/vanillacord/helper/VelocityHelper$LoginRequestPacket",
                                "namespace",
                                field.getDescriptor()
                        );
                        mv.visitVarInsn(Opcodes.ALOAD, 4);
                        mv.visitVarInsn(Opcodes.ALOAD, 2);
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                field.getInternalName(),
                                set_field.getName(),
                                Type.getMethodDescriptor(set_field), false
                        );

                        mv.visitLabel(new Label());
                        mv.visitFieldInsn(Opcodes.GETSTATIC,
                                "uk/co/thinkofdeath/vanillacord/helper/VelocityHelper$LoginRequestPacket",
                                "data",
                                field.getDescriptor()
                        );
                        mv.visitVarInsn(Opcodes.ALOAD, 4);
                        mv.visitTypeInsn(Opcodes.NEW, data.getInternalName());
                        mv.visitInsn(Opcodes.DUP);
                        mv.visitVarInsn(Opcodes.ALOAD, 3);
                        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                                data.getInternalName(),
                                "<init>",
                                Type.getConstructorDescriptor(construct_data), false
                        );
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                field.getInternalName(),
                                set_field.getName(),
                                Type.getMethodDescriptor(set_field), false
                        );

                        mv.visitLabel(new Label());
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitTypeInsn(Opcodes.CHECKCAST, networkManager.getInternalName());
                        mv.visitVarInsn(Opcodes.ALOAD, 4);
                    } else {
                        Type namespace = (Type) values.get("VCTR-NamespacedKey");
                        Constructor<?> construct_request = getClass(request).getConstructor(int.class, getClass(namespace), getClass(data));

                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitTypeInsn(Opcodes.CHECKCAST, networkManager.getInternalName());
                        mv.visitTypeInsn(Opcodes.NEW, request.getInternalName());
                        mv.visitInsn(Opcodes.DUP);
                        mv.visitVarInsn(Opcodes.ILOAD, 1);
                        mv.visitVarInsn(Opcodes.ALOAD, 2);
                        mv.visitTypeInsn(Opcodes.CHECKCAST, namespace.getInternalName());
                        mv.visitTypeInsn(Opcodes.NEW, data.getInternalName());
                        mv.visitInsn(Opcodes.DUP);
                        mv.visitVarInsn(Opcodes.ALOAD, 3);
                        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                                data.getInternalName(),
                                "<init>",
                                Type.getConstructorDescriptor(construct_data), false
                        );
                        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                                request.getInternalName(),
                                "<init>",
                                Type.getConstructorDescriptor(construct_request), false
                        );
                    }
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            networkManager.getInternalName(),
                            send_packet.getName(),
                            Type.getMethodDescriptor(send_packet), false
                    );
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(2, 2);
                    mv.visitEnd();
                    return null;
                }
                case "LoginResponsePacket::getTransactionID": {
                    if (useFields) {
                        return mv;
                    } else {
                        Type response = (Type) values.get("VCTR-LoginResponsePacket");
                        Method method = (Method) values.get("VCM-LoginResponsePacket-GetTransactionID");

                        mv.visitCode();
                        mv.visitLabel(new Label());
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitTypeInsn(Opcodes.CHECKCAST, response.getInternalName());
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                response.getInternalName(),
                                method.getName(),
                                Type.getMethodDescriptor(method), false
                        );
                        mv.visitInsn(Opcodes.IRETURN);
                        mv.visitMaxs(2, 2);
                        mv.visitEnd();
                        return null;
                    }
                }
                case "LoginResponsePacket::getData": {
                    if (useFields) {
                        return mv;
                    } else {
                        Type response = (Type) values.get("VCTR-LoginResponsePacket");
                        Method method = (Method) values.get("VCM-LoginResponsePacket-GetData");

                        mv.visitCode();
                        mv.visitLabel(new Label());
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitTypeInsn(Opcodes.CHECKCAST, response.getInternalName());
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                response.getInternalName(),
                                method.getName(),
                                Type.getMethodDescriptor(method), false
                        );
                        mv.visitInsn(Opcodes.ARETURN);
                        mv.visitMaxs(2, 2);
                        mv.visitEnd();
                        return null;
                    }
                }
                default: {
                    throw NOT_WRITTEN;
                }
            }
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            e.printStackTrace();
            throw NOT_WRITTEN;
        }
    }

    @Override
    protected boolean keepMethod(String tag) {
        return useFields || (!tag.equals("LoginRequestPacket::<clinit>") && !tag.equals("LoginResponsePacket::<clinit>"));
    }

    @Override
    protected boolean keepField(String tag) {
        return useFields || (!tag.startsWith("LoginRequestPacket.") && !tag.startsWith("LoginResponsePacket."));
    }
}
