/*
 * This file is part of UltimateCore, licensed under the MIT License (MIT).
 *
 * Copyright (c) Bammerbom
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package bammerbom.ultimatecore.bukkit.resources.utils;

import bammerbom.ultimatecore.bukkit.resources.utils.NbtFactory.NbtCompound;
import bammerbom.ultimatecore.bukkit.resources.utils.NbtFactory.NbtList;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.*;
import com.google.common.io.Closeables;
import com.google.common.io.Files;
import com.google.common.primitives.Primitives;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class AttributeUtil {

    // This may be modified
    public ItemStack stack;
    private NbtList attributes;

    public AttributeUtil(ItemStack stack) {
        // Create a CraftItemStack (under the hood)
        this.stack = NbtFactory.getCraftItemStack(stack);

        // Load NBT
        NbtCompound nbt = NbtFactory.fromItemTag(this.stack);
        this.attributes = nbt.getList("AttributeModifiers", true);
    }

    /**
     * Retrieve the modified item stack.
     *
     * @return The modified item stack.
     */
    public ItemStack getStack() {
        return stack;
    }

    /**
     * Retrieve the number of attributes.
     *
     * @return Number of attributes.
     */
    public int size() {
        return attributes.size();
    }

    /**
     * Add a new attribute to the list.
     *
     * @param attribute - the new attribute.
     */
    public void add(Attribute attribute) {
        Preconditions.checkNotNull(attribute.getName(), "must specify an attribute name.");
        attributes.add(attribute.data);
    }

    /**
     * Remove the first instance of the given attribute.
     * <p/>
     * The attribute will be removed using its UUID.
     *
     * @param attribute - the attribute to remove.
     * @return TRUE if the attribute was removed, FALSE otherwise.
     */
    public boolean remove(Attribute attribute) {
        UUID uuid = attribute.getUUID();

        for (Iterator<Attribute> it = values().iterator(); it.hasNext();) {
            if (Objects.equal(it.next().getUUID(), uuid)) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    public void clear() {
        attributes.clear();
    }

    /**
     * Retrieve the attribute at a given index.
     *
     * @param index - the index to look up.
     * @return The attribute at that index.
     */
    public Attribute get(int index) {
        return new Attribute((NbtCompound) attributes.get(index));
    }

    // We can't make Attributes itself iterable without splitting it up into separate classes
    public Iterable<Attribute> values() {
        return new Iterable<Attribute>() {
            @Override
            public Iterator<Attribute> iterator() {
                return Iterators.transform(attributes.iterator(),
                        new Function<Object, Attribute>() {
                            @Override
                            public Attribute apply(@Nullable Object element) {
                                return new Attribute((NbtCompound) element);
                            }
                        });
            }
        };
    }

    public enum Operation {

        ADD_NUMBER(0),
        MULTIPLY_PERCENTAGE(1),
        ADD_PERCENTAGE(2);
        private int id;

        private Operation(int id) {
            this.id = id;
        }

        public static Operation fromId(int id) {
            // Linear scan is very fast for small N
            for (Operation op : values()) {
                if (op.getId() == id) {
                    return op;
                }
            }
            throw new IllegalArgumentException("Corrupt operation ID " + id + " detected.");
        }

        public int getId() {
            return id;
        }
    }

    public static class AttributeType {

        public static final AttributeType GENERIC_MAX_HEALTH = new AttributeType("generic.maxHealth").register();
        public static final AttributeType GENERIC_FOLLOW_RANGE = new AttributeType("generic.followRange").register();
        public static final AttributeType GENERIC_ATTACK_DAMAGE = new AttributeType("generic.attackDamage").register();
        public static final AttributeType GENERIC_MOVEMENT_SPEED = new AttributeType("generic.movementSpeed").register();
        public static final AttributeType GENERIC_KNOCKBACK_RESISTANCE = new AttributeType("generic.knockbackResistance").register();
        private static ConcurrentMap<String, AttributeType> LOOKUP = Maps.newConcurrentMap();
        private final String minecraftId;

        /**
         * Construct a new attribute type.
         * <p/>
         * Remember to {@link #register()} the type.
         *
         * @param minecraftId - the ID of the type.
         */
        public AttributeType(String minecraftId) {
            this.minecraftId = minecraftId;
        }

        /**
         * Retrieve the attribute type associated with a given ID.
         *
         * @param minecraftId The ID to search for.
         * @return The attribute type, or NULL if not found.
         */
        public static AttributeType fromId(String minecraftId) {
            return LOOKUP.get(minecraftId);
        }

        /**
         * Retrieve every registered attribute type.
         *
         * @return Every type.
         */
        public static Iterable<AttributeType> values() {
            return LOOKUP.values();
        }

        /**
         * Retrieve the associated minecraft ID.
         *
         * @return The associated ID.
         */
        public String getMinecraftId() {
            return minecraftId;
        }

        /**
         * Register the type in the central registry.
         *
         * @return The registered type.
         */
        // Constructors should have no side-effects!
        public AttributeType register() {
            AttributeType old = LOOKUP.putIfAbsent(minecraftId, this);
            return old != null ? old : this;
        }
    }

    public static class Attribute {

        private NbtCompound data;

        private Attribute(Builder builder) {
            data = NbtFactory.createCompound();
            setAmount(builder.amount);
            setOperation(builder.operation);
            setAttributeType(builder.type);
            setName(builder.name);
            setUUID(builder.uuid);
        }

        private Attribute(NbtCompound data) {
            this.data = data;
        }

        /**
         * Construct a new attribute builder with a random UUID and default
         * operation of adding numbers.
         *
         * @return The attribute builder.
         */
        public static Builder newBuilder() {
            return new Builder().uuid(UUID.randomUUID()).operation(Operation.ADD_NUMBER);
        }

        public double getAmount() {
            return data.getDouble("Amount", 0.0);
        }

        public void setAmount(double amount) {
            data.put("Amount", amount);
        }

        public Operation getOperation() {
            return Operation.fromId(data.getInteger("Operation", 0));
        }

        public void setOperation(@Nonnull Operation operation) {
            Preconditions.checkNotNull(operation, "operation cannot be NULL.");
            data.put("Operation", operation.getId());
        }

        public AttributeType getAttributeType() {
            return AttributeType.fromId(data.getString("AttributeName", null));
        }

        public void setAttributeType(@Nonnull AttributeType type) {
            Preconditions.checkNotNull(type, "type cannot be NULL.");
            data.put("AttributeName", type.getMinecraftId());
        }

        public String getName() {
            return data.getString("Name", null);
        }

        public void setName(@Nonnull String name) {
            Preconditions.checkNotNull(name, "name cannot be NULL.");
            data.put("Name", name);
        }

        public UUID getUUID() {
            return new UUID(data.getLong("UUIDMost", null), data.getLong("UUIDLeast", null));
        }

        public void setUUID(@Nonnull UUID id) {
            Preconditions.checkNotNull("id", "id cannot be NULL.");
            data.put("UUIDLeast", id.getLeastSignificantBits());
            data.put("UUIDMost", id.getMostSignificantBits());
        }

        // Makes it easier to construct an attribute
        public static class Builder {

            private double amount;
            private Operation operation = Operation.ADD_NUMBER;
            private AttributeType type;
            private String name;
            private UUID uuid;

            private Builder() {
                // Don't make this accessible
            }

            public Builder amount(double amount) {
                this.amount = amount;
                return this;
            }

            public Builder operation(Operation operation) {
                this.operation = operation;
                return this;
            }

            public Builder type(AttributeType type) {
                this.type = type;
                return this;
            }

            public Builder name(String name) {
                this.name = name;
                return this;
            }

            public Builder uuid(UUID uuid) {
                this.uuid = uuid;
                return this;
            }

            public Attribute build() {
                return new Attribute(this);
            }
        }
    }
}

class NbtFactory {

    // Convert between NBT id and the equivalent class in java
    private static final BiMap<Integer, Class<?>> NBT_CLASS = HashBiMap.create();
    private static final BiMap<Integer, NbtType> NBT_ENUM = HashBiMap.create();
    // Shared instance
    private static NbtFactory INSTANCE;
    private final Field[] DATA_FIELD = new Field[12];
    // The NBT base class
    private Class<?> BASE_CLASS;
    private Class<?> COMPOUND_CLASS;
    private Method NBT_CREATE_TAG;
    private Method NBT_GET_TYPE;
    private Field NBT_LIST_TYPE;
    // CraftItemStack
    private Class<?> CRAFT_STACK;
    private Field CRAFT_HANDLE;
    private Field STACK_TAG;
    // Loading/saving compounds
    private Method LOAD_COMPOUND;
    private Method SAVE_COMPOUND;

    /**
     * Construct an instance of the NBT factory by deducing the class of
     * NBTBase.
     */
    private NbtFactory() {
        if (BASE_CLASS == null) {
            try {
                // Keep in mind that I do use hard-coded field names - but it's okay as long as we're dealing
                // with CraftBukkit or its derivatives. This does not work in MCPC+ however.
                ClassLoader loader = NbtFactory.class.getClassLoader();
                //String packageName = "org.bukkit.craftbukkit.v1_6_R2";
                String packageName = Bukkit.getServer().getClass().getPackage().getName();
                Class<?> offlinePlayer = loader.loadClass(packageName + ".CraftOfflinePlayer");

                // Prepare NBT
                COMPOUND_CLASS = getMethod(0, Modifier.STATIC, offlinePlayer, "getData").getReturnType();
                BASE_CLASS = COMPOUND_CLASS.getSuperclass();
                NBT_GET_TYPE = getMethod(0, Modifier.STATIC, BASE_CLASS, "getTypeId");
                NBT_CREATE_TAG = getMethod(Modifier.STATIC, 0, BASE_CLASS, "createTag", byte.class, String.class);

                // Prepare CraftItemStack
                CRAFT_STACK = loader.loadClass(packageName + ".inventory.CraftItemStack");
                CRAFT_HANDLE = getField(null, CRAFT_STACK, "handle");
                STACK_TAG = getField(null, CRAFT_HANDLE.getType(), "tag");

                // Loading/saving
                LOAD_COMPOUND = getMethod(Modifier.STATIC, 0, BASE_CLASS, null, DataInput.class);
                SAVE_COMPOUND = getMethod(Modifier.STATIC, 0, BASE_CLASS, null, BASE_CLASS, DataOutput.class);

            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Unable to find offline player.", e);
            }
        }
    }

    /**
     * Retrieve or construct a shared NBT factory.
     *
     * @return The factory.
     */
    private static NbtFactory get() {
        if (INSTANCE == null) {
            INSTANCE = new NbtFactory();
        }
        return INSTANCE;
    }

    /**
     * Construct a new NBT list of an unspecified type.
     *
     * @return The NBT list.
     */
    public static NbtList createList(Object... content) {
        return createList(Arrays.asList(content));
    }

    /**
     * Construct a new NBT list of an unspecified type.
     *
     * @return The NBT list.
     */
    public static NbtList createList(Iterable<? extends Object> iterable) {
        NbtList list = get().new NbtList(
                INSTANCE.createNbtTag(NbtType.TAG_LIST, "", null)
        );

        // Add the content as well
        for (Object obj : iterable) {
            list.add(obj);
        }
        return list;
    }

    /**
     * Construct a new NBT compound.
     * <p/>
     * Use {@link NbtCompound#asMap()} to modify it.
     *
     * @return The NBT compound.
     */
    public static NbtCompound createCompound() {
        return get().new NbtCompound(
                INSTANCE.createNbtTag(NbtType.TAG_COMPOUND, "", null)
        );
    }

    /**
     * Construct a new NBT root compound.
     * <p/>
     * This compound must be given a name, as it is the root object.
     *
     * @param name - the name of the compound.
     * @return The NBT compound.
     */
    public static NbtCompound createRootCompound(String name) {
        return get().new NbtCompound(
                INSTANCE.createNbtTag(NbtType.TAG_COMPOUND, name, null)
        );
    }

    /**
     * Construct a new NBT wrapper from a list.
     *
     * @param nmsList - the NBT list.
     * @return The wrapper.
     */
    public static NbtList fromList(Object nmsList) {
        return get().new NbtList(nmsList);
    }

    /**
     * Load the content of a file from a stream.
     * <p/>
     * Use {@link Files#newInputStreamSupplier(java.io.File)} to provide a
     * stream from a file.
     *
     * @param stream - the stream supplier.
     * @param option - whether or not to decompress the input stream.
     * @return The decoded NBT compound.
     * @throws IOException If anything went wrong.
     */
    public static NbtCompound fromStream(InputStream input, StreamOptions option) throws IOException {
        DataInputStream data = null;

        try {
            data = new DataInputStream(new BufferedInputStream(
                    option == StreamOptions.GZIP_COMPRESSION ? new GZIPInputStream(input) : input
            ));

            return fromCompound(invokeMethod(get().LOAD_COMPOUND, null, data));
        } finally {
            if (data != null) {
                Closeables.closeQuietly(data);
            }
            if (input != null) {
                Closeables.closeQuietly(input);
            }
        }
    }

    /**
     * Save the content of a NBT compound to a stream.
     * <p/>
     * Use {@link Files#newOutputStreamSupplier(java.io.File)} to provide a
     * stream supplier to a file.
     *
     * @param source - the NBT compound to save.
     * @param stream - the stream.
     * @param option - whether or not to compress the output.
     * @throws IOException If anything went wrong.
     */
    public static void saveStream(NbtCompound source, OutputStream output, StreamOptions option) throws IOException {
        DataOutputStream data = null;

        try {
            data = new DataOutputStream(
                    option == StreamOptions.GZIP_COMPRESSION ? new GZIPOutputStream(output) : output
            );

            invokeMethod(get().SAVE_COMPOUND, null, source.getHandle(), data);
        } finally {
            if (data != null) {
                data.close();
            }
            if (output != null) {
                output.close();
            }
        }
    }

    /**
     * Construct a new NBT wrapper from a compound.
     *
     * @param nmsCompound - the NBT compund.
     * @return The wrapper.
     */
    public static NbtCompound fromCompound(Object nmsCompound) {
        return get().new NbtCompound(nmsCompound);
    }

    /**
     * Set the NBT compound tag of a given item stack.
     * <p/>
     * The item stack must be a wrapper for a CraftItemStack. Use
     * {@link ItemStack} if not.
     *
     * @param stack - the item stack, cannot be air.
     * @param compound - the new NBT compound, or NULL to remove it.
     * @throws IllegalArgumentException If the stack is not a CraftItemStack, or
     * it represents air.
     */
    public static void setItemTag(ItemStack stack, NbtCompound compound) {
        checkItemStack(stack);
        Object nms = getFieldValue(get().CRAFT_HANDLE, stack);

        // Now update the tag compound
        setFieldValue(get().STACK_TAG, nms, compound.getHandle());
    }

    /**
     * Construct a wrapper for an NBT tag stored (in memory) in an item stack.
     * This is where auxillary data such as enchanting, name and lore is stored.
     * It does not include items material, damage value or count.
     * <p/>
     * The item stack must be a wrapper for a CraftItemStack.
     *
     * @param stack - the item stack.
     * @return A wrapper for its NBT tag.
     */
    public static NbtCompound fromItemTag(ItemStack stack) {
        checkItemStack(stack);
        Object nms = getFieldValue(get().CRAFT_HANDLE, stack);
        Object tag = getFieldValue(get().STACK_TAG, nms);

        // Create the tag if it doesn't exist
        if (tag == null) {
            NbtCompound compound = createRootCompound("tag");
            setItemTag(stack, compound);
            return compound;
        }
        return fromCompound(tag);
    }

    /**
     * Retrieve a CraftItemStack version of the stack.
     *
     * @param stack - the stack to convert.
     * @return The CraftItemStack version.
     */
    public static ItemStack getCraftItemStack(ItemStack stack) {
        // Any need to convert?
        if (stack == null || get().CRAFT_STACK.isAssignableFrom(stack.getClass())) {
            return stack;
        }
        try {
            // Call the private constructor
            Constructor<?> caller = INSTANCE.CRAFT_STACK.getDeclaredConstructor(ItemStack.class);
            caller.setAccessible(true);
            return (ItemStack) caller.newInstance(stack);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to convert " + stack + " + to a CraftItemStack.");
        }
    }

    /**
     * Ensure that the given stack can store arbitrary NBT information.
     *
     * @param stack - the stack to check.
     */
    private static void checkItemStack(ItemStack stack) {
        if (stack == null) {
            throw new IllegalArgumentException("Stack cannot be NULL.");
        }
        if (!get().CRAFT_STACK.isAssignableFrom(stack.getClass())) {
            throw new IllegalArgumentException("Stack must be a CraftItemStack.");
        }
        if (stack.getType() == Material.AIR) {
            throw new IllegalArgumentException("ItemStacks representing air cannot store NMS information.");
        }
    }

    /**
     * Invoke a method on the given target instance using the provided
     * parameters.
     *
     * @param method - the method to invoke.
     * @param target - the target.
     * @param params - the parameters to supply.
     * @return The result of the method.
     */
    private static Object invokeMethod(Method method, Object target, Object... params) {
        try {
            return method.invoke(target, params);
        } catch (Exception e) {
            throw new RuntimeException("Unable to invoke method " + method + " for " + target, e);
        }
    }

    private static void setFieldValue(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Unable to set " + field + " for " + target, e);
        }
    }

    private static Object getFieldValue(Field field, Object target) {
        try {
            return field.get(target);
        } catch (Exception e) {
            throw new RuntimeException("Unable to retrieve " + field + " for " + target, e);
        }
    }

    /**
     * Search for the first publically and privately defined method of the given
     * name and parameter count.
     *
     * @param requireMod - modifiers that are required.
     * @param bannedMod - modifiers that are banned.
     * @param clazz - a class to start with.
     * @param methodName - the method name, or NULL to skip.
     * @param paramCount - the expected parameter count.
     * @return The first method by this name.
     * @throws IllegalStateException If we cannot find this method.
     */
    private static Method getMethod(int requireMod, int bannedMod, Class<?> clazz, String methodName, Class<?>... params) {
        for (Method method : clazz.getDeclaredMethods()) {
            // Limitation: Doesn't handle overloads
            if ((method.getModifiers() & requireMod) == requireMod
                    && (method.getModifiers() & bannedMod) == 0
                    && (methodName == null || method.getName().equals(methodName))
                    && Arrays.equals(method.getParameterTypes(), params)) {

                method.setAccessible(true);
                return method;
            }
        }
        // Search in every superclass
        if (clazz.getSuperclass() != null) {
            return getMethod(requireMod, bannedMod, clazz.getSuperclass(), methodName, params);
        }
        throw new IllegalStateException(String.format(
                "Unable to find method %s (%s).", methodName, Arrays.asList(params)));
    }

    /**
     * Search for the first publically and privately defined field of the given
     * name.
     *
     * @param instance - an instance of the class with the field.
     * @param clazz - an optional class to start with, or NULL to deduce it from
     * instance.
     * @param fieldName - the field name.
     * @return The first field by this name.
     * @throws IllegalStateException If we cannot find this field.
     */
    private static Field getField(Object instance, Class<?> clazz, String fieldName) {
        if (clazz == null) {
            clazz = instance.getClass();
        }
        // Ignore access rules
        for (Field field : clazz.getDeclaredFields()) {
            if (field.getName().equals(fieldName)) {
                field.setAccessible(true);
                return field;
            }
        }
        // Recursively fild the correct field
        if (clazz.getSuperclass() != null) {
            return getField(instance, clazz.getSuperclass(), fieldName);
        }
        throw new IllegalStateException("Unable to find field " + fieldName + " in " + instance);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getDataMap(Object handle) {
        return (Map<String, Object>) getFieldValue(
                getDataField(NbtType.TAG_COMPOUND, handle), handle);
    }

    @SuppressWarnings("unchecked")
    private List<Object> getDataList(Object handle) {
        return (List<Object>) getFieldValue(
                getDataField(NbtType.TAG_LIST, handle), handle);
    }

    /**
     * Convert wrapped List and Map objects into their respective NBT
     * counterparts.
     *
     * @param name - the name of the NBT element to create.
     * @param value - the value of the element to create. Can be a List or a
     * Map.
     * @return The NBT element.
     */
    private Object unwrapValue(String name, Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Wrapper) {
            return ((Wrapper) value).getHandle();

        } else if (value instanceof List) {
            throw new IllegalArgumentException("Can only insert a WrappedList.");
        } else if (value instanceof Map) {
            throw new IllegalArgumentException("Can only insert a WrappedCompound.");

        } else {
            return createNbtTag(getPrimitiveType(value), name, value);
        }
    }

    /**
     * Convert a given NBT element to a primitive wrapper or List/Map
     * equivalent.
     * <p/>
     * All changes to any mutable objects will be reflected in the underlying
     * NBT element(s).
     *
     * @param nms - the NBT element.
     * @return The wrapper equivalent.
     */
    private Object wrapNative(Object nms) {
        if (nms == null) {
            return null;
        }

        if (BASE_CLASS.isAssignableFrom(nms.getClass())) {
            final NbtType type = getNbtType(nms);

            // Handle the different types
            switch (type) {
                case TAG_COMPOUND:
                    return new NbtCompound(nms);
                case TAG_LIST:
                    return new NbtList(nms);
                default:
                    return getFieldValue(getDataField(type, nms), nms);
            }
        }
        throw new IllegalArgumentException("Unexpected type: " + nms);
    }

    /**
     * Construct a new NMS NBT tag initialized with the given value.
     *
     * @param type - the NBT type.
     * @param name - the name of the NBT tag.
     * @param value - the value, or NULL to keep the original value.
     * @return The created tag.
     */
    private Object createNbtTag(NbtType type, String name, Object value) {
        Object tag = invokeMethod(NBT_CREATE_TAG, null, (byte) type.id, name);

        if (value != null) {
            setFieldValue(getDataField(type, tag), tag, value);
        }
        return tag;
    }

    /**
     * Retrieve the field where the NBT class stores its value.
     *
     * @param type - the NBT type.
     * @param nms - the NBT class instance.
     * @return The corresponding field.
     */
    private Field getDataField(NbtType type, Object nms) {
        if (DATA_FIELD[type.id] == null) {
            DATA_FIELD[type.id] = getField(nms, null, type.getFieldName());
        }
        return DATA_FIELD[type.id];
    }

    /**
     * Retrieve the NBT type from a given NMS NBT tag.
     *
     * @param nms - the native NBT tag.
     * @return The corresponding type.
     */
    private NbtType getNbtType(Object nms) {
        int type = (Byte) invokeMethod(NBT_GET_TYPE, nms);
        return NBT_ENUM.get(type);
    }

    /**
     * Retrieve the nearest NBT type for a given primitive type.
     *
     * @param primitive - the primitive type.
     * @return The corresponding type.
     */
    private NbtType getPrimitiveType(Object primitive) {
        NbtType type = NBT_ENUM.get(NBT_CLASS.inverse().get(
                Primitives.unwrap(primitive.getClass())
        ));

        // Display the illegal value at least
        if (type == null) {
            throw new IllegalArgumentException(String.format(
                    "Illegal type: %s (%s)", primitive.getClass(), primitive));
        }
        return type;
    }

    /**
     * Whether or not to enable stream compression.
     *
     * @author Kristian
     */
    public enum StreamOptions {

        NO_COMPRESSION,
        GZIP_COMPRESSION,
    }

    private enum NbtType {

        TAG_END(0, Void.class),
        TAG_BYTE(1, byte.class),
        TAG_SHORT(2, short.class),
        TAG_INT(3, int.class),
        TAG_LONG(4, long.class),
        TAG_FLOAT(5, float.class),
        TAG_DOUBLE(6, double.class),
        TAG_BYTE_ARRAY(7, byte[].class),
        TAG_INT_ARRAY(11, int[].class),
        TAG_STRING(8, String.class),
        TAG_LIST(9, List.class),
        TAG_COMPOUND(10, Map.class);

        // Unique NBT id
        public final int id;

        private NbtType(int id, Class<?> type) {
            this.id = id;
            NBT_CLASS.put(id, type);
            NBT_ENUM.put(id, this);
        }

        private String getFieldName() {
            if (this == TAG_COMPOUND) {
                return "map";
            } else if (this == TAG_LIST) {
                return "list";
            } else {
                return "data";
            }
        }
    }

    /**
     * Represents an object that provides a view of a native NMS class.
     *
     * @author Kristian
     */
    public static interface Wrapper {

        /**
         * Retrieve the underlying native NBT tag.
         *
         * @return The underlying NBT.
         */
        public Object getHandle();
    }

    /**
     * Represents a root NBT compound.
     * <p/>
     * All changes to this map will be reflected in the underlying NBT compound.
     * Values may only be one of the following:
     * <ul>
     * <li>Primitive types</li>
     * <li>{@link java.lang.String String}</li>
     * <li>{@link NbtList}</li>
     * <li>{@link NbtCompound}</li>
     * </ul>
     * <p/>
     * See also:
     * <ul>
     * <li>{@link NbtFactory#createCompound()}</li>
     * <li>{@link NbtFactory#fromCompound(Object)}</li>
     * </ul>
     *
     * @author Kristian
     */
    public final class NbtCompound extends ConvertedMap {

        private NbtCompound(Object handle) {
            super(handle, getDataMap(handle));
        }

        // Simplifiying access to each value
        public Byte getByte(String key, Byte defaultValue) {
            return containsKey(key) ? (Byte) get(key) : defaultValue;
        }

        public Short getShort(String key, Short defaultValue) {
            return containsKey(key) ? (Short) get(key) : defaultValue;
        }

        public Integer getInteger(String key, Integer defaultValue) {
            return containsKey(key) ? (Integer) get(key) : defaultValue;
        }

        public Long getLong(String key, Long defaultValue) {
            return containsKey(key) ? (Long) get(key) : defaultValue;
        }

        public Float getFloat(String key, Float defaultValue) {
            return containsKey(key) ? (Float) get(key) : defaultValue;
        }

        public Double getDouble(String key, Double defaultValue) {
            return containsKey(key) ? (Double) get(key) : defaultValue;
        }

        public String getString(String key, String defaultValue) {
            return containsKey(key) ? (String) get(key) : defaultValue;
        }

        public byte[] getByteArray(String key, byte[] defaultValue) {
            return containsKey(key) ? (byte[]) get(key) : defaultValue;
        }

        public int[] getIntegerArray(String key, int[] defaultValue) {
            return containsKey(key) ? (int[]) get(key) : defaultValue;
        }

        /**
         * Retrieve the list by the given name.
         *
         * @param key - the name of the list.
         * @param createNew - whether or not to create a new list if its
         * missing.
         * @return An existing list, a new list or NULL.
         */
        public NbtList getList(String key, boolean createNew) {
            NbtList list = (NbtList) get(key);

            if (list == null) {
                put(key, list = createList());
            }
            return list;
        }

        /**
         * Retrieve the map by the given name.
         *
         * @param key - the name of the map.
         * @param createNew - whether or not to create a new map if its missing.
         * @return An existing map, a new map or NULL.
         */
        public NbtCompound getMap(String key, boolean createNew) {
            return getMap(Arrays.asList(key), createNew);
        }
        // Done

        /**
         * Set the value of an entry at a given location.
         * <p/>
         * Every element of the path (except the end) are assumed to be
         * compounds, and will be created if they are missing.
         *
         * @param path - the path to the entry.
         * @param value - the new value of this entry.
         * @return This compound, for chaining.
         */
        public NbtCompound putPath(String path, Object value) {
            List<String> entries = getPathElements(path);
            Map<String, Object> map = getMap(entries.subList(0, entries.size() - 1), true);

            map.put(entries.get(entries.size() - 1), value);
            return this;
        }

        /**
         * Retrieve the value of a given entry in the tree.
         * <p/>
         * Every element of the path (except the end) are assumed to be
         * compounds. The retrieval operation will be cancelled if any of them
         * are missing.
         *
         * @param path - path to the entry.
         * @return The value, or NULL if not found.
         */
        @SuppressWarnings("unchecked")
        public <T> T getPath(String path) {
            List<String> entries = getPathElements(path);
            NbtCompound map = getMap(entries.subList(0, entries.size() - 1), false);

            if (map != null) {
                return (T) map.get(entries.get(entries.size() - 1));
            }
            return null;
        }

        /**
         * Save the content of a NBT compound to a stream.
         * <p/>
         * Use {@link Files#newOutputStreamSupplier(java.io.File)} to provide a
         * stream supplier to a file.
         *
         * @param stream - the output stream.
         * @param option - whether or not to compress the output.
         * @throws IOException If anything went wrong.
         */
        public void saveTo(OutputStream stream, StreamOptions option) throws IOException {
            saveStream(this, stream, option);
        }

        /**
         * Retrieve a map from a given path.
         *
         * @param path - path of compounds to look up.
         * @param createNew - whether or not to create new compounds on the way.
         * @return The map at this location.
         */
        private NbtCompound getMap(Iterable<String> path, boolean createNew) {
            NbtCompound current = this;

            for (String entry : path) {
                NbtCompound child = (NbtCompound) current.get(entry);

                if (child == null) {
                    if (!createNew) {
                        throw new IllegalArgumentException("Cannot find " + entry + " in " + path);
                    }
                    current.put(entry, child = createCompound());
                }
                current = child;
            }
            return current;
        }

        /**
         * Split the path into separate elements.
         *
         * @param path - the path to split.
         * @return The elements.
         */
        private List<String> getPathElements(String path) {
            return Lists.newArrayList(Splitter.on(".").omitEmptyStrings().split(path));
        }
    }

    /**
     * Represents a root NBT list. See also:
     * <ul>
     * <li>{@link NbtFactory#createNbtList()}</li>
     * <li>{@link NbtFactory#fromList(Object)}</li>
     * </ul>
     *
     * @author Kristian
     */
    public final class NbtList extends ConvertedList {

        private NbtList(Object handle) {
            super(handle, getDataList(handle));
        }
    }

    /**
     * Represents a class for caching wrappers.
     *
     * @author Kristian
     */
    private final class CachedNativeWrapper {

        // Don't recreate wrapper objects
        private final ConcurrentMap<Object, Object> cache = new MapMaker().weakKeys().makeMap();

        public Object wrap(Object value) {
            Object current = cache.get(value);

            if (current == null) {
                current = wrapNative(value);

                // Only cache composite objects
                if (current instanceof ConvertedMap
                        || current instanceof ConvertedList) {
                    cache.put(value, current);
                }
            }
            return current;
        }
    }

    /**
     * Represents a map that wraps another map and automatically converts
     * entries of its type and another exposed type.
     *
     * @author Kristian
     */
    private class ConvertedMap extends AbstractMap<String, Object> implements Wrapper {

        private final Object handle;
        private final Map<String, Object> original;

        private final CachedNativeWrapper cache = new CachedNativeWrapper();

        public ConvertedMap(Object handle, Map<String, Object> original) {
            this.handle = handle;
            this.original = original;
        }

        // For converting back and forth
        protected Object wrapOutgoing(Object value) {
            return cache.wrap(value);
        }

        protected Object unwrapIncoming(String key, Object wrapped) {
            return unwrapValue(key, wrapped);
        }

        // Modification
        @Override
        public Object put(String key, Object value) {
            return wrapOutgoing(original.put(
                    (String) key,
                    unwrapIncoming((String) key, value)
            ));
        }

        // Performance
        @Override
        public Object get(Object key) {
            return wrapOutgoing(original.get(key));
        }

        @Override
        public Object remove(Object key) {
            return wrapOutgoing(original.remove(key));
        }

        @Override
        public boolean containsKey(Object key) {
            return original.containsKey(key);
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return new AbstractSet<Entry<String, Object>>() {
                @Override
                public boolean add(Entry<String, Object> e) {
                    String key = e.getKey();
                    Object value = e.getValue();

                    original.put(key, unwrapIncoming(key, value));
                    return true;
                }

                @Override
                public int size() {
                    return original.size();
                }

                @Override
                public Iterator<Entry<String, Object>> iterator() {
                    return ConvertedMap.this.iterator();
                }
            };
        }

        private Iterator<Entry<String, Object>> iterator() {
            final Iterator<Entry<String, Object>> proxy = original.entrySet().iterator();

            return new Iterator<Entry<String, Object>>() {
                @Override
                public boolean hasNext() {
                    return proxy.hasNext();
                }

                @Override
                public Entry<String, Object> next() {
                    Entry<String, Object> entry = proxy.next();

                    return new SimpleEntry<String, Object>(
                            entry.getKey(), wrapOutgoing(entry.getValue())
                    );
                }

                @Override
                public void remove() {
                    proxy.remove();
                }
            };
        }

        @Override
        public Object getHandle() {
            return handle;
        }
    }

    /**
     * Represents a list that wraps another list and converts elements of its
     * type and another exposed type.
     *
     * @author Kristian
     */
    private class ConvertedList extends AbstractList<Object> implements Wrapper {

        private final Object handle;

        private final List<Object> original;
        private final CachedNativeWrapper cache = new CachedNativeWrapper();

        public ConvertedList(Object handle, List<Object> original) {
            if (NBT_LIST_TYPE == null) {
                NBT_LIST_TYPE = getField(handle, null, "type");
            }
            this.handle = handle;
            this.original = original;
        }

        protected Object wrapOutgoing(Object value) {
            return cache.wrap(value);
        }

        protected Object unwrapIncoming(Object wrapped) {
            return unwrapValue("", wrapped);
        }

        @Override
        public Object get(int index) {
            return wrapOutgoing(original.get(index));
        }

        @Override
        public int size() {
            return original.size();
        }

        @Override
        public Object set(int index, Object element) {
            return wrapOutgoing(
                    original.set(index, unwrapIncoming(element))
            );
        }

        @Override
        public void add(int index, Object element) {
            Object nbt = unwrapIncoming(element);

            // Set the list type if its the first element
            if (size() == 0) {
                setFieldValue(NBT_LIST_TYPE, handle, (byte) getNbtType(nbt).id);
            }
            original.add(index, nbt);
        }

        @Override
        public Object remove(int index) {
            return wrapOutgoing(original.remove(index));
        }

        @Override
        public boolean remove(Object o) {
            return original.remove(unwrapIncoming(o));
        }

        @Override
        public Object getHandle() {
            return handle;
        }
    }
}