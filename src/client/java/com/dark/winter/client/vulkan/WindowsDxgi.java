// Provides the Windows x64 COM calls needed for a Vulkan-to-DXGI presentation surface.
package com.dark.winter.client.vulkan;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

final class WindowsDxgi implements AutoCloseable {
    static final int SWAPCHAIN_BUFFERS = 3;
    static final int DXGI_FORMAT_RGBA8 = 28;
    static final String FACTORY_ID = "7632e1f5-ee65-4dca-87fd-84cd75f8838d";
    static final String ADAPTER_ID = "2411e7e1-12ac-4ccf-bd14-9798e8534dc0";
    static final String DEVICE_ID = "189819f1-1db6-4b57-be54-1821339b85f7";
    static final String RESOURCE_ID = "696442be-a72e-4059-bc79-5b5c98040fad";
    static final String QUEUE_ID = "0ec870a6-5d7e-4c22-8cfc-5baae07616ed";
    static final String ALLOCATOR_ID = "6102dee4-af59-4b09-b999-b44d73f09b24";
    static final String FENCE_ID = "0a753dcf-c4d8-4b91-adf6-be5a60d95a76";
    static final String COMMAND_LIST_ID = "5b160d0f-ac1b-4185-8ba8-b3ae42a5a455";
    static final String SWAPCHAIN_ID = "94d99bdb-f1f8-4ab0-b236-7da0170edab1";

    private static final SymbolLookup LIBRARIES = libraries();
    private static final Map<String, MethodHandle> FUNCTIONS = new HashMap<>();
    final Com factory;
    Com device, queue, fence;
    final int nodeMask;
    final boolean allowTearing;
    long sharedFence;

    WindowsDxgi(long luid, int nodeMask) {
        this.nodeMask = nodeMask;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            hresult((int)nativeCall("CreateDXGIFactory2", ValueLayout.JAVA_INT,
                    0, guid(arena, FACTORY_ID), out));
            factory = new Com(out.get(ValueLayout.ADDRESS, 0));
            try {
                MemorySegment support = arena.allocate(ValueLayout.JAVA_INT);
                int result = factory.integer(28, 0, support, 4);
                allowTearing = result >= 0 && support.get(ValueLayout.JAVA_INT, 0) != 0;
                try (Com adapter = factory.create(26, ADAPTER_ID, luid)) {
                    hresult((int)nativeCall("D3D12CreateDevice", ValueLayout.JAVA_INT,
                            adapter.address(), 0xb000, guid(arena, DEVICE_ID), out));
                    device = new Com(out.get(ValueLayout.ADDRESS, 0));
                }
                MemorySegment queueDesc = zero(arena, 16, 4);
                queueDesc.set(ValueLayout.JAVA_INT, 12, nodeMask);
                queue = device.create(8, QUEUE_ID, queueDesc);
                fence = device.create(36, FENCE_ID, 0L, 1);
                sharedFence = share(fence);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }
    }

    Com createSwapchain(long hwnd, int width, int height) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = zero(arena, 48, 4);
            ints(desc, width, height, DXGI_FORMAT_RGBA8, 0, 1, 0,
                    0, SWAPCHAIN_BUFFERS, 1, 4, 3, 64 | (allowTearing ? 2048 : 0));
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            hresult(factory.integer(15, queue.address(), hwnd, desc, MemorySegment.NULL, MemorySegment.NULL, out));
            try (Com swapchain1 = new Com(out.get(ValueLayout.ADDRESS, 0))) {
                Com swapchain3 = swapchain1.create(0, SWAPCHAIN_ID);
                try {
                    hresult(factory.integer(8, hwnd, 3));
                    hresult(swapchain3.integer(31, 2));
                    return swapchain3;
                } catch (RuntimeException | Error failure) {
                    swapchain3.close();
                    throw failure;
                }
            }
        }
    }

    Com createSharedTexture(int width, int height) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment heap = zero(arena, 20, 4);
            ints(heap, 1, 0, 0, nodeMask, nodeMask);
            MemorySegment desc = zero(arena, 56, 8);
            desc.set(ValueLayout.JAVA_INT, 0, 3);
            desc.set(ValueLayout.JAVA_LONG, 16, width);
            desc.set(ValueLayout.JAVA_INT, 24, height);
            desc.set(ValueLayout.JAVA_SHORT, 28, (short)1);
            desc.set(ValueLayout.JAVA_SHORT, 30, (short)1);
            desc.set(ValueLayout.JAVA_INT, 32, DXGI_FORMAT_RGBA8);
            desc.set(ValueLayout.JAVA_INT, 36, 1);
            return device.create(27, RESOURCE_ID, heap, 1, desc, 0, MemorySegment.NULL);
        }
    }

    Com createAllocator() { return device.create(9, ALLOCATOR_ID, 0); }

    Com createCommandList(Com allocator) {
        Com list = device.create(12, COMMAND_LIST_ID, nodeMask, 0, allocator.address(), MemorySegment.NULL);
        try {
            hresult(list.integer(9));
            return list;
        } catch (RuntimeException | Error failure) {
            list.close();
            throw failure;
        }
    }

    long share(Com object) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.JAVA_LONG);
            hresult(device.integer(31, object.address(), MemorySegment.NULL, 0x10000000, MemorySegment.NULL, out));
            long handle = out.get(ValueLayout.JAVA_LONG, 0);
            if (handle == 0) throw new IllegalStateException("D3D12 returned a null shared handle");
            return handle;
        }
    }

    void copy(Com list, Com allocator, Com destination, Com source) {
        hresult(allocator.integer(8));
        hresult(list.integer(10, allocator.address(), MemorySegment.NULL));
        list.nothing(17, destination.address(), source.address());
        hresult(list.integer(9));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pointers = arena.allocate(ValueLayout.ADDRESS);
            pointers.set(ValueLayout.ADDRESS, 0, list.address());
            // ExecuteCommandLists returns void, as specified by the native D3D12 interface.
            queue.nothing(10, 1, pointers);
        }
    }

    void waitForVulkan(long value) { hresult(queue.integer(15, fence.address(), value)); }

    void finishQueue(long value) {
        hresult(queue.integer(14, fence.address(), value));
        long event = (long)nativeCall("CreateEventW", ValueLayout.JAVA_LONG, MemorySegment.NULL, 0, 0, MemorySegment.NULL);
        if (event == 0) throw new IllegalStateException("Cannot create a D3D12 completion event");
        try {
            hresult(fence.integer(9, value, event));
            int result = (int)nativeCall("WaitForSingleObjectEx", ValueLayout.JAVA_INT, event, 5000, 0);
            if (result != 0) throw new IllegalStateException("D3D12 completion wait failed: 0x" + Integer.toHexString(result));
        } finally { closeHandle(event); }
    }

    static void closeHandle(long handle) {
        if (handle != 0 && (int)nativeCall("CloseHandle", ValueLayout.JAVA_INT, handle) == 0)
            throw new IllegalStateException("CloseHandle failed for a DXGI presentation handle");
    }

    static int waitFrame(long handle) {
        return (int)nativeCall("WaitForSingleObjectEx", ValueLayout.JAVA_INT, handle, 1000, 0);
    }

    @Override public void close() {
        RuntimeException failure = null;
        if (sharedFence != 0) {
            try { closeHandle(sharedFence); } catch (RuntimeException error) { failure = error; }
            sharedFence = 0;
        }
        for (Com object : new Com[] { fence, queue, device, factory }) {
            if (object == null) continue;
            try { object.close(); } catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    static MemorySegment zero(Arena arena, long size, long alignment) { return arena.allocate(size, alignment).fill((byte)0); }

    static MemorySegment guid(Arena arena, String id) {
        byte[] bytes = HexFormat.of().parseHex(id.replace("-", ""));
        byte[] nativeOrder = {bytes[3], bytes[2], bytes[1], bytes[0], bytes[5], bytes[4], bytes[7], bytes[6],
                bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]};
        MemorySegment target = arena.allocate(16, 4);
        target.copyFrom(MemorySegment.ofArray(nativeOrder));
        return target;
    }

    private static void ints(MemorySegment target, int... values) {
        for (int i = 0; i < values.length; i++) target.set(ValueLayout.JAVA_INT, i * 4L, values[i]);
    }

    static void hresult(int result) {
        if (result < 0) throw new IllegalStateException("DXGI/D3D12 HRESULT 0x" + Integer.toHexString(result));
    }

    private static SymbolLookup libraries() {
        Path system = Path.of(System.getenv("SystemRoot"), "System32").toAbsolutePath();
        return SymbolLookup.libraryLookup(system.resolve("dxgi.dll"), Arena.global())
                .or(SymbolLookup.libraryLookup(system.resolve("d3d12.dll"), Arena.global()))
                .or(SymbolLookup.libraryLookup(system.resolve("kernel32.dll"), Arena.global()));
    }

    private static FunctionDescriptor descriptor(MemoryLayout result, Object[] args) {
        MemoryLayout[] types = new MemoryLayout[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] instanceof MemorySegment ? ValueLayout.ADDRESS
                    : args[i] instanceof Long ? ValueLayout.JAVA_LONG : ValueLayout.JAVA_INT;
        }
        return result == null ? FunctionDescriptor.ofVoid(types) : FunctionDescriptor.of(result, types);
    }

    private static Object invoke(MethodHandle function, Object... args) {
        try { return function.invokeWithArguments(args); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Windows graphics call failed", failure); }
    }

    private static Object nativeCall(String name, MemoryLayout result, Object... args) {
        String key = name + descriptor(result, args);
        MethodHandle function = FUNCTIONS.computeIfAbsent(key, ignored ->
                Linker.nativeLinker().downcallHandle(LIBRARIES.find(name).orElseThrow(),
                        descriptor(result, args)));
        return invoke(function, args);
    }

    static final class Com implements AutoCloseable {
        private MemorySegment pointer;
        private final Map<Key, MethodHandle> functions = new HashMap<>();

        Com(MemorySegment pointer) {
            if (pointer.address() == 0) throw new IllegalStateException("Null Windows graphics interface");
            this.pointer = pointer;
        }

        MemorySegment address() {
            if (pointer.address() == 0) throw new IllegalStateException("Windows graphics interface is closed");
            return pointer;
        }

        int integer(int slot, Object... arguments) { return (int)call(slot, ValueLayout.JAVA_INT, arguments); }
        long handle(int slot) { return (long)call(slot, ValueLayout.JAVA_LONG); }
        void nothing(int slot, Object... arguments) { call(slot, null, arguments); }

        Com create(int slot, String iid, Object... arguments) {
            try (Arena arena = Arena.ofConfined()) {
                Object[] args = java.util.Arrays.copyOf(arguments, arguments.length + 2);
                args[arguments.length] = guid(arena, iid);
                MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
                args[arguments.length + 1] = out;
                hresult(integer(slot, args));
                return new Com(out.get(ValueLayout.ADDRESS, 0));
            }
        }

        private Object call(int slot, MemoryLayout result, Object... arguments) {
            Object[] args = new Object[arguments.length + 1];
            args[0] = address();
            System.arraycopy(arguments, 0, args, 1, arguments.length);
            FunctionDescriptor descriptor = descriptor(result, args);
            MethodHandle function = functions.computeIfAbsent(new Key(slot, descriptor), key -> {
                MemorySegment table = address().reinterpret(8).get(ValueLayout.ADDRESS, 0).reinterpret((slot + 1L) * 8);
                return Linker.nativeLinker().downcallHandle(table.get(ValueLayout.ADDRESS, slot * 8L), descriptor);
            });
            return invoke(function, args);
        }

        @Override public void close() {
            if (pointer.address() == 0) return;
            integer(2);
            pointer = MemorySegment.NULL;
        }

        private record Key(int slot, FunctionDescriptor descriptor) {}
    }
}
