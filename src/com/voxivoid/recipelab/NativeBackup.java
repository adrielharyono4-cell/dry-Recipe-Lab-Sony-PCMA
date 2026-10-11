package com.voxivoid.recipelab;

import java.util.HashMap;
import java.util.Map;

/** Pure Java mock of the camera settings store for dry-Film-lab. */
public class NativeBackup {
    private static final Map<Integer, byte[]> store = new HashMap<Integer, byte[]>();

    public static byte[] read(int id) throws NativeException {
        byte[] b = store.get(id);
        return b != null ? b : new byte[0];
    }
    
    public static void write(int id, byte[] data) throws NativeException {
        store.put(id, data);
    }
    
    public static int attr(int id) throws NativeException {
        return 0; // default attribute
    }
    
    public static void sync() {
        // Mock sync
    }

    public static int readByte(int id) throws NativeException {
        byte[] b = read(id);
        return b.length > 0 ? b[0] : 0;
    }
    
    public static void writeByte(int id, int value) throws NativeException {
        write(id, new byte[] { (byte) value });
    }
}
