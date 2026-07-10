// Vulkan capability probe for the Hardware panel (T1DMDROID issue 20 — STEP 5).
//
// A tiny, dependency-free JNI shim over the NDK's Vulkan loader (libvulkan.so). It
// creates a throwaway headless VkInstance (no surface, no layers, no extensions),
// enumerates the first physical device, and reports the GPU / driver / compute
// facts the panel wants: API version, device name + vendor, driver version, the
// COMPUTE queue-family count, subgroup size, fp16 compute support (shaderFloat16 +
// storageBuffer16BitAccess), the compute workgroup limits, maxStorageBufferRange,
// timestampPeriod, and the memory HEAPS (Mali is UNIFIED with system RAM — there is
// no discrete VRAM, and this is flagged, never invented).
//
// It returns a single newline-delimited "Label\tValue" string that Kotlin parses;
// every step is guarded so a failure degrades to a partial/"unavailable" report
// rather than crashing the process. Nothing here executes the model — this is pure
// device capability enumeration, valid EVEN IF the custom Vulkan-delegate AAR never
// builds.

#include <jni.h>
#include <string>
#include <vector>
#include <cstdio>
#include <cstdint>

#include <vulkan/vulkan.h>

namespace {

std::string ver_str(uint32_t v) {
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%u.%u.%u",
                  VK_API_VERSION_MAJOR(v), VK_API_VERSION_MINOR(v), VK_API_VERSION_PATCH(v));
    return buf;
}

std::string vendor_name(uint32_t id) {
    switch (id) {
        case 0x13B5: return "ARM";
        case 0x5143: return "Qualcomm";
        case 0x1010: return "ImgTec";
        case 0x10DE: return "NVIDIA";
        case 0x1002: return "AMD";
        case 0x8086: return "Intel";
        default:     return "unknown";
    }
}

std::string dev_type(VkPhysicalDeviceType t) {
    switch (t) {
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated GPU";
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU:   return "discrete GPU";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU:    return "virtual GPU";
        case VK_PHYSICAL_DEVICE_TYPE_CPU:            return "CPU";
        default:                                     return "other";
    }
}

void add(std::string& out, const char* label, const std::string& value) {
    out += label;
    out += '\t';
    out += value;
    out += '\n';
}

std::string mb(VkDeviceSize bytes) {
    char buf[48];
    std::snprintf(buf, sizeof(buf), "%.0f MB", (double)bytes / (1024.0 * 1024.0));
    return buf;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_t1dm_app_hardware_VulkanProbe_nativeProbe(JNIEnv* env, jclass) {
    std::string out;

    // Instance-level API version (may exceed what any one device supports).
    uint32_t instanceApi = VK_API_VERSION_1_0;
    auto pEnumInstanceVersion = (PFN_vkEnumerateInstanceVersion)
        vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
    if (pEnumInstanceVersion) pEnumInstanceVersion(&instanceApi);
    add(out, "Loader API", ver_str(instanceApi));

    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "t1dm-vk-probe";
    app.apiVersion = instanceApi >= VK_API_VERSION_1_1 ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0;

    VkInstanceCreateInfo ici{};
    ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ici.pApplicationInfo = &app;

    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&ici, nullptr, &instance) != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        add(out, "Status", "vkCreateInstance failed — Vulkan unavailable");
        return env->NewStringUTF(out.c_str());
    }

    uint32_t nDev = 0;
    vkEnumeratePhysicalDevices(instance, &nDev, nullptr);
    if (nDev == 0) {
        add(out, "Status", "no Vulkan physical device");
        vkDestroyInstance(instance, nullptr);
        return env->NewStringUTF(out.c_str());
    }
    std::vector<VkPhysicalDevice> devs(nDev);
    vkEnumeratePhysicalDevices(instance, &nDev, devs.data());
    VkPhysicalDevice phys = devs[0];

    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(phys, &props);
    add(out, "Device", props.deviceName);
    add(out, "Vendor", vendor_name(props.vendorID) + " (0x" +
        [&]{ char b[8]; std::snprintf(b, sizeof(b), "%04X", props.vendorID); return std::string(b); }() + ")");
    add(out, "Device type", dev_type(props.deviceType));
    add(out, "Device API", ver_str(props.apiVersion));
    {
        char b[24];
        std::snprintf(b, sizeof(b), "0x%08X", props.driverVersion);
        add(out, "Driver version", b);
    }

    // ── Compute limits (from core props) ──
    const VkPhysicalDeviceLimits& L = props.limits;
    add(out, "Max workgroup invocations", std::to_string(L.maxComputeWorkGroupInvocations));
    {
        char b[64];
        std::snprintf(b, sizeof(b), "%u x %u x %u",
                      L.maxComputeWorkGroupSize[0], L.maxComputeWorkGroupSize[1], L.maxComputeWorkGroupSize[2]);
        add(out, "Max workgroup size", b);
    }
    add(out, "Max storage buffer range", mb(L.maxStorageBufferRange));
    {
        char b[48];
        std::snprintf(b, sizeof(b), "%.3f ns", L.timestampPeriod);
        add(out, "Timestamp period", b);
    }
    {
        char b[64];
        std::snprintf(b, sizeof(b), "%u x %u x %u",
                      L.maxComputeWorkGroupCount[0], L.maxComputeWorkGroupCount[1], L.maxComputeWorkGroupCount[2]);
        add(out, "Max workgroup count", b);
    }
    add(out, "Max shared memory", std::to_string(L.maxComputeSharedMemorySize / 1024) + " KB");

    // ── Subgroup size (1.1 props2) ──
    auto pGetProps2 = (PFN_vkGetPhysicalDeviceProperties2)
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties2");
    if (pGetProps2 && instanceApi >= VK_API_VERSION_1_1) {
        VkPhysicalDeviceSubgroupProperties sg{};
        sg.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES;
        VkPhysicalDeviceProperties2 p2{};
        p2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
        p2.pNext = &sg;
        pGetProps2(phys, &p2);
        add(out, "Subgroup size", std::to_string(sg.subgroupSize));
        add(out, "Subgroup compute", (sg.supportedStages & VK_SHADER_STAGE_COMPUTE_BIT) ? "yes" : "no");
    } else {
        add(out, "Subgroup size", "n/a (needs Vulkan 1.1)");
    }

    // ── fp16 compute features (1.1 features2 + KHR structs) ──
    auto pGetFeat2 = (PFN_vkGetPhysicalDeviceFeatures2)
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2");
    if (pGetFeat2 && instanceApi >= VK_API_VERSION_1_1) {
        VkPhysicalDeviceShaderFloat16Int8Features f16{};
        f16.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES;
        VkPhysicalDevice16BitStorageFeatures s16{};
        s16.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES;
        f16.pNext = &s16;
        VkPhysicalDeviceFeatures2 feat2{};
        feat2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        feat2.pNext = &f16;
        pGetFeat2(phys, &feat2);
        add(out, "shaderFloat16", f16.shaderFloat16 ? "yes" : "no");
        add(out, "storageBuffer16BitAccess", s16.storageBuffer16BitAccess ? "yes" : "no");
        add(out, "uniformAndStorageBuffer16", s16.uniformAndStorageBuffer16BitAccess ? "yes" : "no");
    } else {
        add(out, "shaderFloat16", "n/a (needs Vulkan 1.1)");
    }

    // ── Queue families: compute-capable count + total ──
    uint32_t nQf = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(phys, &nQf, nullptr);
    std::vector<VkQueueFamilyProperties> qf(nQf);
    vkGetPhysicalDeviceQueueFamilyProperties(phys, &nQf, qf.data());
    uint32_t computeFamilies = 0, dedicatedCompute = 0, totalQueues = 0;
    for (const auto& q : qf) {
        totalQueues += q.queueCount;
        if (q.queueFlags & VK_QUEUE_COMPUTE_BIT) {
            computeFamilies++;
            if (!(q.queueFlags & VK_QUEUE_GRAPHICS_BIT)) dedicatedCompute++;
        }
    }
    add(out, "Queue families", std::to_string(nQf));
    add(out, "Compute-capable families", std::to_string(computeFamilies) +
        (dedicatedCompute ? " (" + std::to_string(dedicatedCompute) + " compute-only)" : ""));
    add(out, "Total queues", std::to_string(totalQueues));

    // ── Memory heaps (Mali = UNIFIED with system RAM; flagged, never a faux VRAM) ──
    VkPhysicalDeviceMemoryProperties mem{};
    vkGetPhysicalDeviceMemoryProperties(phys, &mem);
    bool anyDeviceLocalNonHost = false;
    for (uint32_t i = 0; i < mem.memoryHeapCount; i++) {
        bool devLocal = (mem.memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0;
        std::string line = mb(mem.memoryHeaps[i].size);
        if (devLocal) line += " · DEVICE_LOCAL";
        add(out, ("Heap " + std::to_string(i)).c_str(), line);
    }
    // Detect host-visible DEVICE_LOCAL (the unified-memory signature): a heap that is both
    // device-local and directly host-mappable means one physical pool shared with the CPU.
    for (uint32_t i = 0; i < mem.memoryTypeCount; i++) {
        auto f = mem.memoryTypes[i].propertyFlags;
        if ((f & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) && (f & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT))
            anyDeviceLocalNonHost = true;
    }
    add(out, "Memory model", anyDeviceLocalNonHost
        ? "UNIFIED (device-local heap is host-visible — shared with system RAM; no discrete VRAM)"
        : "heaps as listed");

    vkDestroyInstance(instance, nullptr);
    add(out, "Status", "ok");
    return env->NewStringUTF(out.c_str());
}
